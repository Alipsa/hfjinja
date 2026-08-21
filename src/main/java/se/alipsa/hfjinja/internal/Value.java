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
    permits Value.UndefinedValue, Value.NullValue, Value.BooleanValue, Value.IntegerValue,
        Value.FloatValue, Value.StringValue, Value.ArrayValue, Value.TupleValue,
        Value.ObjectValue, Value.CallableValue {
  enum UndefinedValue implements Value { INSTANCE }
  enum NullValue implements Value { INSTANCE }
  record BooleanValue(boolean value) implements Value {}
  record IntegerValue(double value) implements Value {}
  record FloatValue(double value) implements Value {}
  record StringValue(String value) implements Value {}
  record ArrayValue(List<Value> values) implements Value {
    public ArrayValue { values = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(values, "values"))); }
  }
  record TupleValue(List<Value> values) implements Value {
    public TupleValue { values = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(values, "values"))); }
  }
  /** Mutable by design for template member assignment; never use as a hash-based key. */
  record ObjectValue(Map<String, Value> values) implements Value {
    public ObjectValue { values = new LinkedHashMap<>(Objects.requireNonNull(values, "values")); }
  }
  record CallableValue(Callable callable) implements Value {
    public CallableValue { Objects.requireNonNull(callable); }
    @FunctionalInterface
    public interface Callable {
      Value invoke(List<Value> arguments, boolean hasKeywordArguments, SourceLocation location);
    }
  }
}
