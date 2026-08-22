package se.alipsa.hfjinja.internal.runtime;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import se.alipsa.hfjinja.internal.JsFormat;
import se.alipsa.hfjinja.internal.Value;

/** Stateless JavaScript-value coercion and operator helpers used by the interpreter. */
@SuppressWarnings("doclint:missing")
final class JsOperations {
  private JsOperations() {}

  static Value add(Value left, Value right) {
    if (left instanceof Value.StringValue || right instanceof Value.StringValue)
      return new Value.StringValue(toText(left) + toText(right));
    double result = toNumber(left) + toNumber(right);
    return integralResult(result) ? new Value.IntegerValue(result) : new Value.FloatValue(result);
  }

  static Value arithmetic(String operator, Value left, Value right) {
    double a = number(left);
    double b = number(right);
    boolean floating = left instanceof Value.FloatValue || right instanceof Value.FloatValue;
    return switch (operator) {
      case "+" -> numeric(a + b, floating);
      case "-" -> numeric(a - b, floating);
      case "*" -> numeric(a * b, floating);
      case "/" -> new Value.FloatValue(a / b);
      case "%" -> numeric(a % b, floating);
      default -> throw new IllegalArgumentException("Not an arithmetic operator: " + operator);
    };
  }

  static boolean compare(String operator, Value left, Value right) {
    double a = number(left);
    double b = number(right);
    return switch (operator) {
      case "<" -> a < b;
      case ">" -> a > b;
      case "<=" -> a <= b;
      case ">=" -> a >= b;
      default -> throw new IllegalArgumentException("Not a comparison operator: " + operator);
    };
  }

  static Value concatenate(Value.ArrayValue left, Value.ArrayValue right) {
    var values = new ArrayList<Value>(left.values());
    values.addAll(right.values());
    return new Value.ArrayValue(values);
  }

  static boolean contains(Value needle, Value.ArrayValue haystack) {
    return haystack.values().stream().anyMatch(value -> strictValueEquals(value, needle));
  }

  static boolean contains(Value.StringValue needle, Value.StringValue haystack) {
    return haystack.value().contains(needle.value());
  }

  static boolean contains(Value.StringValue needle, Value.ObjectValue haystack) {
    return haystack.values().containsKey(needle.undefinedBacked() ? needle : needle.value());
  }

  static String toText(Value value) {
    return switch (value) {
      case Value.StringValue x -> x.value();
      case Value.IntegerValue x -> JsFormat.plainString(x.value());
      case Value.FloatValue x -> JsFormat.plainString(x.value());
      case Value.BooleanValue x -> Boolean.toString(x.value());
      case Value.NullValue ignored -> "null";
      case Value.UndefinedValue ignored -> "undefined";
      case Value.ArrayValue x -> arrayText(x.values());
      case Value.TupleValue x -> arrayText(x.values());
      case Value.ObjectValue ignored -> "[object Object]";
      case Value.KeywordArgumentsValue ignored -> "[object Object]";
      case Value.CallableValue ignored -> "function";
    };
  }

  static double toNumber(Value value) {
    if (value instanceof Value.IntegerValue x) return x.value();
    if (value instanceof Value.FloatValue x) return x.value();
    if (value instanceof Value.NullValue) return 0;
    if (value instanceof Value.BooleanValue x) return x.value() ? 1 : 0;
    if (value instanceof Value.StringValue x) return stringNumber(x.value());
    return Double.NaN;
  }

  static boolean looseEquals(Value left, Value right) {
    if (left instanceof Value.NullValue || left instanceof Value.UndefinedValue)
      return right instanceof Value.NullValue || right instanceof Value.UndefinedValue;
    if (right instanceof Value.NullValue || right instanceof Value.UndefinedValue) return false;
    if (left instanceof Value.BooleanValue x) return looseNumberEquals(x.value() ? 1 : 0, right);
    if (right instanceof Value.BooleanValue x) return looseNumberEquals(x.value() ? 1 : 0, left);
    if (numeric(left) && right instanceof Value.StringValue x) return number(left) == stringNumber(x.value());
    if (numeric(right) && left instanceof Value.StringValue x) return number(right) == stringNumber(x.value());
    return strictValueEquals(left, right);
  }

  static boolean rawTruthy(Value value) {
    return switch (value) {
      case Value.NullValue ignored -> false;
      case Value.UndefinedValue ignored -> false;
      case Value.BooleanValue x -> x.value();
      case Value.IntegerValue x -> x.value() != 0 && !Double.isNaN(x.value());
      case Value.FloatValue x -> x.value() != 0 && !Double.isNaN(x.value());
      case Value.StringValue x -> !x.undefinedBacked() && !x.value().isEmpty();
      default -> true;
    };
  }

  static boolean numeric(Value value) {
    return value instanceof Value.IntegerValue || value instanceof Value.FloatValue;
  }

  private static boolean looseNumberEquals(double number, Value other) {
    return numeric(other)
        ? number == number(other)
        : other instanceof Value.StringValue x && number == stringNumber(x.value());
  }

  private static boolean strictValueEquals(Value left, Value right) {
    if (numeric(left) && numeric(right)) return number(left) == number(right);
    if (left instanceof Value.StringValue a && right instanceof Value.StringValue b)
      return a.undefinedBacked() == b.undefinedBacked() && a.value().equals(b.value());
    if (left instanceof Value.BooleanValue a && right instanceof Value.BooleanValue b)
      return a.value() == b.value();
    return left == right;
  }

  private static Value numeric(double value, boolean floating) {
    return floating ? new Value.FloatValue(value) : new Value.IntegerValue(value);
  }

  private static boolean integralResult(double value) {
    return Double.isFinite(value) && value == Math.rint(value);
  }

  private static double number(Value value) {
    return value instanceof Value.IntegerValue x ? x.value() : ((Value.FloatValue) value).value();
  }

  private static String arrayText(List<Value> values) {
    var text = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) text.append(',');
      var value = values.get(i);
      if (!(value instanceof Value.NullValue || value instanceof Value.UndefinedValue)) text.append(toText(value));
    }
    return text.toString();
  }

  private static double stringNumber(String value) {
    var text = trimEcmaWhitespace(value);
    if (text.isEmpty()) return 0;
    if (text.equals("Infinity") || text.equals("+Infinity")) return Double.POSITIVE_INFINITY;
    if (text.equals("-Infinity")) return Double.NEGATIVE_INFINITY;
    if (text.matches("0[xX][0-9a-fA-F]+")) return new BigInteger(text.substring(2), 16).doubleValue();
    if (text.matches("0[oO][0-7]+")) return new BigInteger(text.substring(2), 8).doubleValue();
    if (text.matches("0[bB][01]+")) return new BigInteger(text.substring(2), 2).doubleValue();
    if (!text.matches("[+-]?(?:(?:\\d+\\.?\\d*)|(?:\\.\\d+))(?:[eE][+-]?\\d+)?")) return Double.NaN;
    return Double.parseDouble(text);
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
}
