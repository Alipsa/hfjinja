package se.alipsa.hfjinja.internal;

import static se.alipsa.hfjinja.internal.Value.*;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.HostFunction;
import se.alipsa.hfjinja.TemplateRenderException;
/** Converts only the explicitly supported Java boundary types into immutable runtime values. */
@SuppressWarnings("doclint:missing")
public final class Values {
  private static final double MAX_SAFE_INTEGER = 9_007_199_254_740_991d;

  private Values() {}

  public static Value fromHost(Object input) {
    return fromHost(input, new IdentityHashMap<>(), new IdentityHashMap<>(), new IdentityHashMap<>());
  }

  /**
   * Converts a runtime value into an inert value for a {@code HostFunction}. Collections are copied
   * and made immutable so a function can neither observe nor mutate interpreter state.
   */
  public static Object toHost(
      Value value,
      IdentityHashMap<Value, Object> converted,
      IdentityHashMap<Object, Value> sourceValues,
      HostPath path) {
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(converted, "converted");
    Objects.requireNonNull(sourceValues, "sourceValues");
    Objects.requireNonNull(path, "path");
    // Values are immutable and only originate from acyclic host input, so this map preserves DAG
    // sharing rather than needing a separate cycle guard.
    return switch (value) {
      case UndefinedValue ignored -> throw new UndefinedHostValueException("undefined value at " + path.describe());
      case NullValue ignored -> null;
      case BooleanValue booleanValue -> booleanValue.value();
      case IntegerValue integerValue -> {
        var hostValue = hostInteger(integerValue.value());
        // Safe integers reconstruct faithfully from a Long. Larger runtime integers and -0 do
        // not, so retain their exact source when a function echoes one.
        yield !(Math.abs(integerValue.value()) <= MAX_SAFE_INTEGER) || isNegativeZero(integerValue.value())
            ? sourceValue(hostValue, value, sourceValues)
            : hostValue;
      }
      case FloatValue floatValue -> sourceValue(hostFloat(floatValue.value()), value, sourceValues);
      case StringValue stringValue -> stringValue.value();
      case ArrayValue arrayValue -> {
        var existing = converted.get(arrayValue);
        if (existing != null) {
          yield existing;
        }
        var values = new ArrayList<Object>(arrayValue.values().size());
        for (int index = 0; index < arrayValue.values().size(); index++) {
          var pathLength = path.length();
          path.appendIndex(index);
          try {
            values.add(toHost(arrayValue.values().get(index), converted, sourceValues, path));
          } finally {
            path.restore(pathLength);
          }
        }
        var hostValue = Collections.unmodifiableList(values);
        converted.put(arrayValue, hostValue);
        sourceValues.putIfAbsent(hostValue, value);
        yield hostValue;
      }
      case TupleValue tupleValue -> {
        var existing = converted.get(tupleValue);
        if (existing != null) yield existing;
        var values = new ArrayList<Object>(tupleValue.values().size());
        for (int index = 0; index < tupleValue.values().size(); index++) {
          var pathLength = path.length(); path.appendIndex(index);
          try { values.add(toHost(tupleValue.values().get(index), converted, sourceValues, path)); }
          finally { path.restore(pathLength); }
        }
        var hostValue = Collections.unmodifiableList(values);
        converted.put(tupleValue, hostValue); sourceValues.putIfAbsent(hostValue, value);
        yield hostValue;
      }
      case ObjectValue objectValue -> {
        var existing = converted.get(objectValue);
        if (existing != null) {
          yield existing;
        }
        var values = new LinkedHashMap<String, Object>(objectValue.values().size());
        for (var entry : objectValue.values().entrySet()) {
          var pathLength = path.length();
          path.appendKey(entry.getKey());
          try {
            values.put(entry.getKey(), toHost(entry.getValue(), converted, sourceValues, path));
          } finally {
            path.restore(pathLength);
          }
        }
        var hostValue = Collections.unmodifiableMap(values);
        converted.put(objectValue, hostValue);
        sourceValues.putIfAbsent(hostValue, value);
        yield hostValue;
      }
      case CallableValue ignored -> throw new UndefinedHostValueException("callable value at " + path.describe());
    };
  }

  private static Object sourceValue(
      Object hostValue, Value sourceValue, IdentityHashMap<Object, Value> sourceValues) {
    // Host functions receive ordinary Java scalar types. An exact scalar argument object returned
    // unchanged is the only signal that it should retain its runtime int/float tag; computed boxed
    // values convert by value and must use FloatResult or IntegerResult when that tag matters.
    //
    // This registration is sound only because nothing JVM-cached ever reaches it: callers route
    // safe-range integers straight through without calling sourceValue(), so only wide Longs
    // (always freshly boxed) and Doubles (never cached by the JVM) arrive here. Registering a
    // cached box would let an unrelated host value with the same identity silently alias to the
    // wrong tag.
    assert !isJvmCachedBox(hostValue) : "Refusing to register a JVM-cached box: " + hostValue;
    sourceValues.putIfAbsent(hostValue, sourceValue);
    return hostValue;
  }

  private static boolean isJvmCachedBox(Object value) {
    return value instanceof Long longValue && Long.valueOf(longValue) == value
        || value instanceof Boolean booleanValue && Boolean.valueOf(booleanValue) == value;
  }

  private static Object hostInteger(double value) {
    if (isNegativeZero(value)) {
      return Double.valueOf(value);
    }
    if (Double.isFinite(value) && value >= -0x1.0p63 && value < 0x1.0p63) {
      return Long.valueOf((long) value);
    }
    return Double.valueOf(value);
  }

  private static Double hostFloat(double value) {
    return Double.valueOf(value);
  }

  public static final class UndefinedHostValueException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private UndefinedHostValueException(String message) {
      super(message);
    }
  }

  /** Defers construction of a diagnostic path string until undefined reaches the host boundary. */
  public static final class HostPath {
    private final StringBuilder description;

    static HostPath argument(int index) {
      return new HostPath("argument " + index);
    }

    private HostPath(String description) {
      this.description = new StringBuilder(description);
    }

    int length() {
      return description.length();
    }

    void appendIndex(int index) {
      description.append('[').append(index).append(']');
    }

    void appendKey(String key) {
      description.append('.').append(Objects.requireNonNull(key, "key"));
    }

    void restore(int length) {
      description.setLength(length);
    }

    String describe() {
      return description.toString();
    }
  }

  public static Value fromHostFunctionReturn(Object input, IdentityHashMap<Object, Value> sourceValues) {
    return fromHost(input, new IdentityHashMap<>(), new IdentityHashMap<>(), new IdentityHashMap<>(), sourceValues, true);
  }

  private static Value fromHost(
      Object input,
      IdentityHashMap<Object, Value> converted,
      IdentityHashMap<Object, Boolean> visiting,
      IdentityHashMap<Value, Boolean> containsMutable) {
    return fromHost(input, converted, visiting, containsMutable, null, false);
  }

  private static Value fromHost(
      Object input,
      IdentityHashMap<Object, Value> converted,
      IdentityHashMap<Object, Boolean> visiting,
      IdentityHashMap<Value, Boolean> containsMutable,
      IdentityHashMap<Object, Value> sourceValues,
      boolean allowResultMarkers) {
    if (sourceValues != null) {
      var sourceValue = sourceValues.get(input);
      if (sourceValue != null) {
        return sourceValue;
      }
    }
    if (input == null) {
      return NullValue.INSTANCE;
    }
    if (allowResultMarkers && input instanceof HostFunction.FloatResult floatResult) {
      if (!Double.isFinite(floatResult.value())) {
        throw conversion("Float result must be finite: " + floatResult.value());
      }
      return new FloatValue(floatResult.value());
    }
    if (allowResultMarkers && input instanceof HostFunction.IntegerResult integerResult) {
      if (!Double.isFinite(integerResult.value()) || integerResult.value() != Math.rint(integerResult.value())) {
        throw conversion("Integer result must be finite and integral: " + integerResult.value());
      }
      return new IntegerValue(integerResult.value());
    }
    if (input instanceof String string) {
      return new StringValue(string);
    }
    if (input instanceof Boolean bool) {
      return new BooleanValue(bool);
    }
    if (input instanceof Number number) {
      return numberValue(number);
    }
    if (input.getClass().isArray()) {
      var existing = converted.get(input);
      if (existing != null) {
        return copyMutableObjects(existing, containsMutable);
      }
      requireAcyclic(input, visiting);
      try {
        int length = Array.getLength(input);
        var values = new ArrayList<Value>(length);
        for (int index = 0; index < length; index++) {
          values.add(fromHost(Array.get(input, index), converted, visiting, containsMutable, sourceValues, allowResultMarkers));
        }
        var value = new ArrayValue(values);
        converted.put(input, value);
        return value;
      } finally {
        visiting.remove(input);
      }
    }
    if (input instanceof List<?> list) {
      var existing = converted.get(input);
      if (existing != null) {
        return copyMutableObjects(existing, containsMutable);
      }
      requireAcyclic(input, visiting);
      try {
        var values = new ArrayList<Value>(list.size());
        for (Object item : list) {
          values.add(fromHost(item, converted, visiting, containsMutable, sourceValues, allowResultMarkers));
        }
        var value = new ArrayValue(values);
        converted.put(input, value);
        return value;
      } finally {
        visiting.remove(input);
      }
    }
    if (input instanceof Map<?, ?> map) {
      var existing = converted.get(input);
      if (existing != null) {
        return copyMutableObjects(existing, containsMutable);
      }
      requireAcyclic(input, visiting);
      try {
        var values = new LinkedHashMap<String, Value>(map.size());
        for (var entry : map.entrySet()) {
          if (!(entry.getKey() instanceof String key)) {
            throw conversion("Map keys must be strings");
          }
          values.put(key, fromHost(entry.getValue(), converted, visiting, containsMutable, sourceValues, allowResultMarkers));
        }
        var value = new ObjectValue(values);
        converted.put(input, value);
        return value;
      } finally {
        visiting.remove(input);
      }
    }
    throw conversion("Unsupported host value type: " + input.getClass().getName());
  }

  /** Copies only paths containing mutable objects; immutable DAG portions stay shared. */
  private static Value copyMutableObjects(Value value, IdentityHashMap<Value, Boolean> containsMutable) {
    if (!containsMutableObjects(value, containsMutable)) return value;
    return switch (value) {
      case ObjectValue objectValue -> {
        var copy = new LinkedHashMap<String, Value>(objectValue.values().size());
        for (var entry : objectValue.values().entrySet()) {
          copy.put(entry.getKey(), copyMutableObjects(entry.getValue(), containsMutable));
        }
        yield new ObjectValue(copy);
      }
      case ArrayValue arrayValue -> {
        var copy = new ArrayList<Value>(arrayValue.values().size());
        boolean changed = false;
        for (var item : arrayValue.values()) {
          var itemCopy = copyMutableObjects(item, containsMutable);
          changed |= itemCopy != item;
          copy.add(itemCopy);
        }
        yield changed ? new ArrayValue(copy) : arrayValue;
      }
      case TupleValue tupleValue -> {
        var copy = new ArrayList<Value>(tupleValue.values().size());
        boolean changed = false;
        for (var item : tupleValue.values()) {
          var itemCopy = copyMutableObjects(item, containsMutable);
          changed |= itemCopy != item;
          copy.add(itemCopy);
        }
        yield changed ? new TupleValue(copy) : tupleValue;
      }
      default -> value;
    };
  }

  private static boolean containsMutableObjects(Value value, IdentityHashMap<Value, Boolean> memo) {
    var existing = memo.get(value);
    if (existing != null) return existing;
    boolean result = switch (value) {
      case ObjectValue ignored -> true;
      case ArrayValue arrayValue -> arrayValue.values().stream().anyMatch(item -> containsMutableObjects(item, memo));
      case TupleValue tupleValue -> tupleValue.values().stream().anyMatch(item -> containsMutableObjects(item, memo));
      default -> false;
    };
    memo.put(value, result);
    return result;
  }

  private static Value numberValue(Number number) {
    if ((number instanceof Double || number instanceof Float)
        && !Double.isFinite(number.doubleValue())) {
      throw conversion("Number must be finite: " + Double.toString(number.doubleValue()));
    }
    final String text = number.toString();
    if (text == null) {
      throw conversion("Number does not have a decimal representation: " + number.getClass().getName());
    }
    final BigDecimal inputDecimal;
    try {
      inputDecimal = new BigDecimal(text).stripTrailingZeros();
    } catch (NumberFormatException exception) {
      throw conversion("Number does not have a decimal representation: " + number.getClass().getName());
    }
    final String canonical = JsFormat.format(inputDecimal);

    final double value = number instanceof Double
        ? number.doubleValue()
        : Double.parseDouble(inputDecimal.toString());
    if (!Double.isFinite(value)) {
      throw conversion("Number must be finite: " + canonical);
    }
    if (inputDecimal.scale() <= 0 && Math.abs(value) > MAX_SAFE_INTEGER) {
      throw conversion("Integer is outside the JavaScript safe-integer range: " + canonical);
    }
    if (!(number instanceof Double)
        && !JsFormat.shortest(value).equals(canonical)) {
      throw conversion("Number is not representable as a JavaScript number: " + canonical);
    }
    if (inputDecimal.scale() <= 0) {
      return new IntegerValue(normalizeHostZero(value));
    }
    return new FloatValue(normalizeHostZero(value));
  }

  /** Host values have no observable signed zero, unlike runtime arithmetic such as {@code 1 / -0}. */
  private static double normalizeHostZero(double value) {
    return value == 0d ? 0d : value;
  }

  private static boolean isNegativeZero(double value) {
    return Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(-0d);
  }

  private static void requireAcyclic(Object input, IdentityHashMap<Object, Boolean> ancestors) {
    if (ancestors.put(input, Boolean.TRUE) != null) {
      throw conversion("Host value graph contains a cycle");
    }
  }

  private static TemplateRenderException conversion(String message) {
    return new TemplateRenderException(message, ErrorCategory.HOST_CONVERSION, null);
  }
}
