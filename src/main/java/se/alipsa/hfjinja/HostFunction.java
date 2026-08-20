package se.alipsa.hfjinja;

import java.util.List;

/**
 * An explicitly registered callable available to a template render.
 *
 * <p>Arguments are immutable, inert Java values: booleans, strings, {@link Long} integers that
 * fit a {@code long}, {@link Double} floats and larger integer doubles, {@code null}, and
 * recursively immutable lists or maps. A template {@code undefined} value cannot cross this
 * boundary; calls containing it fail with {@code HOST_FUNCTION}. Return values must satisfy the
 * same closed host-value boundary as render context values. Returning an exact scalar argument
 * object unchanged preserves its runtime int/float tag. Computed {@link Double} or {@link Long}
 * results convert by value: use {@link FloatResult} to retain an integral float, or
 * {@link IntegerResult} for an integral result outside the JavaScript safe-integer range.
 *
 * <p>Exception to the integer contract: an integral {@code -0} argument is delivered as
 * {@link Double}, not {@link Long}. The standard {@code Long} boxing path is subject to JVM
 * caching, and a cached box cannot be tied to one specific {@code -0} argument without risking
 * that an unrelated {@code +0} elsewhere silently aliases to it.
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
