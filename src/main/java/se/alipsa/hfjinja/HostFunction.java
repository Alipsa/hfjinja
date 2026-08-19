package se.alipsa.hfjinja;

import java.util.List;

/**
 * An explicitly registered callable available to a template render.
 *
 * <p>Arguments are immutable, inert Java values: booleans, strings, {@link Long} integers that
 * fit a {@code long}, {@link Double} floats and larger integer doubles, {@code null}, and
 * recursively immutable lists or maps. A template {@code undefined} value cannot cross this
 * boundary; calls containing it fail with {@code HOST_FUNCTION}. Return values must satisfy the
 * same closed host-value boundary as render context values. Return {@link FloatResult} whenever
 * retaining a float result whose value is integral matters; an ordinary {@link Double} return is
 * converted by value and therefore becomes an integer in that case. Return {@link IntegerResult}
 * for a computed integral result outside the JavaScript safe-integer range. Returning an ordinary
 * {@link Long} outside that range is rejected; use {@link #integerResult(double)} instead.
 */
@FunctionalInterface
public interface HostFunction {
  Object apply(List<Object> arguments);

  /** Marks a host-function result as a template float, including integral values such as {@code 2.0}. */
  static FloatResult floatResult(double value) {
    return new FloatResult(value);
  }

  /** Marks a computed integral host-function result, including values outside the safe-integer range. */
  static IntegerResult integerResult(double value) {
    return new IntegerResult(value);
  }

  /** Explicit float result marker for {@link #floatResult(double)}. */
  record FloatResult(double value) {}

  /** Explicit integer result marker for {@link #integerResult(double)}. */
  record IntegerResult(double value) {}
}
