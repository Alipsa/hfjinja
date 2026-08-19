package se.alipsa.hfjinja.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.HostFunction;
import se.alipsa.hfjinja.RenderOptions;
import se.alipsa.hfjinja.SourceLocation;
import se.alipsa.hfjinja.TemplateRenderException;

class HostFunctionsTest {
  private static final SourceLocation CALL_LOCATION = new SourceLocation(7, 1, 8);

  @Test
  void dispatchesOnlyRegisteredFunctionsWithDefensiveInertArguments() {
    var options = RenderOptions.builder()
        .hostFunction("inspect", arguments -> {
          assertThrows(UnsupportedOperationException.class, () -> arguments.add("later"));
          Map<?, ?> nested = assertInstanceOf(Map.class, arguments.get(0));
          assertEquals(Arrays.asList("first", null), nested.get("items"));
          assertUnmodifiableMap(nested);
          List<?> items = assertInstanceOf(List.class, nested.get("items"));
          assertUnmodifiableList(items);
          return Map.of("argumentCount", arguments.size());
        })
        .build();

    var result = HostFunctions.invoke(
        "inspect",
        function(options, "inspect"),
        List.of(new ObjectValue(Map.of("items", new ArrayValue(List.of(new StringValue("first"), NullValue.INSTANCE))))),
        false,
        CALL_LOCATION);

    assertEquals(new ObjectValue(Map.of("argumentCount", new IntegerValue(1d))), result);
  }

  @Test
  void rejectsKeywordsAndUndefinedArgumentsAtTheCallLocation() {
    var options = RenderOptions.builder().hostFunction("known", arguments -> null).build();

    var keywordError = assertHostFailure(
        () -> HostFunctions.invoke("known", function(options, "known"), List.of(), true, CALL_LOCATION));
    assertEquals("Host function 'known' does not accept keyword arguments", keywordError.getMessage());

    var called = new AtomicBoolean();
    var undefinedOptions = RenderOptions.builder()
        .hostFunction("known", arguments -> { called.set(true); return null; })
        .build();
    var undefinedError = assertHostFailure(() -> HostFunctions.invoke(
        "known", function(undefinedOptions, "known"),
        List.of(new ObjectValue(Map.of("items", new ArrayValue(List.of(UndefinedValue.INSTANCE))))),
        false,
        CALL_LOCATION));
    assertEquals(
        "Host function 'known' cannot receive undefined value at argument 0.items[0]",
        undefinedError.getMessage());
    assertEquals(false, called.get());
  }

  @Test
  void wrapsHostExceptionsAndInvalidReturnValuesAtTheCallLocation() {
    var functionFailure = new IllegalStateException("broken");
    var options = RenderOptions.builder()
        .hostFunction("throws", arguments -> { throw functionFailure; })
        .hostFunction("badReturn", arguments -> 'x')
        .build();

    var thrownError = assertHostFailure(
        () -> HostFunctions.invoke("throws", function(options, "throws"), List.of(), false, CALL_LOCATION));
    assertEquals("Host function 'throws' failed", thrownError.getMessage());
    assertSame(functionFailure, thrownError.getCause());

    var returnError = assertHostFailure(
        () -> HostFunctions.invoke("badReturn", function(options, "badReturn"), List.of(), false, CALL_LOCATION));
    assertEquals("Host function 'badReturn' returned an unsupported value", returnError.getMessage());
    var conversionCause = assertInstanceOf(TemplateRenderException.class, returnError.getCause());
    assertEquals(ErrorCategory.HOST_CONVERSION, conversionCause.category());
  }

  @Test
  void preservesIntegerArgumentsAndWrapsUnexpectedConversionFailures() {
    var malformedNumber = new Number() {
      @Override public int intValue() { return 0; }
      @Override public long longValue() { return 0; }
      @Override public float floatValue() { return 0; }
      @Override public double doubleValue() { return 0; }
      @Override public String toString() { return null; }
    };
    var options = RenderOptions.builder()
        .hostFunction("identity", arguments -> {
          assertEquals(3L, arguments.get(0));
          return arguments.get(0);
        })
        .hostFunction("malformed", arguments -> malformedNumber)
        .build();

    assertEquals(new IntegerValue(3d), HostFunctions.invoke(
        "identity", function(options, "identity"), List.of(new IntegerValue(3d)), false, CALL_LOCATION));
    var malformedError = assertHostFailure(() -> HostFunctions.invoke(
        "malformed", function(options, "malformed"), List.of(), false, CALL_LOCATION));
    var conversionCause = assertInstanceOf(TemplateRenderException.class, malformedError.getCause());
    assertEquals(ErrorCategory.HOST_CONVERSION, conversionCause.category());
  }

  @Test
  void preservesAliasedAndFloatValuesAcrossHostFunctionRoundTrips() {
    var shared = new ArrayValue(List.of(new StringValue("value")));
    var options = RenderOptions.builder()
        .hostFunction("sharing", arguments -> {
          assertSame(arguments.get(0), arguments.get(1));
          return null;
        })
        .hostFunction("identity", arguments -> arguments.get(0))
        .hostFunction("float", arguments -> HostFunction.floatResult(2d))
        .build();

    HostFunctions.invoke("sharing", function(options, "sharing"), List.of(shared, shared), false, CALL_LOCATION);
    assertEquals(new FloatValue(2d), HostFunctions.invoke(
        "identity", function(options, "identity"), List.of(new FloatValue(2d)), false, CALL_LOCATION));
    assertEquals(new FloatValue(2d), HostFunctions.invoke(
        "float", function(options, "float"), List.of(), false, CALL_LOCATION));
  }

  @Test
  void exposesComputedNumbersToHostFunctions() {
    var options = RenderOptions.builder()
        .hostFunction("type", arguments -> arguments.get(0).getClass().getSimpleName())
        .build();
    assertEquals(new StringValue("Long"), HostFunctions.invoke(
        "type", function(options, "type"),
        List.of(new IntegerValue(1L << 60)), false, CALL_LOCATION));
    assertEquals(new StringValue("Double"), HostFunctions.invoke(
        "type", function(options, "type"),
        List.of(new FloatValue(Double.NaN)), false, CALL_LOCATION));
  }

  private static HostFunction function(RenderOptions options, String name) {
    return options.hostFunctions().get(name);
  }

  private static TemplateRenderException assertHostFailure(org.junit.jupiter.api.function.Executable action) {
    var error = assertThrows(TemplateRenderException.class, action);
    assertEquals(ErrorCategory.HOST_FUNCTION, error.category());
    assertEquals(CALL_LOCATION, error.location().orElseThrow());
    return error;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void assertUnmodifiableMap(Map<?, ?> map) {
    assertThrows(UnsupportedOperationException.class, () -> ((Map) map).put("other", 1d));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void assertUnmodifiableList(List<?> list) {
    assertThrows(UnsupportedOperationException.class, () -> ((List) list).add("later"));
  }
}
