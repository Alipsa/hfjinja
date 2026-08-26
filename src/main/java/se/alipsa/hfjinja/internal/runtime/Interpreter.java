package se.alipsa.hfjinja.internal.runtime;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;
import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.RenderOptions;
import se.alipsa.hfjinja.SourceLocation;
import se.alipsa.hfjinja.TemplateRenderException;
import se.alipsa.hfjinja.internal.HostFunctions;
import se.alipsa.hfjinja.internal.JsFormat;
import se.alipsa.hfjinja.internal.Value;
import se.alipsa.hfjinja.internal.ast.Expression;
import se.alipsa.hfjinja.internal.ast.Statement;
import se.alipsa.hfjinja.internal.util.JsSlice;
import se.alipsa.hfjinja.internal.util.PosixStrftime;

/** Internal evaluator entry point. */
@SuppressWarnings("doclint:missing")
public final class Interpreter {
  private Interpreter() {}

  /**
   * Renders a parsed program using the supplied context and options.
   *
   * @param program the parsed program
   * @param context the runtime context object
   * @param options render-time options
   * @param output destination for rendered text
   */
  public static void render(
      Statement.Program program,
      Value.ObjectValue context,
      RenderOptions options,
      Appendable output) {
    var env = new Environment(null);
    var budget = new RenderBudget(options);
    try {
      seed(env, options, program.location(), budget);
      for (var e : context.values().entrySet()) {
        if (e.getKey() instanceof String key) env.set(key, e.getValue());
      }
    } catch (IllegalStateException ex) {
      throw new TemplateRenderException(ex.getMessage(), ErrorCategory.VALUE, program.location());
    }
    ExecResult result;
    try {
      result = evaluateBlock(program.body(), env, budget);
    } catch (StackOverflowError overflow) {
      // RenderBudget.maxMacroDepth bounds macro/call-block *invocation* count, not total
      // interpreter recursion depth: a recursive macro whose body itself nests control-flow
      // constructs (nested {% for %}/{% if %}/{% call %}) consumes several interpreter stack
      // frames per invocation, so a deliberately or accidentally deep-nested body can still
      // exhaust the native JVM stack well below maxMacroDepth invocations. This catch is the
      // backstop that keeps that case inside this project's documented contract (render() only
      // throws TemplateRenderException) instead of letting a bare Error escape to a caller
      // sandboxing untrusted templates. Chaining the original StackOverflowError as the cause
      // matters here specifically: this catch cannot distinguish "template recursed too deep"
      // from a genuine interpreter bug (e.g. an AST cycle) that also exhausts the stack, so the
      // original stack trace must survive for diagnosis rather than being discarded.
      throw new TemplateRenderException(
          "Maximum interpreter recursion depth exceeded",
          overflow,
          ErrorCategory.RESOURCE_LIMIT,
          program.location());
    }
    if (!(result instanceof ExecResult.Normal normal))
      throw new TemplateRenderException(
          "break or continue outside a for loop", ErrorCategory.SYNTAX, program.location());
    try {
      output.append(normal.output());
    } catch (IOException ex) {
      throw new TemplateRenderException(
          "Unable to write rendered output", ex, ErrorCategory.OUTPUT, program.location());
    }
  }

  private static void seed(
      Environment env, RenderOptions o, SourceLocation l, RenderBudget budget) {
    env.set("false", new Value.BooleanValue(false));
    env.set("true", new Value.BooleanValue(true));
    env.set("none", Value.NullValue.INSTANCE);
    env.set("False", new Value.BooleanValue(false));
    env.set("True", new Value.BooleanValue(true));
    env.set("None", Value.NullValue.INSTANCE);
    env.set("range", new Value.CallableValue((a, k, x, s) -> range(a, k, x, budget)));
    env.set("raise_exception", new Value.CallableValue((a, k, x, s) -> raise(a, k, x)));
    env.set("strftime_now", new Value.CallableValue((a, k, x, s) -> strftime(a, k, x, o)));
    for (var e : o.hostFunctions().entrySet())
      env.set(
          e.getKey(),
          new Value.CallableValue(
              (a, k, x, s) -> HostFunctions.invoke(e.getKey(), e.getValue(), a, k, x)));
  }

  static ExecResult evaluateBlock(List<Statement> body, Environment env, RenderBudget budget) {
    var out = new StringBuilder();
    for (var node : body) {
      budget.chargeStep(node.location());
      var result = evaluateStatement(node, env, budget);
      if (!(result instanceof ExecResult.Normal n)) return result;
      out.append(n.output());
    }
    return new ExecResult.Normal(out.toString());
  }

  static ExecResult evaluateStatement(Statement n, Environment env, RenderBudget budget) {
    return switch (n) {
      case Statement.Program p -> evaluateBlock(p.body(), env, budget);
      case Statement.If i -> evaluateIf(i, env, budget);
      case Statement.For f -> evaluateFor(f, env, budget);
      case Statement.Break ignored -> ExecResult.Break.INSTANCE;
      case Statement.Continue ignored -> ExecResult.Continue.INSTANCE;
      case Statement.SetStatement s -> evaluateSet(s, env, budget);
      case Statement.Comment ignored -> new ExecResult.Normal("");
      case Statement.Macro m -> evaluateMacro(m, env, budget);
      case Statement.FilterStatement f -> evaluateFilterStatement(f, env, budget);
      case Statement.CallStatement c -> evaluateCallStatement(c, env, budget);
      case Expression e -> {
        var v = evaluateExpression(e, env, budget);
        String t =
            v instanceof Value.NullValue || v instanceof Value.UndefinedValue
                ? ""
                : renderText(v, e.location());
        budget.chargeOutput(t.length(), e.location());
        yield new ExecResult.Normal(t);
      }
    };
  }

  static Value evaluateExpression(Expression n, Environment env, RenderBudget budget) {
    return switch (n) {
      case Expression.Identifier x -> env.lookupVariable(x.value());
      case Expression.IntegerLiteral x -> new Value.IntegerValue(x.value());
      case Expression.FloatLiteral x -> new Value.FloatValue(x.value());
      case Expression.StringLiteral x -> new Value.StringValue(x.value());
      case Expression.ArrayLiteral x -> new Value.ArrayValue(values(x.value(), env, budget));
      case Expression.TupleLiteral x -> new Value.TupleValue(values(x.value(), env, budget));
      case Expression.ObjectLiteral x -> object(x, env, budget);
      case Expression.MemberExpression x -> member(x, env, budget);
      case Expression.CallExpression x -> call(x, env, budget);
      case Expression.SelectExpression x ->
          truthy(evaluateExpression(x.test(), env, budget))
              ? evaluateExpression(x.lhs(), env, budget)
              : Value.UndefinedValue.INSTANCE;
      case Expression.BinaryExpression x -> binary(x, env, budget);
      case Expression.UnaryExpression x -> unary(x, env, budget);
      case Expression.FilterExpression x -> filter(x, env, budget);
      case Expression.TestExpression x -> test(x, env, budget);
      case Expression.Ternary x -> ternary(x, env, budget);
      // SliceExpression/KeywordArgumentExpression/SpreadExpression are the only three sealed
      // Expression cases with no real evaluation logic here, and that is not a gap: the parser
      // constructs each of them in exactly one place, and every structural position they can
      // occupy is intercepted before it can ever reach this generic dispatch.
      // Parser.parseMemberExpressionArgumentsList is the only site that builds a SliceExpression,
      // and it always becomes the `property` of a computed MemberExpression; member()
      // special-cases `n.computed() && n.property() instanceof SliceExpression` before ever
      // calling evaluateExpression on that property. Parser.parseArgumentsList is the only site
      // that builds a KeywordArgumentExpression or SpreadExpression, feeding a CallExpression's
      // argument list, a Macro's own parameter list, or a {% call %} block's caller parameter
      // list; none of those three consumers ever passes the kwarg/spread node itself to
      // evaluateExpression — evaluateArguments recurses only into keyword.value()/
      // spread.argument(), and the macro and caller-parameter loops reject unexpected node types
      // outright. No valid AST from this parser can hand one of these three node types to this
      // switch directly, so — matching renderText's identical NullValue/UndefinedValue arms below
      // — these throw AssertionError rather than a template-facing exception: reaching here is an
      // interpreter bug, not a template author's mistake.
      case Expression.SliceExpression x -> throw unreachable(x);
      case Expression.KeywordArgumentExpression x -> throw unreachable(x);
      case Expression.SpreadExpression x -> throw unreachable(x);
    };
  }

  private static Value ternary(
      Expression.Ternary expression, Environment env, RenderBudget budget) {
    return truthy(evaluateExpression(expression.condition(), env, budget))
        ? evaluateExpression(expression.trueExpr(), env, budget)
        : evaluateExpression(expression.falseExpr(), env, budget);
  }

  private static AssertionError unreachable(Expression n) {
    return new AssertionError(
        "unreachable: " + n.getClass().getSimpleName() + " at " + n.location());
  }

  private static Value binary(
      Expression.BinaryExpression expression, Environment env, RenderBudget budget) {
    var left = evaluateExpression(expression.left(), env, budget);
    var operator = expression.operator().value();
    if (operator.equals("and"))
      return truthy(left) ? evaluateExpression(expression.right(), env, budget) : left;
    if (operator.equals("or"))
      return truthy(left) ? left : evaluateExpression(expression.right(), env, budget);

    var right = evaluateExpression(expression.right(), env, budget);
    if (operator.equals("==") || operator.equals("!=")) {
      boolean equal = JsOperations.looseEquals(left, right);
      return new Value.BooleanValue(operator.equals("==") ? equal : !equal);
    }
    if (left instanceof Value.UndefinedValue || right instanceof Value.UndefinedValue) {
      if (right instanceof Value.UndefinedValue
          && (operator.equals("in") || operator.equals("not in")))
        return new Value.BooleanValue(operator.equals("not in"));
      throw operatorUndefined(operator, expression.location());
    }
    if (left instanceof Value.NullValue || right instanceof Value.NullValue)
      throw operatorNull(expression.location());
    if (operator.equals("~"))
      return new Value.StringValue(
          JsOperations.payloadText(left, expression.location())
              + JsOperations.payloadText(right, expression.location()));
    if (JsOperations.numeric(left) && JsOperations.numeric(right)) {
      if (operator.equals("+")) return JsOperations.add(left, right, expression.location());
      if (operator.equals("-")
          || operator.equals("*")
          || operator.equals("/")
          || operator.equals("%")) return JsOperations.arithmetic(operator, left, right);
      if (operator.equals("<")
          || operator.equals(">")
          || operator.equals("<=")
          || operator.equals(">="))
        return new Value.BooleanValue(JsOperations.compare(operator, left, right));
    } else if (arrayLike(left) && arrayLike(right)) {
      if (operator.equals("+"))
        return JsOperations.concatenate(arrayValues(left), arrayValues(right));
    } else if (arrayLike(right)
        && !(left instanceof Value.ArrayValue || left instanceof Value.TupleValue)) {
      if (operator.equals("in") || operator.equals("not in")) {
        boolean present = JsOperations.contains(left, new Value.ArrayValue(arrayValues(right)));
        return new Value.BooleanValue(operator.equals("in") ? present : !present);
      }
    }
    if (left instanceof Value.StringValue || right instanceof Value.StringValue) {
      if (operator.equals("+")) return JsOperations.add(left, right, expression.location());
    }
    if (left instanceof Value.StringValue string && right instanceof Value.StringValue other) {
      if (operator.equals("in") || operator.equals("not in")) {
        boolean present = JsOperations.contains(string, other);
        return new Value.BooleanValue(operator.equals("in") ? present : !present);
      }
    }
    if (left instanceof Value.StringValue string && right instanceof Value.ObjectValue object) {
      if (operator.equals("in") || operator.equals("not in")) {
        boolean present = JsOperations.contains(string, object);
        return new Value.BooleanValue(operator.equals("in") ? present : !present);
      }
    }
    throw operatorUnsupportedTypes(operator, left, right, expression.location());
  }

  private static boolean arrayLike(Value value) {
    return value instanceof Value.ArrayValue || value instanceof Value.TupleValue;
  }

  private static List<Value> arrayValues(Value value) {
    return value instanceof Value.ArrayValue array
        ? array.values()
        : ((Value.TupleValue) value).values();
  }

  private static Value unary(
      Expression.UnaryExpression expression, Environment env, RenderBudget budget) {
    var argument = evaluateExpression(expression.argument(), env, budget);
    if (expression.operator().value().equals("not"))
      return new Value.BooleanValue(!JsOperations.rawTruthy(argument));
    throw operatorUnsupportedUnary(expression.operator().value(), argument, expression.location());
  }

  private record NamedArguments(String name, List<Value> positional, Map<String, Value> keywords) {}

  private static Value filter(
      Expression.FilterExpression expression, Environment env, RenderBudget budget) {
    var operand = evaluateExpression(expression.operand(), env, budget);
    return applyFilter(operand, expression.filter(), env, budget, expression.location());
  }

  private static Value applyFilter(
      Value operand,
      Expression filterNode,
      Environment env,
      RenderBudget budget,
      SourceLocation location) {
    // Known gap (see docs/superpowers/plans/2026-08-23-wp5-slice2-spread-call-arguments.md,
    // "Known gaps this slice leaves open" — eager filter-argument evaluation): this evaluates and
    // validates every filter argument, including spreads, before dispatch on filter.name() below,
    // whereas upstream evaluates arguments only inside the matching dispatch branch.
    var filter = namedArguments(filterNode, env, budget, location, "filter");
    return switch (filter.name()) {
      // Upstream recognizes `safe` only in its bare-filter form. There is no autoescape or
      // markup runtime in hfjinja, so preserving the evaluated value is the whole operation.
      case "safe" -> {
        if (!(filterNode instanceof Expression.Identifier))
          throw filterType("Unknown filter: safe", location);
        yield operand;
      }
      case "tojson" -> filterToJson(operand, filter, location, budget);
      case "default" -> {
        if (filterNode instanceof Expression.Identifier)
          throw filterType("`default` filter must be called with parentheses", location);
        yield filterDefault(operand, filter, location);
      }
      case "length" -> filterLength(operand, filter, location);
      case "lower" ->
          filterString(operand, filter, location, value -> value.toLowerCase(Locale.ROOT));
      case "upper" ->
          filterString(operand, filter, location, value -> value.toUpperCase(Locale.ROOT));
      case "trim" -> filterString(operand, filter, location, JsOperations::trimEcmaWhitespace);
      case "join" -> filterJoin(operand, filter, location);
      case "list" -> filterList(operand, filter, location);
      case "items" -> filterItems(operand, filter, location);
      case "first" -> filterFirstLast(operand, filter, location, false);
      case "last" -> filterFirstLast(operand, filter, location, true);
      case "reverse" -> filterReverse(operand, filter, location);
      case "unique" -> filterUnique(operand, filter, location);
      case "sort" -> filterSort(operand, filter, location);
      case "map" -> filterMap(operand, filter, location);
      case "string" -> filterToString(operand, filter, location);
      case "title" -> filterString(operand, filter, location, Interpreter::titleCase);
      case "capitalize" -> filterString(operand, filter, location, Interpreter::capitalize);
      case "abs" -> filterAbs(operand, filter, location);
      case "bool" -> filterBool(operand, filter, location);
      case "indent" -> filterIndent(operand, filter, location, budget);
      case "replace" -> filterReplace(operand, filter, location);
      case "keys", "values", "dictsort", "get" -> filterObjectBuiltin(operand, filter, location);
      case "selectattr" -> filterSelectAttr(operand, filter, location, true);
      case "rejectattr" -> filterSelectAttr(operand, filter, location, false);
      case "int" -> filterNumber(operand, filter, location, true);
      case "float" -> filterNumber(operand, filter, location, false);
      default -> throw filterType("Unknown filter: " + filter.name(), location);
    };
  }

  private static Value test(
      Expression.TestExpression expression, Environment env, RenderBudget budget) {
    var operand = evaluateExpression(expression.operand(), env, budget);
    boolean result = namedTest(expression.test().value(), operand, null, expression.location());
    return new Value.BooleanValue(expression.negate() ? !result : result);
  }

  private static Value filterToJson(
      Value operand, NamedArguments filter, SourceLocation location, RenderBudget budget) {
    Value indent = filter.keywords().getOrDefault("indent", Value.NullValue.INSTANCE);
    if (!(indent instanceof Value.NullValue || indent instanceof Value.IntegerValue))
      throw new TemplateRenderException(
          "If set, indent must be a number", ErrorCategory.TYPE, location);
    Double indentValue = indent instanceof Value.IntegerValue number ? number.value() : null;

    Value ensureAscii =
        filter.keywords().getOrDefault("ensure_ascii", new Value.BooleanValue(false));
    if (!(ensureAscii instanceof Value.BooleanValue ensureAsciiValue))
      throw new TemplateRenderException(
          "If set, ensure_ascii must be a boolean", ErrorCategory.TYPE, location);
    Value sortKeys = filter.keywords().getOrDefault("sort_keys", new Value.BooleanValue(false));
    if (!(sortKeys instanceof Value.BooleanValue sortKeysValue))
      throw new TemplateRenderException(
          "If set, sort_keys must be a boolean", ErrorCategory.TYPE, location);
    Value separators = filter.keywords().getOrDefault("separators", Value.NullValue.INSTANCE);
    JsFormat.JsonSeparators separatorValues = null;
    if (separators instanceof Value.ArrayValue array)
      separatorValues = jsonSeparators(array.values(), location);
    else if (separators instanceof Value.TupleValue tuple)
      separatorValues = jsonSeparators(tuple.values(), location);
    else if (!(separators instanceof Value.NullValue))
      throw new TemplateRenderException(
          "If set, separators must be a tuple of two strings", ErrorCategory.TYPE, location);
    return new Value.StringValue(
        JsFormat.runtimeJson(
            operand,
            location,
            true,
            new JsFormat.JsonOptions(
                indentValue,
                ensureAsciiValue.value(),
                sortKeysValue.value(),
                separatorValues,
                budget.remainingOutputLength())));
  }

  private static JsFormat.JsonSeparators jsonSeparators(
      List<Value> values, SourceLocation location) {
    if (values.size() != 2
        || !(values.get(0) instanceof Value.StringValue first)
        || !(values.get(1) instanceof Value.StringValue second))
      throw new TemplateRenderException(
          "separators must be a tuple of two strings", ErrorCategory.TYPE, location);
    return new JsFormat.JsonSeparators(
        first.undefinedBacked() ? null : first.value(),
        second.undefinedBacked() ? null : second.value());
  }

  private static Value filterDefault(
      Value operand, NamedArguments filter, SourceLocation location) {
    // Known gap (see docs/superpowers/plans/2026-08-23-wp5-slice2-spread-call-arguments.md,
    // "Known gaps this slice leaves open" — strict unknown-keyword rejection): upstream silently
    // ignores keywords outside "boolean" instead of throwing.
    requireNoUnknownKeywords(filter, location, "boolean");
    // Known gap (same doc, "per-filter arity caps"): upstream ignores extra positional arguments
    // past two instead of throwing.
    if (filter.positional().size() > 2)
      throw new TemplateRenderException(
          "`default` filter accepts at most two arguments", ErrorCategory.ARITY, location);
    var fallback =
        filter.positional().isEmpty() ? new Value.StringValue("") : filter.positional().get(0);
    Value booleanFlag =
        filter.positional().size() > 1
            ? filter.positional().get(1)
            : filter.keywords().get("boolean");
    if (booleanFlag == null) booleanFlag = new Value.BooleanValue(false);
    if (!(booleanFlag instanceof Value.BooleanValue flag))
      throw new TemplateRenderException(
          "`default` filter flag must be a boolean", ErrorCategory.TYPE, location);
    return undefinedLike(operand) || flag.value() && !truthy(operand) ? fallback : operand;
  }

  private static Value filterLength(Value operand, NamedArguments filter, SourceLocation location) {
    requireNoArguments(filter, location);
    int length;
    if (operand instanceof Value.ArrayValue array) length = array.values().size();
    else if (operand instanceof Value.TupleValue tuple) length = tuple.values().size();
    else if (operand instanceof Value.StringValue string && !string.undefinedBacked())
      length = string.value().length();
    else if (operand instanceof Value.ObjectValue object) length = object.values().size();
    else throw filterReceiver("length", operand, location);
    return new Value.IntegerValue(length);
  }

  private static Value filterString(
      Value operand,
      NamedArguments filter,
      SourceLocation location,
      java.util.function.UnaryOperator<String> operation) {
    requireNoArguments(filter, location);
    if (!(operand instanceof Value.StringValue string) || string.undefinedBacked())
      throw filterReceiver(filter.name(), operand, location);
    return new Value.StringValue(operation.apply(string.value()));
  }

  private static Value filterJoin(Value operand, NamedArguments filter, SourceLocation location) {
    // Known gap (see docs/superpowers/plans/2026-08-23-wp5-slice2-spread-call-arguments.md,
    // "Known gaps this slice leaves open" — strict unknown-keyword rejection): upstream silently
    // ignores keywords outside "separator" instead of throwing.
    requireNoUnknownKeywords(filter, location, "separator");
    // Known gap (same doc, "per-filter arity caps"): upstream ignores extra positional arguments
    // past one instead of throwing.
    if (filter.positional().size() > 1)
      throw new TemplateRenderException(
          "`join` filter accepts at most one argument", ErrorCategory.ARITY, location);
    Value separator =
        filter.positional().isEmpty()
            ? filter.keywords().get("separator")
            : filter.positional().get(0);
    if (separator == null) separator = new Value.StringValue("");
    if (!(separator instanceof Value.StringValue string) || string.undefinedBacked())
      throw new TemplateRenderException("separator must be a string", ErrorCategory.TYPE, location);
    List<Value> values;
    if (operand instanceof Value.ArrayValue array) values = array.values();
    else if (operand instanceof Value.TupleValue tuple) values = tuple.values();
    else if (operand instanceof Value.StringValue value && !value.undefinedBacked()) {
      values =
          value
              .value()
              .codePoints()
              .mapToObj(c -> (Value) new Value.StringValue(new String(Character.toChars(c))))
              .toList();
    } else throw filterReceiver("join", operand, location);
    return new Value.StringValue(
        values.stream()
            .map(v -> joinText(v, location))
            .collect(java.util.stream.Collectors.joining(string.value())));
  }

  private static Value filterList(Value operand, NamedArguments filter, SourceLocation location) {
    requireNoArguments(filter, location);
    if (operand instanceof Value.ArrayValue array) return array;
    if (operand instanceof Value.TupleValue tuple) return tuple;
    throw filterReceiver("list", operand, location);
  }

  private static Value filterItems(Value operand, NamedArguments filter, SourceLocation location) {
    requireNoArguments(filter, location);
    if (!(operand instanceof Value.ObjectValue object))
      throw filterReceiver("items", operand, location);
    return itemsOf(object);
  }

  private static List<Value> sequence(Value operand, String name, SourceLocation location) {
    if (operand instanceof Value.ArrayValue array) return array.values();
    if (operand instanceof Value.TupleValue tuple) return tuple.values();
    throw filterReceiver(name, operand, location);
  }

  private static Value filterFirstLast(
      Value operand, NamedArguments filter, SourceLocation location, boolean last) {
    requireNoArguments(filter, location);
    var values = sequence(operand, filter.name(), location);
    return values.isEmpty()
        ? Value.UndefinedValue.INSTANCE
        : values.get(last ? values.size() - 1 : 0);
  }

  private static Value filterReverse(
      Value operand, NamedArguments filter, SourceLocation location) {
    requireNoArguments(filter, location);
    var values = new ArrayList<>(sequence(operand, filter.name(), location));
    Collections.reverse(values);
    return new Value.ArrayValue(values);
  }

  private static Value filterUnique(Value operand, NamedArguments filter, SourceLocation location) {
    requireNoArguments(filter, location);
    var result = new ArrayList<Value>();
    for (var value : sequence(operand, filter.name(), location))
      if (result.stream().noneMatch(existing -> JsOperations.strictValueEquals(existing, value)))
        result.add(value);
    return new Value.ArrayValue(result);
  }

  private static Value filterAbs(Value operand, NamedArguments filter, SourceLocation location) {
    requireNoArguments(filter, location);
    if (operand instanceof Value.IntegerValue number)
      return new Value.IntegerValue(Math.abs(number.value()));
    if (operand instanceof Value.FloatValue number)
      return new Value.FloatValue(Math.abs(number.value()));
    throw filterReceiver(filter.name(), operand, location);
  }

  private static Value filterSort(Value operand, NamedArguments filter, SourceLocation location) {
    var values = new ArrayList<>(sequence(operand, filter.name(), location));
    boolean reverse = filterBoolean(filter, 0, "reverse", false, location);
    boolean caseSensitive = filterBoolean(filter, 1, "case_sensitive", false, location);
    Value attribute = filterArgument(filter, 2, "attribute");
    if (attribute != null
        && !(attribute instanceof Value.NullValue
            || attribute instanceof Value.StringValue
            || attribute instanceof Value.IntegerValue))
      throw filterType("attribute must be a string, integer, or null", location);
    values.sort(
        (left, right) -> {
          Value a = sortValue(left, attribute);
          Value b = sortValue(right, attribute);
          int comparison = compareValues(a, b, caseSensitive, location);
          return reverse ? -comparison : comparison;
        });
    return new Value.ArrayValue(values);
  }

  private static Value filterMap(Value operand, NamedArguments filter, SourceLocation location) {
    var attribute = filter.keywords().get("attribute");
    if (!(attribute instanceof Value.StringValue string) || string.undefinedBacked())
      throw filterType("attribute must be a string", location);
    Value fallback = filter.keywords().getOrDefault("default", Value.UndefinedValue.INSTANCE);
    var values = new ArrayList<Value>();
    for (var item : sequence(operand, filter.name(), location)) {
      if (!(item instanceof Value.ObjectValue))
        throw filterType("items in map must be an object", location);
      Value mapped = attribute(item, string.value());
      values.add(mapped instanceof Value.UndefinedValue ? fallback : mapped);
    }
    return new Value.ArrayValue(values);
  }

  private static Value filterIndent(
      Value operand, NamedArguments filter, SourceLocation location, RenderBudget budget) {
    if (!(operand instanceof Value.StringValue string) || string.undefinedBacked())
      throw filterReceiver(filter.name(), operand, location);
    Value width = filterArgument(filter, 0, "width");
    if (width == null) width = new Value.IntegerValue(4);
    if (!(width instanceof Value.IntegerValue number))
      throw filterType("width must be a number", location);
    boolean first = filterTruthy(filterArgument(filter, 1, "first"), false);
    boolean blank = filterTruthy(filterArgument(filter, 2, "blank"), false);
    if (number.value() < 0)
      throw new TemplateRenderException(
          "Invalid count value: " + JsFormat.plainString(number.value()),
          ErrorCategory.VALUE,
          location);
    if (number.value() > budget.remainingOutputLength())
      throw new TemplateRenderException(
          "Maximum render output length exceeded", ErrorCategory.RESOURCE_LIMIT, location);
    var lines = string.value().split("\\n", -1);
    String prefix = " ".repeat((int) number.value());
    for (int index = 0; index < lines.length; index++)
      if ((first || index != 0) && (blank || !lines[index].isEmpty()))
        lines[index] = prefix + lines[index];
    return new Value.StringValue(String.join("\n", lines));
  }

  private static Value filterReplace(
      Value operand, NamedArguments filter, SourceLocation location) {
    if (!(operand instanceof Value.StringValue string) || string.undefinedBacked())
      throw filterReceiver(filter.name(), operand, location);
    if (filter.positional().size() < 2)
      throw filterType("replace() requires at least two arguments", location);
    Value old = filter.positional().isEmpty() ? null : filter.positional().getFirst();
    Value replacement = filter.positional().size() < 2 ? null : filter.positional().get(1);
    if (!(old instanceof Value.StringValue oldString)
        || !(replacement instanceof Value.StringValue newString))
      throw filterType("replace() arguments must be strings", location);
    Value count =
        filter.positional().size() > 2
            ? filter.positional().get(2)
            : filter.keywords().get("count");
    if (count != null
        && !(count instanceof Value.IntegerValue)
        && !(count instanceof Value.NullValue))
      throw filterType("replace() count argument must be a number or null", location);
    return new Value.StringValue(
        replace(
            string.value(),
            oldString.value(),
            newString.value(),
            count instanceof Value.IntegerValue number ? (int) number.value() : -1));
  }

  private static Value filterArgument(NamedArguments filter, int index, String key) {
    return filter.positional().size() > index
        ? filter.positional().get(index)
        : filter.keywords().get(key);
  }

  private static boolean filterBoolean(
      NamedArguments filter, int index, String key, boolean fallback, SourceLocation location) {
    Value value = filterArgument(filter, index, key);
    if (value == null) return fallback;
    if (value instanceof Value.BooleanValue booleanValue) return booleanValue.value();
    throw filterType(key + " must be a boolean", location);
  }

  private static boolean filterTruthy(Value value, boolean fallback) {
    // The upstream indent implementation applies JavaScript's raw !value check, rather than
    // Jinja's container-aware truthiness. In particular, [] and {} enable indentation here.
    return value == null ? fallback : JsOperations.rawTruthy(value);
  }

  private static Value filterObjectBuiltin(
      Value operand, NamedArguments filter, SourceLocation location) {
    if (!(operand instanceof Value.ObjectValue object))
      throw filterReceiver(filter.name(), operand, location);
    var arguments = new ArrayList<>(filter.positional());
    if (!filter.keywords().isEmpty())
      arguments.add(new Value.KeywordArgumentsValue(filter.keywords()));
    return ((Value.CallableValue) objectBuiltin(object, filter.name()))
        .callable()
        .invoke(arguments, !filter.keywords().isEmpty(), location, null);
  }

  private static Value sortValue(Value value, Value attribute) {
    if (attribute == null || attribute instanceof Value.NullValue) return value;
    String path =
        attribute instanceof Value.StringValue string
            ? string.value()
            : JsFormat.plainString(((Value.IntegerValue) attribute).value());
    return attribute(value, path);
  }

  private static Value attribute(Value value, String path) {
    for (var part : path.split("\\.")) {
      if (value instanceof Value.ObjectValue object)
        value = object.values().getOrDefault(part, Value.UndefinedValue.INSTANCE);
      else if (value instanceof Value.ArrayValue array) {
        try {
          int index = Integer.parseInt(part);
          value =
              index >= 0 && index < array.values().size()
                  ? array.values().get(index)
                  : Value.UndefinedValue.INSTANCE;
        } catch (NumberFormatException ignored) {
          return Value.UndefinedValue.INSTANCE;
        }
      } else return Value.UndefinedValue.INSTANCE;
    }
    return value;
  }

  private static Value filterBool(Value operand, NamedArguments filter, SourceLocation location) {
    requireNoArguments(filter, location);
    if (operand instanceof Value.BooleanValue) return operand;
    throw filterReceiver(filter.name(), operand, location);
  }

  private static String titleCase(String value) {
    var result = new StringBuilder(value.length());
    boolean previousWord = false;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      boolean word =
          (character >= 'A' && character <= 'Z')
              || (character >= 'a' && character <= 'z')
              || (character >= '0' && character <= '9')
              || character == '_';
      result.append(word && !previousWord ? Character.toUpperCase(character) : character);
      previousWord = word;
    }
    return result.toString();
  }

  private static String capitalize(String value) {
    return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }

  private static Value filterToString(
      Value operand, NamedArguments filter, SourceLocation location) {
    requireNoArguments(filter, location);
    if (operand instanceof Value.StringValue string) return string;
    if (operand instanceof Value.ArrayValue
        || operand instanceof Value.TupleValue
        || operand instanceof Value.IntegerValue
        || operand instanceof Value.FloatValue
        || operand instanceof Value.BooleanValue)
      return new Value.StringValue(renderText(operand, location));
    throw filterReceiver("string", operand, location);
  }

  private static Value filterSelectAttr(
      Value operand, NamedArguments filter, SourceLocation location, boolean select) {
    if (!filter.keywords().isEmpty())
      throw new TemplateRenderException(
          "`" + filter.name() + "` filter does not accept keyword arguments",
          ErrorCategory.ARITY,
          location);
    if (filter.positional().isEmpty() || filter.positional().size() > 3)
      throw new TemplateRenderException(
          "`" + filter.name() + "` filter requires 1 to 3 arguments",
          ErrorCategory.ARITY,
          location);
    List<Value> values;
    if (operand instanceof Value.ArrayValue array) values = array.values();
    else if (operand instanceof Value.TupleValue tuple) values = tuple.values();
    else throw filterReceiver(filter.name(), operand, location);
    for (var item : values)
      if (!(item instanceof Value.ObjectValue))
        throw new TemplateRenderException(
            "`" + filter.name() + "` can only be applied to array of objects",
            ErrorCategory.TYPE,
            location);
    var attr = requireFilterString(filter, 0, location);
    String testName =
        filter.positional().size() > 1 ? requireFilterString(filter, 1, location).value() : null;
    Value comparison = filter.positional().size() > 2 ? filter.positional().get(2) : null;
    var result = new ArrayList<Value>();
    for (var item : values) {
      var attrValue = ((Value.ObjectValue) item).values().get(attr.value());
      boolean matched =
          attrValue != null
              && (testName == null
                  ? truthy(attrValue)
                  : namedTest(testName, attrValue, comparison, location));
      if (matched == select) result.add(item);
    }
    return new Value.ArrayValue(result);
  }

  private static Value.StringValue requireFilterString(
      NamedArguments filter, int index, SourceLocation location) {
    var value = filter.positional().get(index);
    if (value instanceof Value.StringValue string && !string.undefinedBacked()) return string;
    throw new TemplateRenderException(
        "`" + filter.name() + "` arguments must be strings", ErrorCategory.TYPE, location);
  }

  private static boolean namedTest(
      String name, Value value, Value comparison, SourceLocation location) {
    return switch (name) {
      case "equalto", "eq" -> {
        if (comparison == null)
          throw filterType("`" + name + "` test requires a comparison value", location);
        yield JsOperations.strictEquals(value, comparison);
      }
      case "defined" -> !undefinedLike(value);
      case "undefined" -> undefinedLike(value);
      case "none" -> value instanceof Value.NullValue;
      case "true" -> value instanceof Value.BooleanValue booleanValue && booleanValue.value();
      case "false" -> value instanceof Value.BooleanValue booleanValue && !booleanValue.value();
      case "boolean" -> value instanceof Value.BooleanValue;
      case "callable" -> value instanceof Value.CallableValue;
      case "odd" -> integerTest(value, true, location);
      case "even" -> integerTest(value, false, location);
      case "integer" -> value instanceof Value.IntegerValue;
      case "lower" ->
          value instanceof Value.StringValue string
              && !string.undefinedBacked()
              && string.value().equals(string.value().toLowerCase(Locale.ROOT));
      case "upper" ->
          value instanceof Value.StringValue string
              && !string.undefinedBacked()
              && string.value().equals(string.value().toUpperCase(Locale.ROOT));
      case "number" -> JsOperations.numeric(value);
      case "string" -> value instanceof Value.StringValue string && !string.undefinedBacked();
      case "mapping" ->
          value instanceof Value.ObjectValue || value instanceof Value.KeywordArgumentsValue;
      case "iterable" ->
          value instanceof Value.ArrayValue
              || value instanceof Value.StringValue string && !string.undefinedBacked();
      case "sequence" ->
          value instanceof Value.ArrayValue
              || value instanceof Value.TupleValue
              || value instanceof Value.ObjectValue
              || value instanceof Value.KeywordArgumentsValue
              || value instanceof Value.StringValue string && !string.undefinedBacked();
      default -> throw filterType("Unknown test: " + name, location);
    };
  }

  private static boolean integerTest(Value value, boolean odd, SourceLocation location) {
    if (!(value instanceof Value.IntegerValue number))
      throw filterType("cannot " + (odd ? "odd" : "even") + " on " + type(value), location);
    return (number.value() % 2 != 0) == odd;
  }

  private static String joinText(Value value, SourceLocation location) {
    return switch (value) {
      case Value.NullValue ignored -> "";
      case Value.UndefinedValue ignored -> "";
      case Value.StringValue string -> string.undefinedBacked() ? "" : string.value();
      case Value.ArrayValue array ->
          array.values().stream()
              .map(item -> joinText(item, location))
              .collect(java.util.stream.Collectors.joining(","));
      case Value.TupleValue tuple ->
          tuple.values().stream()
              .map(item -> joinText(item, location))
              .collect(java.util.stream.Collectors.joining(","));
      default -> JsOperations.payloadText(value, location);
    };
  }

  private static Value filterNumber(
      Value operand, NamedArguments filter, SourceLocation location, boolean integer) {
    // Known gap (see docs/superpowers/plans/2026-08-23-wp5-slice2-spread-call-arguments.md,
    // "Known gaps this slice leaves open" — strict unknown-keyword rejection): upstream silently
    // ignores keywords outside "default" instead of throwing.
    requireNoUnknownKeywords(filter, location, "default");
    // Known gap (same doc, "per-filter arity caps"): upstream ignores extra positional arguments
    // past one instead of throwing.
    if (filter.positional().size() > 1)
      throw new TemplateRenderException(
          "`" + filter.name() + "` filter accepts at most one argument",
          ErrorCategory.ARITY,
          location);
    var fallback =
        filter.positional().isEmpty()
            ? filter.keywords().get("default")
            : filter.positional().get(0);
    if (fallback == null) fallback = integer ? new Value.IntegerValue(0) : new Value.FloatValue(0);
    if (operand instanceof Value.IntegerValue value)
      return integer ? value : new Value.FloatValue(value.value());
    if (operand instanceof Value.FloatValue value)
      return integer ? new Value.IntegerValue(Math.floor(value.value())) : value;
    if (operand instanceof Value.BooleanValue value)
      return integer
          ? new Value.IntegerValue(value.value() ? 1 : 0)
          : new Value.FloatValue(value.value() ? 1 : 0);
    if (!(operand instanceof Value.StringValue string) || string.undefinedBacked())
      throw filterReceiver(filter.name(), operand, location);
    var parsed = integer ? parseInt(string.value()) : parseFloat(string.value());
    return Double.isNaN(parsed)
        ? fallback
        : integer ? new Value.IntegerValue(parsed) : new Value.FloatValue(parsed);
  }

  private static double parseInt(String text) {
    var matcher = java.util.regex.Pattern.compile("^[\\s]*([+-]?\\d+)").matcher(text);
    if (!matcher.find()) return Double.NaN;
    try {
      return Double.parseDouble(matcher.group(1));
    } catch (NumberFormatException ignored) {
      return Double.NaN;
    }
  }

  private static double parseFloat(String text) {
    var trimmed = JsOperations.trimEcmaWhitespace(text);
    if (trimmed.startsWith("Infinity") || trimmed.startsWith("+Infinity"))
      return Double.POSITIVE_INFINITY;
    if (trimmed.startsWith("-Infinity")) return Double.NEGATIVE_INFINITY;
    if (trimmed.startsWith("NaN")) return Double.NaN;
    var matcher =
        java.util.regex.Pattern.compile(
                "^([+-]?(?:(?:\\d+\\.?\\d*)|(?:\\.\\d+))(?:[eE][+-]?\\d+)?)")
            .matcher(trimmed);
    if (!matcher.find()) return Double.NaN;
    try {
      return Double.parseDouble(matcher.group(1));
    } catch (NumberFormatException ignored) {
      return Double.NaN;
    }
  }

  private static NamedArguments namedArguments(
      Expression expression,
      Environment env,
      RenderBudget budget,
      SourceLocation location,
      String kind) {
    // The bare-identifier fast path targets a parenthesis-less filter like `| join`, distinct
    // from the CallExpression argument-evaluation loop that evaluateArguments below replaces.
    if (expression instanceof Expression.Identifier id)
      return new NamedArguments(id.value(), List.of(), Map.of());
    if (!(expression instanceof Expression.CallExpression call)
        || !(call.callee() instanceof Expression.Identifier id))
      throw filterType("Unknown " + kind + ": " + expression.getClass().getSimpleName(), location);
    var evaluated = evaluateArguments(call.args(), env, budget);
    return new NamedArguments(id.value(), evaluated.positional(), evaluated.keywords());
  }

  private record EvaluatedArguments(List<Value> positional, Map<String, Value> keywords) {}

  /**
   * Evaluates a call or filter argument list left-to-right, matching upstream's evaluateArguments:
   * keyword arguments accumulate separately, array/tuple spreads expand one level into positional
   * values, and an ordinary positional value after a keyword is rejected — but a spread after a
   * keyword is not, since upstream's spread branch bypasses that check.
   */
  private static EvaluatedArguments evaluateArguments(
      List<Expression> args, Environment env, RenderBudget budget) {
    var positional = new ArrayList<Value>();
    // LinkedHashMap preserves source insertion order so error reporting stays deterministic; the
    // returned map wraps this local unmodifiable rather than copying it via an order-agnostic Map
    // factory — see this plan's Step 2 source guard in
    // docs/superpowers/plans/2026-08-23-wp5-slice2-spread-call-arguments.md. keywords never
    // escapes this method otherwise, so wrapping it directly is safe.
    var keywords = new LinkedHashMap<String, Value>();
    boolean sawKeyword = false;
    for (var argument : args) {
      if (argument instanceof Expression.KeywordArgumentExpression keyword) {
        sawKeyword = true;
        keywords.put(keyword.key().value(), evaluateExpression(keyword.value(), env, budget));
      } else if (argument instanceof Expression.SpreadExpression spread) {
        var value = evaluateExpression(spread.argument(), env, budget);
        if (value instanceof Value.ArrayValue array) positional.addAll(array.values());
        else if (value instanceof Value.TupleValue tuple) positional.addAll(tuple.values());
        else
          throw new TemplateRenderException(
              "Cannot unpack non-iterable type: " + type(value),
              ErrorCategory.TYPE,
              spread.location());
      } else {
        if (sawKeyword)
          throw new TemplateRenderException(
              "Positional arguments cannot follow keyword arguments",
              ErrorCategory.SYNTAX,
              argument.location());
        positional.add(evaluateExpression(argument, env, budget));
      }
    }
    return new EvaluatedArguments(
        List.copyOf(positional),
        keywords.isEmpty() ? Map.of() : Collections.unmodifiableMap(keywords));
  }

  private static void requireNoArguments(NamedArguments arguments, SourceLocation location) {
    if (!arguments.positional().isEmpty() || !arguments.keywords().isEmpty())
      throw new TemplateRenderException(
          "`" + arguments.name() + "` filter accepts no arguments", ErrorCategory.ARITY, location);
  }

  private static void requireNoUnknownKeywords(
      NamedArguments arguments, SourceLocation location, String allowed) {
    for (var key : arguments.keywords().keySet())
      if (!key.equals(allowed))
        throw new TemplateRenderException(
            "Unknown `" + arguments.name() + "` filter argument: " + key,
            ErrorCategory.VALUE,
            location);
  }

  private static TemplateRenderException filterReceiver(
      String name, Value value, SourceLocation location) {
    return filterType("Cannot apply filter `" + name + "` to type: " + type(value), location);
  }

  private static TemplateRenderException filterType(String message, SourceLocation location) {
    return new TemplateRenderException(message, ErrorCategory.TYPE, location);
  }

  private static boolean undefinedLike(Value value) {
    return value instanceof Value.UndefinedValue
        || value instanceof Value.StringValue string && string.undefinedBacked();
  }

  private static TemplateRenderException operatorUndefined(
      String operator, SourceLocation location) {
    return new TemplateRenderException(
        "Cannot perform operation " + operator + " on undefined values",
        ErrorCategory.UNDEFINED_OR_ACCESS,
        location);
  }

  private static TemplateRenderException operatorNull(SourceLocation location) {
    return new TemplateRenderException(
        "Cannot perform operation on null values", ErrorCategory.UNDEFINED_OR_ACCESS, location);
  }

  private static TemplateRenderException operatorUnsupportedTypes(
      String operator, Value left, Value right, SourceLocation location) {
    return new TemplateRenderException(
        "Unknown operator \"" + operator + "\" between " + type(left) + " and " + type(right),
        ErrorCategory.TYPE,
        location);
  }

  private static TemplateRenderException operatorUnsupportedUnary(
      String operator, Value operand, SourceLocation location) {
    return new TemplateRenderException(
        "Unknown unary operator \"" + operator + "\" for " + type(operand),
        ErrorCategory.TYPE,
        location);
  }

  private static List<Value> values(List<Expression> items, Environment e, RenderBudget b) {
    var r = new ArrayList<Value>();
    for (var x : items) r.add(evaluateExpression(x, e, b));
    return r;
  }

  private static Value object(Expression.ObjectLiteral x, Environment e, RenderBudget b) {
    var r = new LinkedHashMap<Object, Value>();
    for (var item : x.value()) {
      var k = evaluateExpression(item.key(), e, b);
      if (!(k instanceof Value.StringValue key))
        throw new TemplateRenderException(
            "Object keys must be strings: got " + type(k),
            ErrorCategory.TYPE,
            item.key().location());
      r.put(key.undefinedBacked() ? key : key.value(), evaluateExpression(item.value(), e, b));
    }
    return new Value.ObjectValue(r);
  }

  private static ExecResult evaluateIf(Statement.If n, Environment e, RenderBudget b) {
    return evaluateBlock(
        truthy(evaluateExpression(n.test(), e, b)) ? n.body() : n.alternate(), e, b);
  }

  static boolean truthy(Value v) {
    return switch (v) {
      case Value.NullValue ignored -> false;
      case Value.UndefinedValue ignored -> false;
      case Value.BooleanValue x -> x.value();
      case Value.IntegerValue x -> x.value() != 0 && !Double.isNaN(x.value());
      case Value.FloatValue x -> x.value() != 0 && !Double.isNaN(x.value());
      case Value.StringValue x -> !x.undefinedBacked() && !x.value().isEmpty();
      case Value.ArrayValue x -> !x.values().isEmpty();
      case Value.TupleValue x -> !x.values().isEmpty();
      case Value.ObjectValue x -> !x.values().isEmpty();
      case Value.KeywordArgumentsValue x -> !x.values().isEmpty();
      case Value.CallableValue ignored -> true;
    };
  }

  private static Value member(Expression.MemberExpression n, Environment e, RenderBudget b) {
    var target = evaluateExpression(n.object(), e, b);
    if (n.computed() && n.property() instanceof Expression.SliceExpression slice)
      return slice(target, slice, e, b, n.location());
    var p =
        !n.computed() && n.property() instanceof Expression.Identifier id
            ? new Value.StringValue(id.value())
            : evaluateExpression(n.property(), e, b);
    if (target instanceof Value.ObjectValue x) {
      if (!(p instanceof Value.StringValue s))
        throw access("Cannot access property with non-string: got " + type(p), n.location());
      var key = objectKey(s);
      if (x.values().containsKey(key)) return x.values().get(key);
      if (!s.undefinedBacked() && "items".equals(s.value())) return objectItemsBuiltin(x);
      if (!s.undefinedBacked()
          && (s.value().equals("get")
              || s.value().equals("keys")
              || s.value().equals("values")
              || s.value().equals("dictsort"))) return objectBuiltin(x, s.value());
      return Value.UndefinedValue.INSTANCE;
    }
    if (target instanceof Value.KeywordArgumentsValue x) {
      if (!(p instanceof Value.StringValue s))
        throw access("Cannot access property with non-string: got " + type(p), n.location());
      if (s.undefinedBacked()) return Value.UndefinedValue.INSTANCE;
      return x.values().getOrDefault(s.value(), Value.UndefinedValue.INSTANCE);
    }
    if (target instanceof Value.ArrayValue x) {
      if (p instanceof Value.StringValue property
          && !property.undefinedBacked()
          && property.value().equals("length")) return new Value.IntegerValue(x.values().size());
      return memberIndex(x.values(), p, n.location());
    }
    if (target instanceof Value.TupleValue x) {
      if (p instanceof Value.StringValue property
          && !property.undefinedBacked()
          && property.value().equals("length")) return new Value.IntegerValue(x.values().size());
      return memberIndex(x.values(), p, n.location());
    }
    if (target instanceof Value.StringValue x) {
      if (x.undefinedBacked()) return Value.UndefinedValue.INSTANCE;
      if (p instanceof Value.StringValue property) {
        if (!property.undefinedBacked()
            && (property.value().equals("startswith") || property.value().equals("endswith")))
          return stringBoundaryBuiltin(
              x.value(),
              property.value(),
              property.value().equals("startswith") ? String::startsWith : String::endsWith);
        if (!property.undefinedBacked() && property.value().equals("length"))
          return new Value.IntegerValue(x.value().length());
        if (List.of(
                "upper",
                "lower",
                "strip",
                "title",
                "capitalize",
                "rstrip",
                "lstrip",
                "split",
                "replace")
            .contains(property.value())) return stringBuiltin(x.value(), property.value());
        return Value.UndefinedValue.INSTANCE;
      }
      if (!(p instanceof Value.IntegerValue))
        throw access(
            "Cannot access property with non-string/non-number: got " + type(p), n.location());
      var v = index(x.value().length(), p);
      return v < 0
          ? Value.StringValue.undefined()
          : new Value.StringValue(String.valueOf(x.value().charAt(v)));
    }
    if (!(p instanceof Value.StringValue))
      throw access("Cannot access property with non-string: got " + type(p), n.location());
    return Value.UndefinedValue.INSTANCE;
  }

  private static Value stringBoundaryBuiltin(
      String receiver, String name, BiPredicate<String, String> matches) {
    return new Value.CallableValue(
        (arguments, hasKeywords, location, environment) -> {
          if (arguments.isEmpty())
            throw new TemplateRenderException(
                name + "() requires at least one argument", ErrorCategory.ARITY, location);
          var pattern = arguments.getFirst();
          if (pattern instanceof Value.StringValue string && !string.undefinedBacked())
            return new Value.BooleanValue(matches.test(receiver, string.value()));
          if (pattern instanceof Value.ArrayValue array)
            return stringBoundaryTuple(receiver, name, matches, array.values(), location);
          if (pattern instanceof Value.TupleValue tuple)
            return stringBoundaryTuple(receiver, name, matches, tuple.values(), location);
          throw new TemplateRenderException(
              name + "() argument must be a string or tuple of strings",
              ErrorCategory.TYPE,
              location);
        });
  }

  private static Value stringBuiltin(String receiver, String name) {
    return new Value.CallableValue(
        (arguments, hasKeywords, location, environment) -> {
          var positional = positional(arguments);
          return switch (name) {
            case "upper" -> new Value.StringValue(receiver.toUpperCase(Locale.ROOT));
            case "lower" -> new Value.StringValue(receiver.toLowerCase(Locale.ROOT));
            case "strip" -> new Value.StringValue(JsOperations.trimEcmaWhitespace(receiver));
            case "rstrip" -> new Value.StringValue(JsOperations.trimEndEcmaWhitespace(receiver));
            case "lstrip" -> new Value.StringValue(JsOperations.trimStartEcmaWhitespace(receiver));
            case "title" -> new Value.StringValue(titleCase(receiver));
            case "capitalize" -> new Value.StringValue(capitalize(receiver));
            case "split" -> stringSplit(receiver, positional, location);
            case "replace" -> stringReplace(receiver, positional, arguments, location);
            default -> throw new AssertionError(name);
          };
        });
  }

  private static List<Value> positional(List<Value> arguments) {
    if (arguments.isEmpty() || !(arguments.getLast() instanceof Value.KeywordArgumentsValue))
      return arguments;
    return arguments.subList(0, arguments.size() - 1);
  }

  private static Value stringSplit(
      String receiver, List<Value> arguments, SourceLocation location) {
    Value separator = arguments.isEmpty() ? Value.NullValue.INSTANCE : arguments.getFirst();
    if (!(separator instanceof Value.StringValue) && !(separator instanceof Value.NullValue))
      throw filterType("sep argument must be a string or null", location);
    int max = -1;
    if (arguments.size() > 1) {
      if (!(arguments.get(1) instanceof Value.IntegerValue number))
        throw filterType("maxsplit argument must be a number", location);
      max = (int) number.value();
    }
    String[] parts;
    if (separator instanceof Value.NullValue) {
      String text = JsOperations.trimStartEcmaWhitespace(receiver);
      var result = new ArrayList<String>();
      int index = 0;
      while (index < text.length()) {
        while (index < text.length() && JsOperations.isEcmaWhitespace(text.charAt(index))) index++;
        if (index == text.length()) break;
        int start = index;
        while (index < text.length() && !JsOperations.isEcmaWhitespace(text.charAt(index))) index++;
        if (max >= 0 && result.size() >= max) {
          // Upstream appends the current match plus the otherwise-unsplit suffix.
          result.add(text.substring(start));
          break;
        }
        result.add(text.substring(start, index));
      }
      parts = result.toArray(String[]::new);
    } else {
      String text = ((Value.StringValue) separator).value();
      if (text.isEmpty()) throw filterType("empty separator", location);
      var split =
          new ArrayList<>(
              java.util.Arrays.asList(
                  receiver.split(java.util.regex.Pattern.quote(text), max == -1 ? -1 : max + 1)));
      if (max < -1) {
        // Upstream splits without a limit, then rejoins the suffix selected by Array.splice(). A
        // negative splice index is relative to the end, rather than another spelling of unlimited.
        int joinStart = Math.max(0, split.size() + max);
        String suffix = String.join(text, split.subList(joinStart, split.size()));
        split.subList(joinStart, split.size()).clear();
        split.add(suffix);
      }
      parts = split.toArray(String[]::new);
    }
    return new Value.ArrayValue(
        java.util.Arrays.stream(parts).map(Value.StringValue::new).map(x -> (Value) x).toList());
  }

  private static Value stringReplace(
      String receiver, List<Value> positional, List<Value> arguments, SourceLocation location) {
    if (positional.size() < 2)
      throw filterType("replace() requires at least two arguments", location);
    if (!(positional.get(0) instanceof Value.StringValue oldValue)
        || !(positional.get(1) instanceof Value.StringValue newValue))
      throw filterType("replace() arguments must be strings", location);
    Value count = positional.size() > 2 ? positional.get(2) : keyword(arguments, "count");
    if (count != null
        && !(count instanceof Value.IntegerValue)
        && !(count instanceof Value.NullValue))
      throw filterType("replace() count argument must be a number or null", location);
    return new Value.StringValue(
        replace(
            receiver,
            oldValue.value(),
            newValue.value(),
            count instanceof Value.IntegerValue n ? (int) n.value() : -1));
  }

  private static String replace(String value, String oldValue, String newValue, int count) {
    if (count == 0) return value;
    var result = new StringBuilder();
    int position = 0;
    int replacements = 0;
    while (count < 0 || replacements < count) {
      int index = value.indexOf(oldValue, position);
      if (index < 0) break;
      result.append(value, position, index).append(newValue);
      position = index + oldValue.length();
      replacements++;
      if (oldValue.isEmpty()) {
        if (position >= value.length()) break;
        int next = value.offsetByCodePoints(position, 1);
        result.append(value, position, next);
        position = next;
      }
    }
    return result.append(value.substring(position)).toString();
  }

  private static Value keyword(List<Value> arguments, String key) {
    return !arguments.isEmpty()
            && arguments.getLast() instanceof Value.KeywordArgumentsValue keywords
        ? keywords.values().get(key)
        : null;
  }

  private static Value objectBuiltin(Value.ObjectValue object, String name) {
    return new Value.CallableValue(
        (arguments, hasKeywords, location, environment) -> {
          var positional = positional(arguments);
          return switch (name) {
            case "get" -> {
              if (positional.isEmpty() || !(positional.getFirst() instanceof Value.StringValue key))
                throw filterType(
                    "Object key must be a string: got "
                        + (positional.isEmpty() ? "UndefinedValue" : type(positional.getFirst())),
                    location);
              yield object
                  .values()
                  .getOrDefault(
                      key.value(),
                      positional.size() > 1 ? positional.get(1) : Value.NullValue.INSTANCE);
            }
            case "keys" ->
                new Value.ArrayValue(
                    object.values().keySet().stream()
                        .map(key -> (Value) objectKeyValue(key))
                        .toList());
            case "values" -> new Value.ArrayValue(new ArrayList<>(object.values().values()));
            case "dictsort" -> dictSort(object, positional, arguments, location);
            default -> throw new AssertionError(name);
          };
        });
  }

  private static Value dictSort(
      Value.ObjectValue object,
      List<Value> positional,
      List<Value> arguments,
      SourceLocation location) {
    Value caseValue =
        positional.isEmpty() ? keyword(arguments, "case_sensitive") : positional.getFirst();
    Value byValue = positional.size() < 2 ? keyword(arguments, "by") : positional.get(1);
    Value reverseValue = positional.size() < 3 ? keyword(arguments, "reverse") : positional.get(2);
    boolean caseSensitive =
        caseValue == null ? false : booleanValue(caseValue, "case_sensitive", location);
    String by = byValue == null ? "key" : stringValue(byValue, "by", location);
    if (!by.equals("key") && !by.equals("value"))
      throw filterType("by must be either 'key' or 'value'", location);
    boolean reverse = reverseValue != null && booleanValue(reverseValue, "reverse", location);
    var entries = new ArrayList<Value>();
    for (var entry : object.values().entrySet()) {
      entries.add(new Value.ArrayValue(List.of(objectKeyValue(entry.getKey()), entry.getValue())));
    }
    int index = by.equals("key") ? 0 : 1;
    entries.sort(
        (a, b) -> {
          int comparison =
              compareValues(
                  ((Value.ArrayValue) a).values().get(index),
                  ((Value.ArrayValue) b).values().get(index),
                  caseSensitive,
                  location);
          return reverse ? -comparison : comparison;
        });
    return new Value.ArrayValue(entries);
  }

  private static boolean booleanValue(Value value, String name, SourceLocation location) {
    if (value instanceof Value.BooleanValue booleanValue) return booleanValue.value();
    throw filterType(name + " must be a boolean", location);
  }

  private static String stringValue(Value value, String name, SourceLocation location) {
    if (value instanceof Value.StringValue string) return string.value();
    throw filterType(name + " must be a string", location);
  }

  private static int compareValues(
      Value left, Value right, boolean caseSensitive, SourceLocation location) {
    if (numericLike(left) && numericLike(right))
      return Double.compare(JsOperations.toNumber(left), JsOperations.toNumber(right));
    if (left instanceof Value.StringValue a && right instanceof Value.StringValue b) {
      String aText = caseSensitive ? a.value() : a.value().toLowerCase(Locale.ROOT);
      String bText = caseSensitive ? b.value() : b.value().toLowerCase(Locale.ROOT);
      return aText.compareTo(bText);
    }
    if (left instanceof Value.BooleanValue a && right instanceof Value.BooleanValue b)
      return Boolean.compare(a.value(), b.value());
    if (left instanceof Value.NullValue && right instanceof Value.NullValue) return 0;
    if (left instanceof Value.UndefinedValue && right instanceof Value.UndefinedValue) return 0;
    throw filterType("Cannot compare " + type(left) + " with " + type(right), location);
  }

  private static boolean numericLike(Value value) {
    return JsOperations.numeric(value) || value instanceof Value.BooleanValue;
  }

  private static Value stringBoundaryTuple(
      String receiver,
      String name,
      BiPredicate<String, String> matches,
      List<Value> patterns,
      SourceLocation location) {
    for (var pattern : patterns) {
      if (!(pattern instanceof Value.StringValue string) || string.undefinedBacked())
        throw new TemplateRenderException(
            name + "() tuple elements must be strings", ErrorCategory.TYPE, location);
      if (matches.test(receiver, string.value())) return new Value.BooleanValue(true);
    }
    return new Value.BooleanValue(false);
  }

  private static Value objectItemsBuiltin(Value.ObjectValue object) {
    if (object.itemsBuiltin() != null) return object.itemsBuiltin();
    var builtin =
        new Value.CallableValue(
            (arguments, hasKeywords, location, environment) -> {
              return itemsOf(object);
            });
    object.setItemsBuiltin(builtin);
    return builtin;
  }

  private static Value.ArrayValue itemsOf(Value.ObjectValue object) {
    var pairs = new ArrayList<Value>();
    for (var entry : object.values().entrySet()) {
      var key = objectKeyValue(entry.getKey());
      pairs.add(new Value.ArrayValue(List.of(key, entry.getValue())));
    }
    return new Value.ArrayValue(pairs);
  }

  private static Value.StringValue objectKeyValue(Object key) {
    return key instanceof Value.StringValue string ? string : new Value.StringValue((String) key);
  }

  private static Value slice(
      Value target,
      Expression.SliceExpression expression,
      Environment environment,
      RenderBudget budget,
      SourceLocation memberLocation) {
    if (!(arrayLike(target) || target instanceof Value.StringValue))
      throw access("Slice object must be an array or string", memberLocation);
    var start = sliceComponent(expression.start(), environment, budget);
    var stop = sliceComponent(expression.stop(), environment, budget);
    var step = sliceComponent(expression.step(), environment, budget);
    Double startValue = sliceBound(start, expression.start(), "start");
    Double stopValue = sliceBound(stop, expression.stop(), "stop");
    Double stepValue = sliceBound(step, expression.step(), "step");
    if (target instanceof Value.StringValue string && string.undefinedBacked())
      return Value.UndefinedValue.INSTANCE;
    if (arrayLike(target))
      return new Value.ArrayValue(
          JsSlice.slice(arrayValues(target), startValue, stopValue, stepValue));
    var string = ((Value.StringValue) target).value();
    var codePoints = string.codePoints().boxed().toList();
    var result = new StringBuilder();
    for (var codePoint : JsSlice.slice(codePoints, startValue, stopValue, stepValue))
      result.appendCodePoint(codePoint);
    return new Value.StringValue(result.toString());
  }

  private static Value sliceComponent(
      Expression expression, Environment environment, RenderBudget budget) {
    return expression == null
        ? Value.UndefinedValue.INSTANCE
        : evaluateExpression(expression, environment, budget);
  }

  private static Double sliceBound(Value value, Expression expression, String name) {
    if (value instanceof Value.UndefinedValue) return null;
    if (value instanceof Value.IntegerValue integer) return integer.value();
    throw access("Slice " + name + " must be numeric or undefined", expression.location());
  }

  private static TemplateRenderException access(String message, SourceLocation location) {
    return new TemplateRenderException(message, ErrorCategory.TYPE, location);
  }

  private static Object objectKey(Value.StringValue value) {
    return value.undefinedBacked() ? value : value.value();
  }

  private static Value memberIndex(List<Value> values, Value property, SourceLocation location) {
    if (property instanceof Value.StringValue) return Value.UndefinedValue.INSTANCE;
    if (!(property instanceof Value.IntegerValue))
      throw access(
          "Cannot access property with non-string/non-number: got " + type(property), location);
    return indexed(values, property);
  }

  private static Value indexed(List<Value> values, Value p) {
    int i = index(values.size(), p);
    return i < 0 ? Value.UndefinedValue.INSTANCE : values.get(i);
  }

  private static int index(int size, Value p) {
    if (!(p instanceof Value.IntegerValue x)) return -1;
    int i = (int) x.value();
    if (i < 0) i += size;
    return i < 0 || i >= size ? -1 : i;
  }

  private static Value call(Expression.CallExpression n, Environment e, RenderBudget b) {
    var evaluated = evaluateArguments(n.args(), e, b);
    var arguments = new ArrayList<>(evaluated.positional());
    if (!evaluated.keywords().isEmpty())
      arguments.add(new Value.KeywordArgumentsValue(evaluated.keywords()));
    var callee = evaluateExpression(n.callee(), e, b);
    if (!(callee instanceof Value.CallableValue f))
      throw new TemplateRenderException(
          "Cannot call something that is not a function: got " + type(callee),
          ErrorCategory.TYPE,
          n.location());
    return f.callable().invoke(arguments, !evaluated.keywords().isEmpty(), n.location(), e);
  }

  private static String type(Value v) {
    return v instanceof Value.CallableValue ? "FunctionValue" : v.getClass().getSimpleName();
  }

  private static ExecResult evaluateMacro(Statement.Macro n, Environment e, RenderBudget b) {
    e.setVariable(
        n.name().value(),
        new Value.CallableValue(
            (arguments, hasKeywords, l, scope) -> {
              // scope is the call-site environment (Step 2), not the macro's own defining
              // environment `e`
              // captured in this closure — this is what makes upstream's late-binding default
              // arguments
              // and call-block scope visibility work.
              var macroScope = new Environment(scope);
              // enterMacro must guard the binding loop below, not just the body: a default
              // argument expression can itself call this macro (e.g. `{% macro m(a=m()) %}`),
              // and evaluateExpression(kwarg.value(), ...) recurses back into this same lambda
              // before evaluateBlock(n.body(), ...) is ever reached. Entering here — before
              // binding — closes that gap; see
              // docs/superpowers/plans/2026-08-24-wp5-slice3-macros-call-and-filter-blocks.md.
              //
              // enterMacro is called BEFORE the try, not inside it: enterMacro is
              // check-before-increment, so on the limit-exceeded path it never touches
              // macroDepth — there is nothing for the finally below to undo. Calling it inside
              // the try would make that finally run exitMacro() on a call that never
              // incremented, decrementing macroDepth without a matching increment.
              b.enterMacro(l);
              try {
                var positional = new ArrayList<>(arguments);
                Value.KeywordArgumentsValue kwargs = null;
                if (!positional.isEmpty()
                    && positional.get(positional.size() - 1)
                        instanceof Value.KeywordArgumentsValue k) {
                  kwargs = k;
                  positional.remove(positional.size() - 1);
                }
                for (int i = 0; i < n.args().size(); i++) {
                  var nodeArg = n.args().get(i);
                  Value passed = i < positional.size() ? positional.get(i) : null;
                  if (nodeArg instanceof Expression.Identifier id) {
                    if (passed == null)
                      throw new TemplateRenderException(
                          "Missing positional argument: " + id.value(), ErrorCategory.ARITY, l);
                    macroScope.setVariable(id.value(), passed);
                  } else if (nodeArg instanceof Expression.KeywordArgumentExpression kwarg) {
                    Value fromKwargs =
                        kwargs == null ? null : kwargs.values().get(kwarg.key().value());
                    Value value =
                        passed != null
                            ? passed
                            : fromKwargs != null
                                ? fromKwargs
                                : evaluateExpression(kwarg.value(), macroScope, b);
                    macroScope.setVariable(kwarg.key().value(), value);
                  } else {
                    throw new TemplateRenderException(
                        "Unknown argument type: " + nodeArg.getClass().getSimpleName(),
                        ErrorCategory.SYNTAX,
                        nodeArg.location());
                  }
                }
                var result = evaluateBlock(n.body(), macroScope, b);
                if (!(result instanceof ExecResult.Normal normal))
                  // Known gap (see
                  // docs/superpowers/plans/2026-08-24-wp5-slice3-macros-call-and-filter-blocks.md,
                  // "Known gaps this slice leaves open"): upstream bleeds a bare break/continue
                  // through
                  // this call boundary into the caller's enclosing loop; we reject it here instead.
                  throw new TemplateRenderException(
                      "break or continue outside a for loop", ErrorCategory.SYNTAX, l);
                return new Value.StringValue(normal.output());
              } finally {
                b.exitMacro();
              }
            }));
    return new ExecResult.Normal("");
  }

  private static ExecResult evaluateCallStatement(
      Statement.CallStatement n, Environment e, RenderBudget b) {
    var caller =
        new Value.CallableValue(
            (callerArgs, hasKeywords, l, callerScope) -> {
              // callerScope is wherever caller() gets called from inside the macro body — this is
              // what lets the call-block body see macro-local state set before caller() runs.
              var callBlockEnv = new Environment(callerScope);
              if (n.callerArgs() != null) {
                for (int i = 0; i < n.callerArgs().size(); i++) {
                  var param = n.callerArgs().get(i);
                  if (!(param instanceof Expression.Identifier id))
                    throw new TemplateRenderException(
                        "Caller parameter must be an identifier, got "
                            + param.getClass().getSimpleName(),
                        ErrorCategory.SYNTAX,
                        param.location());
                  Value value =
                      i < callerArgs.size() ? callerArgs.get(i) : Value.UndefinedValue.INSTANCE;
                  callBlockEnv.setVariable(id.value(), value);
                }
              }
              // enterMacro is called BEFORE the try, not inside it — see the matching comment in
              // evaluateMacro above: it is check-before-increment, so a limit-exceeded throw
              // never touches macroDepth, and calling it inside the try would make the finally
              // below decrement a call that never incremented.
              b.enterMacro(l);
              try {
                var result = evaluateBlock(n.body(), callBlockEnv, b);
                if (!(result instanceof ExecResult.Normal normal))
                  // Known gap (see
                  // docs/superpowers/plans/2026-08-24-wp5-slice3-macros-call-and-filter-blocks.md,
                  // "Known gaps this slice leaves open"): upstream bleeds a bare break/continue
                  // through this call boundary into the caller's enclosing loop; we reject it here
                  // instead.
                  throw new TemplateRenderException(
                      "break or continue outside a for loop", ErrorCategory.SYNTAX, l);
                return new Value.StringValue(normal.output());
              } finally {
                b.exitMacro();
              }
            });

    var evaluated = evaluateArguments(n.call().args(), e, b);
    var arguments = new ArrayList<>(evaluated.positional());
    // Unlike call(), the keyword-arguments bag is pushed unconditionally, even when empty,
    // matching upstream's own evaluateCallStatement/evaluateCallExpression asymmetry (needed for
    // range()/namespace()/macro callees to match the oracle). This means every non-host callee
    // invoked via {% call %} sees a trailing empty-map argument; HostFunctions.invoke strips it
    // before it reaches user-registered host functions, since they have no such upstream contract.
    arguments.add(new Value.KeywordArgumentsValue(evaluated.keywords()));
    var callee = evaluateExpression(n.call().callee(), e, b);
    if (!(callee instanceof Value.CallableValue f))
      throw new TemplateRenderException(
          "Cannot call something that is not a function: got " + type(callee),
          ErrorCategory.TYPE,
          n.call().location());
    var newEnv = new Environment(e);
    newEnv.setVariable("caller", caller);
    var result =
        f.callable()
            .invoke(arguments, !evaluated.keywords().isEmpty(), n.call().location(), newEnv);
    var text =
        result instanceof Value.NullValue || result instanceof Value.UndefinedValue
            ? ""
            : renderText(result, n.location());
    // The call-block body's own text was already charged once inside evaluateBlock above (each
    // inner Expression statement charges as it renders); this charges the callee's returned text
    // again. maxOutputLength is therefore a bound on cumulative rendered characters, not on final
    // output size, for any construct that re-renders already-charged text — evaluateSet's
    // {% set x %}...{% endset %} block-capture already has this same property (charged once
    // inside the block, again wherever `x` is later interpolated). Not a regression introduced
    // here; consistent with pre-existing behavior.
    b.chargeOutput(text.length(), n.location());
    return new ExecResult.Normal(text);
  }

  private static ExecResult evaluateFilterStatement(
      Statement.FilterStatement n, Environment e, RenderBudget b) {
    var rendered = evaluateBlock(n.body(), e, b);
    // Propagating a non-Normal result outward (rather than erroring, unlike the macro/call-block
    // paths above) is deliberate: FilterStatement never crosses the Value.CallableValue.Callable
    // boundary, so this composes correctly exactly as evaluateSet's block-capture already does —
    // confirmed against the oracle that a bare break here bleeds into the caller's enclosing loop.
    if (!(rendered instanceof ExecResult.Normal normal)) return rendered;
    var filtered =
        applyFilter(
            new Value.StringValue(normal.output()), n.filter(), e, b, n.filter().location());
    var text =
        filtered instanceof Value.NullValue || filtered instanceof Value.UndefinedValue
            ? ""
            : renderText(filtered, n.location());
    // Re-charges the body's already-charged text after filtering; see the matching comment in
    // evaluateCallStatement above.
    b.chargeOutput(text.length(), n.location());
    return new ExecResult.Normal(text);
  }

  private static ExecResult evaluateSet(Statement.SetStatement n, Environment e, RenderBudget b) {
    Value rhs;
    if (n.value() != null) rhs = evaluateExpression(n.value(), e, b);
    else {
      var r = evaluateBlock(n.body(), e, b);
      if (!(r instanceof ExecResult.Normal normal)) return r;
      rhs = new Value.StringValue(normal.output());
    }
    if (n.assignee() instanceof Expression.Identifier x) e.setVariable(x.value(), rhs);
    else if (n.assignee() instanceof Expression.TupleLiteral x) {
      if (!(rhs instanceof Value.ArrayValue || rhs instanceof Value.TupleValue))
        throw new TemplateRenderException(
            "Cannot unpack non-iterable type in set: " + type(rhs),
            ErrorCategory.TYPE,
            n.location());
      List<Value> vals =
          rhs instanceof Value.ArrayValue a ? a.values() : ((Value.TupleValue) rhs).values();
      if (vals.size() != x.value().size())
        throw new TemplateRenderException(
            "Too " + (vals.size() < x.value().size() ? "few" : "many") + " items to unpack in set",
            ErrorCategory.VALUE,
            n.location());
      for (int i = 0; i < vals.size(); i++) {
        if (!(x.value().get(i) instanceof Expression.Identifier id))
          throw new TemplateRenderException(
              "Cannot unpack to non-identifier in set: "
                  + x.value().get(i).getClass().getSimpleName(),
              ErrorCategory.TYPE,
              n.location());
        e.setVariable(id.value(), vals.get(i));
      }
    } else if (n.assignee() instanceof Expression.MemberExpression x) {
      var target = evaluateExpression(x.object(), e, b);
      if (!(target instanceof Value.ObjectValue || target instanceof Value.KeywordArgumentsValue))
        throw new TemplateRenderException(
            "Cannot assign to member of non-object", ErrorCategory.TYPE, n.location());
      if (!(x.property() instanceof Expression.Identifier key))
        throw new TemplateRenderException(
            "Cannot assign to member with non-identifier property",
            ErrorCategory.TYPE,
            n.location());
      if (target instanceof Value.ObjectValue obj) obj.values().put(key.value(), rhs);
      else ((Value.KeywordArgumentsValue) target).values().put(key.value(), rhs);
    } else
      throw new TemplateRenderException(
          "Invalid LHS inside assignment expression: " + astJson(n.assignee()),
          ErrorCategory.SYNTAX,
          n.location());
    return new ExecResult.Normal("");
  }

  private static String astJson(Expression expression) {
    if (expression instanceof Expression.Identifier value)
      return "{\"type\":\"Identifier\",\"value\":" + JsFormat.quote(value.value()) + "}";
    if (expression instanceof Expression.IntegerLiteral value)
      return "{\"type\":\"IntegerLiteral\",\"value\":" + JsFormat.jsonString(value.value()) + "}";
    if (expression instanceof Expression.FloatLiteral value)
      return "{\"type\":\"FloatLiteral\",\"value\":" + JsFormat.jsonString(value.value()) + "}";
    if (expression instanceof Expression.StringLiteral value)
      return "{\"type\":\"StringLiteral\",\"value\":" + JsFormat.quote(value.value()) + "}";
    if (expression instanceof Expression.ArrayLiteral value)
      return "{\"type\":\"ArrayLiteral\",\"value\":" + astJsonList(value.value()) + "}";
    if (expression instanceof Expression.TupleLiteral value)
      return "{\"type\":\"TupleLiteral\",\"value\":" + astJsonList(value.value()) + "}";
    return "{\"type\":" + JsFormat.quote(expression.getClass().getSimpleName()) + "}";
  }

  private static String astJsonList(List<Expression> values) {
    return values.stream()
        .map(Interpreter::astJson)
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }

  private static ExecResult evaluateFor(Statement.For n, Environment e, RenderBudget b) {
    Expression test = n.iterable() instanceof Expression.SelectExpression s ? s.test() : null;
    Expression iterableExpression =
        n.iterable() instanceof Expression.SelectExpression s ? s.lhs() : n.iterable();
    var scope = new Environment(e);
    var iterable = evaluateExpression(iterableExpression, scope, b);
    List<Value> items;
    if (iterable instanceof Value.ArrayValue a) items = a.values();
    else if (iterable instanceof Value.TupleValue a) items = a.values();
    else if (iterable instanceof Value.ObjectValue o) {
      items = new ArrayList<>();
      for (var k : o.values().keySet())
        items.add(
            k instanceof Value.StringValue string ? string : new Value.StringValue((String) k));
    } else if (iterable instanceof Value.KeywordArgumentsValue o) {
      items = new ArrayList<>();
      for (var k : o.values().keySet()) items.add(new Value.StringValue(k));
    } else
      throw new TemplateRenderException(
          "Expected iterable or object type in for loop: got " + type(iterable),
          ErrorCategory.TYPE,
          n.location());
    var filtered = new ArrayList<Value>();
    for (var item : items) {
      b.chargeLoopIteration(n.location());
      var filterScope = new Environment(e);
      bind(n.loopVariable(), item, filterScope, n.location());
      if (test == null || truthy(evaluateExpression(test, filterScope, b))) filtered.add(item);
    }
    items = filtered;
    var out = new StringBuilder();
    boolean none = true;
    for (int i = 0; i < items.size(); i++) {
      var loop = new LinkedHashMap<String, Value>();
      loop.put("index", new Value.IntegerValue(i + 1));
      loop.put("index0", new Value.IntegerValue(i));
      loop.put("revindex", new Value.IntegerValue(items.size() - i));
      loop.put("revindex0", new Value.IntegerValue(items.size() - i - 1));
      loop.put("first", new Value.BooleanValue(i == 0));
      loop.put("last", new Value.BooleanValue(i == items.size() - 1));
      loop.put("length", new Value.IntegerValue(items.size()));
      loop.put("previtem", i > 0 ? items.get(i - 1) : Value.UndefinedValue.INSTANCE);
      loop.put("nextitem", i + 1 < items.size() ? items.get(i + 1) : Value.UndefinedValue.INSTANCE);
      scope.setVariable("loop", new Value.ObjectValue(loop));
      bind(n.loopVariable(), items.get(i), scope, n.location());
      var r = evaluateBlock(n.body(), scope, b);
      if (r instanceof ExecResult.Break) break;
      if (r instanceof ExecResult.Continue) continue;
      none = false;
      out.append(((ExecResult.Normal) r).output());
    }
    return none ? evaluateBlock(n.defaultBlock(), scope, b) : new ExecResult.Normal(out.toString());
  }

  private static void bind(Expression target, Value item, Environment e, SourceLocation l) {
    if (target instanceof Expression.Identifier x) {
      e.setVariable(x.value(), item);
      return;
    }
    if (!(target instanceof Expression.TupleLiteral tuple))
      throw new TemplateRenderException(
          "Invalid loop variable(s): " + target.getClass().getSimpleName(), ErrorCategory.TYPE, l);
    if (!(item instanceof Value.ArrayValue a))
      throw new TemplateRenderException(
          "Cannot unpack non-iterable type: " + type(item), ErrorCategory.TYPE, l);
    if (a.values().size() != tuple.value().size())
      throw new TemplateRenderException(
          "Too " + (a.values().size() < tuple.value().size() ? "few" : "many") + " items to unpack",
          ErrorCategory.VALUE,
          l);
    for (int i = 0; i < a.values().size(); i++) {
      if (!(tuple.value().get(i) instanceof Expression.Identifier id))
        throw new TemplateRenderException(
            "Cannot unpack non-identifier type: " + tuple.value().get(i).getClass().getSimpleName(),
            ErrorCategory.TYPE,
            l);
      e.setVariable(id.value(), a.values().get(i));
    }
  }

  static String renderText(Value v, SourceLocation l) {
    return switch (v) {
      case Value.BooleanValue x -> Boolean.toString(x.value());
      case Value.IntegerValue x -> JsFormat.plainString(x.value());
      case Value.FloatValue x -> renderFloat(x.value());
      case Value.StringValue x -> x.value();
      case Value.ArrayValue ignored -> renderJson(v, l);
      case Value.ObjectValue ignored -> renderJson(v, l);
      case Value.TupleValue ignored -> renderJson(v, l);
      case Value.KeywordArgumentsValue ignored -> renderJson(v, l);
      case Value.CallableValue ignored -> "<function>";
      case Value.NullValue ignored -> throw new AssertionError("unreachable: " + v);
      case Value.UndefinedValue ignored -> throw new AssertionError("unreachable: " + v);
    };
  }

  private static String renderFloat(double v) {
    return JsFormat.floatString(v);
  }

  static String renderJson(Value v, SourceLocation l) {
    return JsFormat.runtimeJson(v, l);
  }

  private static Value range(List<Value> a, boolean k, SourceLocation l, RenderBudget budget) {
    Value current = argument(a, 0);
    Value stop = argument(a, 1);
    Value step = argument(a, 2);
    if (step instanceof Value.UndefinedValue) step = new Value.IntegerValue(1);
    if (stop instanceof Value.UndefinedValue) {
      stop = current;
      current = new Value.IntegerValue(0);
    }
    if (step instanceof Value.IntegerValue integerStep && integerStep.value() == 0
        || step instanceof Value.FloatValue floatStep && floatStep.value() == 0)
      throw new TemplateRenderException("range() step must not be zero", ErrorCategory.VALUE, l);
    boolean ascending = JsOperations.toNumber(step) > 0;
    var r = new ArrayList<Value>();
    while (ascending
        ? JsOperations.toNumber(current) < JsOperations.toNumber(stop)
        : JsOperations.toNumber(current) > JsOperations.toNumber(stop)) {
      budget.chargeRangeElement(l);
      r.add(rangeElement(current));
      current = JsOperations.add(current, step, l);
    }
    return new Value.ArrayValue(r);
  }

  private static Value rangeElement(Value value) {
    if (value instanceof Value.FloatValue number)
      return number.value() == Math.rint(number.value())
          ? new Value.IntegerValue(number.value())
          : value;
    return value;
  }

  private static Value argument(List<Value> arguments, int index) {
    if (index >= arguments.size()) return Value.UndefinedValue.INSTANCE;
    var value = arguments.get(index);
    return value instanceof Value.NullValue
            || value instanceof Value.StringValue string && string.undefinedBacked()
        ? Value.UndefinedValue.INSTANCE
        : value;
  }

  private static Value raise(List<Value> a, boolean k, SourceLocation l) {
    throw new TemplateRenderException(
        exceptionText(argument(a, 0), l), ErrorCategory.EXPLICIT_RAISE, l);
  }

  private static String exceptionText(Value value, SourceLocation location) {
    return switch (value) {
      case Value.NullValue ignored -> "";
      case Value.UndefinedValue ignored -> "";
      case Value.ArrayValue array ->
          array.values().stream()
              .map(Interpreter::nestedExceptionText)
              .reduce((left, right) -> left + "," + right)
              .orElse("");
      case Value.TupleValue tuple ->
          tuple.values().stream()
              .map(Interpreter::nestedExceptionText)
              .reduce((left, right) -> left + "," + right)
              .orElse("");
      case Value.ObjectValue ignored -> "[object Map]";
      case Value.KeywordArgumentsValue ignored -> "[object Map]";
      case Value.CallableValue ignored -> "[object Object]";
      case Value.BooleanValue x -> Boolean.toString(x.value());
      case Value.IntegerValue x -> JsFormat.plainString(x.value());
      case Value.FloatValue x -> JsFormat.plainString(x.value());
      case Value.StringValue x -> x.value();
    };
  }

  private static String nestedExceptionText(Value value) {
    return switch (value) {
      case Value.NullValue ignored -> "undefined";
      case Value.UndefinedValue ignored -> "undefined";
      case Value.ArrayValue array ->
          array.values().stream()
              .map(Interpreter::nestedExceptionText)
              .reduce((left, right) -> left + ", " + right)
              .map(text -> "[" + text + "]")
              .orElse("[]");
      case Value.TupleValue tuple ->
          tuple.values().stream()
              .map(Interpreter::nestedExceptionText)
              .reduce((left, right) -> left + ", " + right)
              .map(text -> "[" + text + "]")
              .orElse("[]");
      case Value.ObjectValue ignored -> "[object Map]";
      case Value.KeywordArgumentsValue ignored -> "[object Map]";
      case Value.CallableValue ignored -> "[object Object]";
      case Value.BooleanValue x -> Boolean.toString(x.value());
      case Value.IntegerValue x -> JsFormat.plainString(x.value());
      case Value.FloatValue x -> JsFormat.plainString(x.value());
      case Value.StringValue x -> x.value();
    };
  }

  private static Value strftime(List<Value> a, boolean k, SourceLocation l, RenderOptions o) {
    // ARITY means "no positional format argument was supplied," which is not the same test as
    // `a.isEmpty()`: two call shapes reach here with a non-empty `a` yet no positional value.
    // `{% call strftime_now() %}...{% endcall %}` has evaluateCallStatement append a
    // KeywordArgumentsValue bag as the last element of `a` even when there are no keywords,
    // landing at index 0 only because this call has no positionals. `strftime_now(fmt='%Y')` has
    // call()'s conditional append push the same shape at index 0 for that same no-positionals
    // reason -- its bag exists at all because its keywords are non-empty. Guard on the shape, not
    // just the count, before calling argument(a, 0) at all.
    //
    // Known false positive: this guard cannot distinguish "no positional value was supplied" from
    // "a positional value was supplied whose runtime type happens to be KeywordArgumentsValue" --
    // e.g. `strftime_now(namespace(a=1))`, where `namespace` returns its keyword bag verbatim
    // (Environment.namespace). Both shapes arrive here as `a=[KeywordArgumentsValue], k=false`;
    // the Callable protocol carries no positional-arity signal that could tell them apart. This
    // guard resolves that ambiguity toward ARITY rather than TYPE. No corpus or oracle record pins
    // either category for this shape, so it is an accepted internal-consistency gap, not a parity
    // regression -- fixing it would require carrying positional count through the Callable
    // signature project-wide, which is out of proportion to this edge case.
    if (a.isEmpty() || a.get(0) instanceof Value.KeywordArgumentsValue)
      throw new TemplateRenderException(
          "strftime_now() expected one string argument", ErrorCategory.ARITY, l);
    var format = argument(a, 0);
    // Upstream's convertToRuntimeValues unwraps every argument shape to its raw .value before
    // calling strftime_now, so an absent/undefined-backed argument and an explicit `none` both
    // reach the same unguarded format.replace(...) call there and only differ in which
    // "Cannot read properties of ... (reading 'replace')" message surfaces -- never a single
    // shared message, and never a distinguishable category. hfjinja's ARITY/TYPE split below is a
    // deliberate category decision upstream cannot express, so it has no matching oracle error
    // record; retain this comment as that record's rationale.
    if (!(format instanceof Value.StringValue f))
      throw new TemplateRenderException(
          "strftime_now() format must be a string", ErrorCategory.TYPE, l);
    if (o.clock().isEmpty() || o.zoneId().isEmpty())
      throw new TemplateRenderException(
          "strftime_now requires clock and zone", ErrorCategory.VALUE, l);
    var z = ZonedDateTime.now(o.clock().get()).withZoneSameInstant(o.zoneId().get());
    return new Value.StringValue(PosixStrftime.format(z, f.value()));
  }
}
