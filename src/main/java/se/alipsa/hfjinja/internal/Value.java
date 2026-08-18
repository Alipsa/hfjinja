package se.alipsa.hfjinja.internal;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    values = Collections.unmodifiableList(new ArrayList<>(values));
  }
}

record ObjectValue(Map<String, Value> values) implements Value {
  ObjectValue {
    values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}

/** Converts only the explicitly supported Java boundary types into immutable runtime values. */
final class Values {
  private static final double MAX_SAFE_INTEGER = 9_007_199_254_740_991d;

  private Values() {}

  static Value fromHost(Object input) {
    return fromHost(input, new IdentityHashMap<>());
  }

  private static Value fromHost(Object input, IdentityHashMap<Object, Boolean> ancestors) {
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
      requireAcyclic(input, ancestors);
      try {
        var values = new ArrayList<Value>(Array.getLength(input));
        for (int index = 0; index < Array.getLength(input); index++) {
          values.add(fromHost(Array.get(input, index), ancestors));
        }
        return new ArrayValue(values);
      } finally {
        ancestors.remove(input);
      }
    }
    if (input instanceof List<?> list) {
      requireAcyclic(input, ancestors);
      try {
        var values = new ArrayList<Value>(list.size());
        for (Object item : list) {
          values.add(fromHost(item, ancestors));
        }
        return new ArrayValue(values);
      } finally {
        ancestors.remove(input);
      }
    }
    if (input instanceof Map<?, ?> map) {
      requireAcyclic(input, ancestors);
      try {
        var values = new LinkedHashMap<String, Value>(map.size());
        for (var entry : map.entrySet()) {
          if (!(entry.getKey() instanceof String key)) {
            throw conversion("Map keys must be strings");
          }
          values.put(key, fromHost(entry.getValue(), ancestors));
        }
        return new ObjectValue(values);
      } finally {
        ancestors.remove(input);
      }
    }
    throw conversion("Unsupported host value type: " + input.getClass().getName());
  }

  private static Value numberValue(Number number) {
    final String canonical;
    try {
      canonical = new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
    } catch (NumberFormatException exception) {
      throw conversion("Number does not have a decimal representation: " + number.getClass().getName());
    }

    final double value;
    try {
      value = Double.parseDouble(canonical);
    } catch (NumberFormatException exception) {
      throw conversion("Number is outside the JavaScript numeric range: " + canonical);
    }
    if (!Double.isFinite(value)) {
      throw conversion("Number must be finite: " + canonical);
    }
    if (!shortestJsDecimal(value).equals(canonical)) {
      throw conversion("Number is not representable as a JavaScript number: " + canonical);
    }
    if (isIntegral(canonical)) {
      if (Math.abs(value) > MAX_SAFE_INTEGER) {
        throw conversion("Integer is outside the JavaScript safe-integer range: " + canonical);
      }
      return new IntegerValue(value);
    }
    return new FloatValue(value);
  }

  private static boolean isIntegral(String decimal) {
    return !decimal.contains(".");
  }

  private static String shortestJsDecimal(double value) {
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
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
