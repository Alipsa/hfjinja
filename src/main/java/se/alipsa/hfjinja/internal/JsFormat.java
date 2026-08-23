package se.alipsa.hfjinja.internal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.SourceLocation;
import se.alipsa.hfjinja.TemplateRenderException;

/** JavaScript-compatible number and JSON string formatting helpers. */
@SuppressWarnings("doclint:missing")
public final class JsFormat {
  private JsFormat() {}

  /**
   * Formats a number for JSON.
   *
   * @param value the number to format
   * @return a JSON number, or {@code null} for a non-finite value
   */
  public static String jsonString(double value) {
    return Double.isFinite(value) ? shortest(value) : "null";
  }

  /**
   * Formats a number using JavaScript's ordinary string form.
   *
   * @param value the number to format
   * @return the JavaScript-compatible representation
   */
  public static String plainString(double value) {
    if (Double.isNaN(value)) return "NaN";
    if (value == Double.POSITIVE_INFINITY) return "Infinity";
    if (value == Double.NEGATIVE_INFINITY) return "-Infinity";
    return shortest(value);
  }

  /** Formats a float runtime value, retaining a fractional zero for integral floats. */
  public static String floatString(double value) {
    if (value % 1 == 0 && Math.abs(value) < 1e21)
      return new BigDecimal(value).setScale(1, RoundingMode.UNNECESSARY).toPlainString();
    return plainString(value);
  }

  /** Renders a runtime value using the project's JSON-compatible value representation. */
  public static String runtimeJson(Value value, SourceLocation location) {
    var rendered = runtimeJsonValue(value, location, new IdentityHashMap<>());
    return rendered == null ? "undefined" : rendered;
  }

  private static String runtimeJsonValue(
      Value value, SourceLocation location, IdentityHashMap<Value, Boolean> visiting) {
    return switch (value) {
      case Value.NullValue ignored -> "null";
      case Value.UndefinedValue ignored -> "undefined";
      case Value.BooleanValue x -> Boolean.toString(x.value());
      case Value.IntegerValue x -> jsonString(x.value());
      case Value.FloatValue x -> jsonString(x.value());
      case Value.StringValue x -> x.undefinedBacked() ? null : quote(x.value());
      case Value.ArrayValue x -> jsonArray(x.values(), location, visiting, x);
      case Value.ObjectValue x -> jsonObject(x.values(), location, visiting, x);
      case Value.TupleValue ignored -> jsonUnsupported("TupleValue", location);
      case Value.KeywordArgumentsValue ignored ->
          jsonUnsupported("KeywordArgumentsValue", location);
      case Value.CallableValue ignored -> jsonUnsupported("FunctionValue", location);
    };
  }

  private static String jsonUnsupported(String type, SourceLocation location) {
    throw new TemplateRenderException(
        "Cannot convert to JSON: " + type, ErrorCategory.TYPE, location);
  }

  private static String jsonArray(
      List<Value> values,
      SourceLocation location,
      IdentityHashMap<Value, Boolean> visiting,
      Value container) {
    enterJson(container, visiting, location);
    try {
      var result = new StringBuilder("[");
      for (int i = 0; i < values.size(); i++) {
        if (i > 0) result.append(", ");
        var rendered = runtimeJsonValue(values.get(i), location, visiting);
        if (rendered != null) result.append(rendered);
      }
      return result.append(']').toString();
    } finally {
      visiting.remove(container);
    }
  }

  private static String jsonObject(
      Map<?, Value> values,
      SourceLocation location,
      IdentityHashMap<Value, Boolean> visiting,
      Value container) {
    enterJson(container, visiting, location);
    try {
      var result = new StringBuilder("{");
      boolean first = true;
      for (var entry : values.entrySet()) {
        if (!first) result.append(", ");
        first = false;
        result.append(jsonObjectKey(entry.getKey())).append(": ");
        var rendered = runtimeJsonValue(entry.getValue(), location, visiting);
        result.append(rendered == null ? "undefined" : rendered);
      }
      return result.append('}').toString();
    } finally {
      visiting.remove(container);
    }
  }

  private static String jsonObjectKey(Object key) {
    return key instanceof Value.StringValue string && string.undefinedBacked()
        ? "undefined"
        : quote((String) key);
  }

  private static void enterJson(
      Value value, IdentityHashMap<Value, Boolean> visiting, SourceLocation location) {
    if (visiting.put(value, Boolean.TRUE) != null)
      throw new TemplateRenderException(
          "Cannot convert cyclic value to JSON", ErrorCategory.TYPE, location);
  }

  /**
   * Quotes and escapes a string for JSON.
   *
   * @param value the string to quote
   * @return the JSON string literal
   */
  public static String quote(String value) {
    var b = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> b.append("\\\\");
        case '"' -> b.append("\\\"");
        case '\b' -> b.append("\\b");
        case '\f' -> b.append("\\f");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          boolean high = Character.isHighSurrogate(c);
          boolean low = Character.isLowSurrogate(c);
          boolean paired =
              high && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))
                  || low && i > 0 && Character.isHighSurrogate(value.charAt(i - 1));
          if (c < 0x20 || (high || low) && !paired) b.append(String.format("\\u%04x", (int) c));
          else b.append(c);
        }
      }
    }
    return b.append('"').toString();
  }

  static String shortest(double value) {
    if (value == 0d) return "0";
    var exact = new BigDecimal(value);
    for (int p = 1; p <= 17; p++) {
      var c = exact.round(new MathContext(p, RoundingMode.HALF_EVEN)).stripTrailingZeros();
      if (Double.parseDouble(c.toString()) == value) return format(c);
    }
    throw new IllegalStateException("No JavaScript round-trip decimal for finite double");
  }

  static String format(BigDecimal decimal) {
    if (decimal.signum() == 0) return "0";
    var n = decimal.stripTrailingZeros();
    int e = n.precision() - n.scale() - 1;
    if (e >= -6 && e < 21) return n.toPlainString();
    String d = n.unscaledValue().abs().toString();
    return (n.signum() < 0 ? "-" : "")
        + (d.length() == 1 ? d : d.charAt(0) + "." + d.substring(1))
        + "e"
        + (e >= 0 ? "+" : "")
        + e;
  }
}
