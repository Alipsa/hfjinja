package se.alipsa.hfjinja.internal.runtime;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
      case Expression.SliceExpression x ->
          throw unsupportedExpression("SliceExpression", x.location());
      case Expression.KeywordArgumentExpression x ->
          throw unsupportedExpression("KeywordArgumentExpression", x.location());
      case Expression.SpreadExpression x ->
          throw unsupportedExpression("SpreadExpression", x.location());
    };
  }

  private static Value ternary(
      Expression.Ternary expression, Environment env, RenderBudget budget) {
    return truthy(evaluateExpression(expression.condition(), env, budget))
        ? evaluateExpression(expression.trueExpr(), env, budget)
        : evaluateExpression(expression.falseExpr(), env, budget);
  }

  private static TemplateRenderException unsupportedExpression(String n, SourceLocation l) {
    return new TemplateRenderException(
        n + " is not yet supported", ErrorCategory.UNDEFINED_OR_ACCESS, l);
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
      case "tojson" -> filterToJson(operand, filter, location);
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
      case "string" -> filterToString(operand, filter, location);
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

  private static Value filterToJson(Value operand, NamedArguments filter, SourceLocation location) {
    // Known gap (see docs/superpowers/plans/2026-08-23-wp5-slice2-spread-call-arguments.md,
    // "Known gaps this slice leaves open" — tojson accepts zero arguments): upstream implements
    // four real keywords (indent, ensure_ascii, sort_keys, separators) and ignores positionals.
    requireNoArguments(filter, location);
    return new Value.StringValue(JsFormat.runtimeJson(operand, location, true));
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
      case "boolean" -> value instanceof Value.BooleanValue;
      case "number" -> JsOperations.numeric(value);
      case "string" -> value instanceof Value.StringValue string && !string.undefinedBacked();
      case "iterable" ->
          value instanceof Value.ArrayValue
              || value instanceof Value.StringValue string && !string.undefinedBacked();
      case "sequence" ->
          value instanceof Value.ArrayValue
              || value instanceof Value.TupleValue
              || value instanceof Value.ObjectValue
              || value instanceof Value.StringValue string && !string.undefinedBacked();
      default -> throw filterType("Unknown test: " + name, location);
    };
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
      return Value.UndefinedValue.INSTANCE;
    }
    if (target instanceof Value.KeywordArgumentsValue x) {
      if (!(p instanceof Value.StringValue s))
        throw access("Cannot access property with non-string: got " + type(p), n.location());
      if (s.undefinedBacked()) return Value.UndefinedValue.INSTANCE;
      return x.values().getOrDefault(s.value(), Value.UndefinedValue.INSTANCE);
    }
    if (target instanceof Value.ArrayValue x) return memberIndex(x.values(), p, n.location());
    if (target instanceof Value.TupleValue x) return memberIndex(x.values(), p, n.location());
    if (target instanceof Value.StringValue x) {
      if (x.undefinedBacked()) return Value.UndefinedValue.INSTANCE;
      if (p instanceof Value.StringValue) return Value.UndefinedValue.INSTANCE;
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

  private static Value objectItemsBuiltin(Value.ObjectValue object) {
    return new Value.CallableValue(
        (arguments, hasKeywords, location, environment) -> {
          var pairs = new ArrayList<Value>();
          for (var entry : object.values().entrySet()) {
            var key =
                entry.getKey() instanceof Value.StringValue string
                    ? string
                    : new Value.StringValue((String) entry.getKey());
            pairs.add(new Value.ArrayValue(List.of(key, entry.getValue())));
          }
          return new Value.ArrayValue(pairs);
        });
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
    var format = argument(a, 0);
    if (!(format instanceof Value.StringValue f))
      throw new TemplateRenderException(
          "strftime_now() expected one string argument", ErrorCategory.ARITY, l);
    if (o.clock().isEmpty() || o.zoneId().isEmpty())
      throw new TemplateRenderException(
          "strftime_now requires clock and zone", ErrorCategory.VALUE, l);
    var z = ZonedDateTime.now(o.clock().get()).withZoneSameInstant(o.zoneId().get());
    String[] sm = {
      "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };
    String[] lm = {
      "January",
      "February",
      "March",
      "April",
      "May",
      "June",
      "July",
      "August",
      "September",
      "October",
      "November",
      "December"
    };
    var out = new StringBuilder();
    for (int i = 0; i < f.value().length(); i++) {
      char c = f.value().charAt(i);
      if (c != '%' || i + 1 == f.value().length()) {
        out.append(c);
        continue;
      }
      char directive = f.value().charAt(++i);
      out.append(
          switch (directive) {
            case 'Y' -> Integer.toString(z.getYear());
            case 'm' -> String.format(Locale.ROOT, "%02d", z.getMonthValue());
            case 'd' -> String.format(Locale.ROOT, "%02d", z.getDayOfMonth());
            case 'b' -> sm[z.getMonthValue() - 1];
            case 'B' -> lm[z.getMonthValue() - 1];
            case 'H' -> String.format(Locale.ROOT, "%02d", z.getHour());
            case 'M' -> String.format(Locale.ROOT, "%02d", z.getMinute());
            case '%' -> "%";
            default -> "%" + directive;
          });
    }
    return new Value.StringValue(out.toString());
  }
}
