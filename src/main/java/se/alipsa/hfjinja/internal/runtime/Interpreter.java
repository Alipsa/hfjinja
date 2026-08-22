package se.alipsa.hfjinja.internal.runtime;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.ZonedDateTime;
import java.util.ArrayList;
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
    var result = evaluateBlock(program.body(), env, budget);
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
    env.set("range", new Value.CallableValue((a, k, x) -> range(a, k, x, budget)));
    env.set("raise_exception", new Value.CallableValue(Interpreter::raise));
    env.set("strftime_now", new Value.CallableValue((a, k, x) -> strftime(a, k, x, o)));
    for (var e : o.hostFunctions().entrySet())
      env.set(
          e.getKey(),
          new Value.CallableValue(
              (a, k, x) -> HostFunctions.invoke(e.getKey(), e.getValue(), a, k, x)));
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
      case Statement.Macro m -> unsupported("Macro", m.location());
      case Statement.FilterStatement f -> unsupported("FilterStatement", f.location());
      case Statement.CallStatement c -> unsupported("CallStatement", c.location());
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

  private static ExecResult unsupported(String name, SourceLocation l) {
    throw new TemplateRenderException(
        name + " is not yet supported", ErrorCategory.UNDEFINED_OR_ACCESS, l);
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
      case Expression.BinaryExpression x ->
          throw unsupportedExpression("BinaryExpression", x.location());
      case Expression.UnaryExpression x ->
          throw unsupportedExpression("UnaryExpression", x.location());
      case Expression.FilterExpression x ->
          throw unsupportedExpression("FilterExpression", x.location());
      case Expression.TestExpression x ->
          throw unsupportedExpression("TestExpression", x.location());
      case Expression.Ternary x -> throw unsupportedExpression("Ternary", x.location());
      case Expression.SliceExpression x ->
          throw unsupportedExpression("SliceExpression", x.location());
      case Expression.KeywordArgumentExpression x ->
          throw unsupportedExpression("KeywordArgumentExpression", x.location());
      case Expression.SpreadExpression x ->
          throw unsupportedExpression("SpreadExpression", x.location());
    };
  }

  private static TemplateRenderException unsupportedExpression(String n, SourceLocation l) {
    return new TemplateRenderException(
        n + " is not yet supported", ErrorCategory.UNDEFINED_OR_ACCESS, l);
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
    var p =
        !n.computed() && n.property() instanceof Expression.Identifier id
            ? new Value.StringValue(id.value())
            : evaluateExpression(n.property(), e, b);
    if (target instanceof Value.ObjectValue x) {
      if (!(p instanceof Value.StringValue s))
        throw access("Cannot access property with non-string: got " + type(p), n.location());
      return x.values().getOrDefault(objectKey(s), Value.UndefinedValue.INSTANCE);
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
    var arguments = new ArrayList<Value>();
    var keywords = new LinkedHashMap<String, Value>();
    boolean sawKeyword = false;
    for (var argument : n.args()) {
      if (argument instanceof Expression.KeywordArgumentExpression keyword) {
        sawKeyword = true;
        keywords.put(keyword.key().value(), evaluateExpression(keyword.value(), e, b));
      } else {
        if (sawKeyword)
          throw new TemplateRenderException(
              "Positional arguments cannot follow keyword arguments",
              ErrorCategory.SYNTAX,
              argument.location());
        arguments.add(evaluateExpression(argument, e, b));
      }
    }
    if (!keywords.isEmpty()) arguments.add(new Value.KeywordArgumentsValue(keywords));
    var callee = evaluateExpression(n.callee(), e, b);
    if (!(callee instanceof Value.CallableValue f))
      throw new TemplateRenderException(
          "Cannot call something that is not a function: got " + type(callee),
          ErrorCategory.TYPE,
          n.location());
    return f.callable().invoke(arguments, !keywords.isEmpty(), n.location());
  }

  private static String type(Value v) {
    return v instanceof Value.CallableValue ? "FunctionValue" : v.getClass().getSimpleName();
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
        items.add(k instanceof Value.StringValue string ? string : new Value.StringValue((String) k));
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
    if (v % 1 == 0 && Math.abs(v) < 1e21)
      return new BigDecimal(v).setScale(1, RoundingMode.UNNECESSARY).toPlainString();
    return JsFormat.plainString(v);
  }

  static String renderJson(Value v, SourceLocation l) {
    var rendered = renderJsonValue(v, l, new java.util.IdentityHashMap<>());
    return rendered == null ? "undefined" : rendered;
  }

  private static String renderJsonValue(
      Value v, SourceLocation l, java.util.IdentityHashMap<Value, Boolean> visiting) {
    return switch (v) {
      case Value.NullValue ignored -> "null";
      case Value.UndefinedValue ignored -> "undefined";
      case Value.BooleanValue x -> Boolean.toString(x.value());
      case Value.IntegerValue x -> JsFormat.jsonString(x.value());
      case Value.FloatValue x -> JsFormat.jsonString(x.value());
      case Value.StringValue x -> x.undefinedBacked() ? null : JsFormat.quote(x.value());
      case Value.ArrayValue x -> jsonArray(x.values(), l, visiting, x);
      case Value.ObjectValue x -> jsonObject(x.values(), l, visiting, x);
      case Value.TupleValue ignored ->
          throw new TemplateRenderException(
              "Cannot convert to JSON: TupleValue", ErrorCategory.TYPE, l);
      case Value.KeywordArgumentsValue ignored ->
          throw new TemplateRenderException(
              "Cannot convert to JSON: KeywordArgumentsValue", ErrorCategory.TYPE, l);
      case Value.CallableValue ignored ->
          throw new TemplateRenderException(
              "Cannot convert to JSON: FunctionValue", ErrorCategory.TYPE, l);
    };
  }

  private static void enterJson(
      Value value, java.util.IdentityHashMap<Value, Boolean> visiting, SourceLocation location) {
    if (visiting.put(value, Boolean.TRUE) != null)
      throw new TemplateRenderException(
          "Cannot convert cyclic value to JSON", ErrorCategory.TYPE, location);
  }

  private static String jsonArray(
      List<Value> values,
      SourceLocation l,
      java.util.IdentityHashMap<Value, Boolean> visiting,
      Value container) {
    enterJson(container, visiting, l);
    try {
      var r = new StringBuilder("[");
      for (int i = 0; i < values.size(); i++) {
        if (i > 0) r.append(", ");
        var rendered = renderJsonValue(values.get(i), l, visiting);
        if (rendered != null) r.append(rendered);
      }
      return r.append(']').toString();
    } finally {
      visiting.remove(container);
    }
  }

  private static String jsonObject(
      Map<?, Value> values,
      SourceLocation l,
      java.util.IdentityHashMap<Value, Boolean> visiting,
      Value container) {
    enterJson(container, visiting, l);
    try {
      var r = new StringBuilder("{");
      boolean first = true;
      for (var x : values.entrySet()) {
        if (!first) r.append(", ");
        first = false;
        r.append(jsonObjectKey(x.getKey()))
            .append(": ")
            .append(jsonObjectValue(x.getValue(), l, visiting));
      }
      return r.append('}').toString();
    } finally {
      visiting.remove(container);
    }
  }

  private static String jsonObjectValue(
      Value value, SourceLocation location, java.util.IdentityHashMap<Value, Boolean> visiting) {
    var rendered = renderJsonValue(value, location, visiting);
    return rendered == null ? "undefined" : rendered;
  }

  private static String jsonObjectKey(Object key) {
    return key instanceof Value.StringValue string && string.undefinedBacked()
        ? "undefined"
        : JsFormat.quote((String) key);
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
    boolean ascending = jsNumber(step) > 0;
    var r = new ArrayList<Value>();
    while (ascending ? jsNumber(current) < jsNumber(stop) : jsNumber(current) > jsNumber(stop)) {
      budget.chargeRangeElement(l);
      r.add(rangeElement(current));
      current = jsAdd(current, step);
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

  private static Value jsAdd(Value left, Value right) {
    if (left instanceof Value.StringValue || right instanceof Value.StringValue)
      return new Value.StringValue(jsText(left) + jsText(right));
    double sum = jsNumber(left) + jsNumber(right);
    return Double.isFinite(sum) && sum == Math.rint(sum)
        ? new Value.IntegerValue(sum)
        : new Value.FloatValue(sum);
  }

  private static String jsText(Value value) {
    return switch (value) {
      case Value.StringValue x -> x.value();
      case Value.IntegerValue x -> JsFormat.plainString(x.value());
      case Value.FloatValue x -> JsFormat.plainString(x.value());
      case Value.BooleanValue x -> Boolean.toString(x.value());
      case Value.NullValue ignored -> "null";
      case Value.UndefinedValue ignored -> "undefined";
      default -> "[object Object]";
    };
  }

  private static double jsNumber(Value v) {
    if (v instanceof Value.IntegerValue x) return x.value();
    if (v instanceof Value.FloatValue x) return x.value();
    if (v instanceof Value.NullValue) return 0;
    if (v instanceof Value.BooleanValue x) return x.value() ? 1 : 0;
    if (v instanceof Value.StringValue x) {
      var s = trimEcmaWhitespace(x.value());
      if (s.isEmpty()) return 0;
      if (s.equals("Infinity") || s.equals("+Infinity")) return Double.POSITIVE_INFINITY;
      if (s.equals("-Infinity")) return Double.NEGATIVE_INFINITY;
      if (s.matches("0[xX][0-9a-fA-F]+")) return new BigInteger(s.substring(2), 16).doubleValue();
      if (s.matches("0[oO][0-7]+")) return new BigInteger(s.substring(2), 8).doubleValue();
      if (s.matches("0[bB][01]+")) return new BigInteger(s.substring(2), 2).doubleValue();
      if (!s.matches("[+-]?(?:(?:\\d+\\.?\\d*)|(?:\\.\\d+))(?:[eE][+-]?\\d+)?")) return Double.NaN;
      return Double.parseDouble(s);
    }
    return Double.NaN;
  }

  private static String trimEcmaWhitespace(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && isEcmaWhitespace(value.charAt(start))) start++;
    while (end > start && isEcmaWhitespace(value.charAt(end - 1))) end--;
    return value.substring(start, end);
  }

  private static boolean isEcmaWhitespace(char value) {
    return Character.getType(value) == Character.SPACE_SEPARATOR
        || value == '\u0009'
        || value == '\u000B'
        || value == '\u000C'
        || value == '\n'
        || value == '\r'
        || value == '\u2028'
        || value == '\u2029'
        || value == '\uFEFF';
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
