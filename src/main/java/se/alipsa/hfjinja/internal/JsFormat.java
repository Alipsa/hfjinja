package se.alipsa.hfjinja.internal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** JavaScript-compatible number and JSON string formatting helpers. */
@SuppressWarnings("doclint:missing")
public final class JsFormat {
  private JsFormat() {}
  public static String jsonString(double value) { return Double.isFinite(value) ? shortest(value) : "null"; }
  public static String plainString(double value) {
    if (Double.isNaN(value)) return "NaN";
    if (value == Double.POSITIVE_INFINITY) return "Infinity";
    if (value == Double.NEGATIVE_INFINITY) return "-Infinity";
    return shortest(value);
  }
  public static String quote(String value) {
    var b = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) { char c = value.charAt(i); switch (c) {
      case '\\' -> b.append("\\\\"); case '"' -> b.append("\\\""); case '\b' -> b.append("\\b");
      case '\f' -> b.append("\\f"); case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
      default -> {
        boolean high = Character.isHighSurrogate(c);
        boolean low = Character.isLowSurrogate(c);
        boolean paired = high && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))
            || low && i > 0 && Character.isHighSurrogate(value.charAt(i - 1));
        if (c < 0x20 || (high || low) && !paired) b.append(String.format("\\u%04x", (int)c)); else b.append(c);
      }
    }} return b.append('"').toString();
  }
  static String shortest(double value) {
    if (value == 0d) return "0";
    var exact = new BigDecimal(value);
    for (int p = 1; p <= 17; p++) { var c = exact.round(new MathContext(p, RoundingMode.HALF_EVEN)).stripTrailingZeros(); if (Double.parseDouble(c.toString()) == value) return format(c); }
    throw new IllegalStateException("No JavaScript round-trip decimal for finite double");
  }
  static String format(BigDecimal decimal) {
    if (decimal.signum() == 0) return "0"; var n = decimal.stripTrailingZeros(); int e = n.precision() - n.scale() - 1;
    if (e >= -6 && e < 21) return n.toPlainString(); String d = n.unscaledValue().abs().toString();
    return (n.signum() < 0 ? "-" : "") + (d.length() == 1 ? d : d.charAt(0) + "." + d.substring(1)) + "e" + (e >= 0 ? "+" : "") + e;
  }
}
