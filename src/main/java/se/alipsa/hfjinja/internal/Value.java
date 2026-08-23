package se.alipsa.hfjinja.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import se.alipsa.hfjinja.SourceLocation;

/**
 * Internal, closed template value model. This public type is intentionally in an internal package:
 * it is shared by the parser-facing runtime without becoming part of the exported public API.
 */
@SuppressWarnings("doclint:missing")
public sealed interface Value
    permits Value.UndefinedValue,
        Value.NullValue,
        Value.BooleanValue,
        Value.IntegerValue,
        Value.FloatValue,
        Value.StringValue,
        Value.ArrayValue,
        Value.TupleValue,
        Value.ObjectValue,
        Value.KeywordArgumentsValue,
        Value.CallableValue {
  enum UndefinedValue implements Value {
    INSTANCE
  }

  enum NullValue implements Value {
    INSTANCE
  }

  record BooleanValue(boolean value) implements Value {}

  record IntegerValue(double value) implements Value {}

  record FloatValue(double value) implements Value {}

  /** A string value, optionally backed by JavaScript {@code undefined}. */
  record StringValue(String value, boolean undefinedBacked) implements Value {
    public StringValue(String value) {
      this(value, false);
    }

    /** Returns the string-shaped value produced by out-of-range string indexing. */
    public static StringValue undefined() {
      return new StringValue("undefined", true);
    }
  }

  record ArrayValue(List<Value> values) implements Value {
    public ArrayValue {
      values =
          Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(values, "values")));
    }
  }

  record TupleValue(List<Value> values) implements Value {
    public TupleValue {
      values =
          Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(values, "values")));
    }
  }

  /** Mutable by design for template member assignment; never use as a hash-based key. */
  final class ObjectValue implements Value {
    private final Map<Object, Value> values;

    public ObjectValue(Map<?, Value> values) {
      this.values = new LinkedHashMap<>(Objects.requireNonNull(values, "values"));
    }

    public Map<Object, Value> values() {
      return values;
    }

    @Override
    public boolean equals(Object object) {
      return object instanceof ObjectValue other && values.equals(other.values);
    }

    @Override
    public int hashCode() {
      return values.hashCode();
    }

    @Override
    public String toString() {
      return "ObjectValue[values=" + values + "]";
    }
  }

  /** Object-like call keyword arguments, distinct because they are not JSON-renderable upstream. */
  record KeywordArgumentsValue(Map<String, Value> values) implements Value {
    public KeywordArgumentsValue {
      values = new LinkedHashMap<>(Objects.requireNonNull(values, "values"));
    }
  }

  record CallableValue(Callable callable) implements Value {
    public CallableValue {
      Objects.requireNonNull(callable);
    }

    @FunctionalInterface
    public interface Callable {
      Value invoke(List<Value> arguments, boolean hasKeywordArguments, SourceLocation location);
    }
  }
}
