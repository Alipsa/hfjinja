package se.alipsa.hfjinja.internal;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.TemplateRenderException;

/** Internal, closed template value model. */
sealed interface Value
    permits UndefinedValue,
        NullValue,
        BooleanValue,
        IntegerValue,
        FloatValue,
        StringValue,
        ArrayValue,
        ObjectValue {}

enum UndefinedValue implements Value {
  INSTANCE
}

enum NullValue implements Value {
  INSTANCE
}

record BooleanValue(boolean value) implements Value {}

record IntegerValue(double value) implements Value {}

record FloatValue(double value) implements Value {}

record StringValue(String value) implements Value {}

record ArrayValue(List<Value> values) implements Value {
  ArrayValue {
    Objects.requireNonNull(values, "values");
    values = Collections.unmodifiableList(new ArrayList<>(values));
  }
}

record ObjectValue(Map<String, Value> values) implements Value {
  ObjectValue {
    Objects.requireNonNull(values, "values");
    values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}

/** Converts only the explicitly supported Java boundary types into immutable runtime values. */
final class Values {
  private static final double MAX_SAFE_INTEGER = 9_007_199_254_740_991d;

  private Values() {}

  static Value fromHost(Object input) {
    return fromHost(input, new IdentityHashMap<>(), new IdentityHashMap<>());
  }

  /**
   * Converts a runtime value into an inert value for a {@code HostFunction}. Collections are copied
   * and made immutable so a function can neither observe nor mutate interpreter state.
   */
  static Object toHost(Value value) {
    Objects.requireNonNull(value, "value");
    return toHost(value, new IdentityHashMap<>());
  }

  private static Object toHost(Value value, IdentityHashMap<Value, Object> converted) {
    // Values are immutable and only originate from acyclic host input, so this map preserves DAG
    // sharing rather than needing a separate cycle guard.
    return switch (value) {
      case UndefinedValue ignored -> throw new UndefinedHostValueException();
      case NullValue ignored -> null;
      case BooleanValue booleanValue -> booleanValue.value();
      case IntegerValue integerValue -> hostInteger(integerValue.value());
      case FloatValue floatValue -> floatValue.value();
      case StringValue stringValue -> stringValue.value();
      case ArrayValue arrayValue -> {
        var existing = converted.get(arrayValue);
        if (existing != null) {
          yield existing;
        }
        var values = new ArrayList<Object>(arrayValue.values().size());
        for (var item : arrayValue.values()) {
          values.add(toHost(item, converted));
        }
        var hostValue = Collections.unmodifiableList(values);
        converted.put(arrayValue, hostValue);
        yield hostValue;
      }
      case ObjectValue objectValue -> {
        var existing = converted.get(objectValue);
        if (existing != null) {
          yield existing;
        }
        var values = new LinkedHashMap<String, Object>(objectValue.values().size());
        for (var entry : objectValue.values().entrySet()) {
          values.put(entry.getKey(), toHost(entry.getValue(), converted));
        }
        var hostValue = Collections.unmodifiableMap(values);
        converted.put(objectValue, hostValue);
        yield hostValue;
      }
    };
  }

  private static Object hostInteger(double value) {
    if (Double.isFinite(value) && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) {
      return Long.valueOf((long) value);
    }
    return value;
  }

  static final class UndefinedHostValueException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private UndefinedHostValueException() {}
  }

  private static Value fromHost(
      Object input,
      IdentityHashMap<Object, Value> converted,
      IdentityHashMap<Object, Boolean> visiting) {
    if (input == null) {
      return NullValue.INSTANCE;
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
        return existing;
      }
      requireAcyclic(input, visiting);
      try {
        int length = Array.getLength(input);
        var values = new ArrayList<Value>(length);
        for (int index = 0; index < length; index++) {
          values.add(fromHost(Array.get(input, index), converted, visiting));
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
        return existing;
      }
      requireAcyclic(input, visiting);
      try {
        var values = new ArrayList<Value>(list.size());
        for (Object item : list) {
          values.add(fromHost(item, converted, visiting));
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
        return existing;
      }
      requireAcyclic(input, visiting);
      try {
        var values = new LinkedHashMap<String, Value>(map.size());
        for (var entry : map.entrySet()) {
          if (!(entry.getKey() instanceof String key)) {
            throw conversion("Map keys must be strings");
          }
          values.put(key, fromHost(entry.getValue(), converted, visiting));
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

  private static Value numberValue(Number number) {
    if ((number instanceof Double || number instanceof Float)
        && !Double.isFinite(number.doubleValue())) {
      throw conversion("Number must be finite: " + number);
    }
    final BigDecimal inputDecimal;
    try {
      inputDecimal = new BigDecimal(number.toString()).stripTrailingZeros();
    } catch (NumberFormatException exception) {
      throw conversion("Number does not have a decimal representation: " + number.getClass().getName());
    }
    final String canonical = formatJsDecimal(inputDecimal);

    final double value = number instanceof Double
        ? number.doubleValue()
        : Double.parseDouble(inputDecimal.toString());
    if (!Double.isFinite(value)) {
      throw conversion("Number must be finite: " + canonical);
    }
    if (!(number instanceof Double)
        && !shortestJsDecimal(value).equals(canonical)) {
      throw conversion("Number is not representable as a JavaScript number: " + canonical);
    }
    if (inputDecimal.scale() <= 0) {
      if (Math.abs(value) > MAX_SAFE_INTEGER) {
        throw conversion("Integer is outside the JavaScript safe-integer range: " + canonical);
      }
      return new IntegerValue(normalizeHostZero(value));
    }
    return new FloatValue(normalizeHostZero(value));
  }

  /** Host values have no observable signed zero, unlike runtime arithmetic such as {@code 1 / -0}. */
  private static double normalizeHostZero(double value) {
    return value == 0d ? 0d : value;
  }

  private static String shortestJsDecimal(double value) {
    var exact = new BigDecimal(value);
    for (int precision = 1; precision <= 17; precision++) {
      var candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN)).stripTrailingZeros();
      if (Double.parseDouble(candidate.toString()) == value) {
        return formatJsDecimal(candidate);
      }
    }
    throw new IllegalStateException("No JavaScript round-trip decimal for finite double");
  }

  private static String formatJsDecimal(BigDecimal decimal) {
    if (decimal.signum() == 0) {
      return "0";
    }
    var normalized = decimal.stripTrailingZeros();
    var exponent = normalized.precision() - normalized.scale() - 1;
    if (exponent >= -6 && exponent < 21) {
      return normalized.toPlainString();
    }
    var digits = normalized.unscaledValue().abs().toString();
    var mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
    var sign = normalized.signum() < 0 ? "-" : "";
    return sign + mantissa + "e" + (exponent >= 0 ? "+" : "") + exponent;
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
