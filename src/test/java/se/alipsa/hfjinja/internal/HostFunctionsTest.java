package se.alipsa.hfjinja.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.alipsa.hfjinja.ErrorCategory;
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
        options,
        "inspect",
        List.of(new ObjectValue(Map.of("items", new ArrayValue(List.of(new StringValue("first"), NullValue.INSTANCE))))),
        false,
        CALL_LOCATION);

    assertEquals(new ObjectValue(Map.of("argumentCount", new IntegerValue(1d))), result);
  }

  @Test
  void rejectsKeywordsAndUnknownFunctionsAtTheCallLocation() {
    var options = RenderOptions.builder().hostFunction("known", arguments -> null).build();

    var keywordError = assertHostFailure(
        () -> HostFunctions.invoke(options, "known", List.of(), true, CALL_LOCATION));
    assertEquals("Host function 'known' does not accept keyword arguments", keywordError.getMessage());

    var unknownError = assertHostFailure(
        () -> HostFunctions.invoke(options, "missing", List.of(), true, CALL_LOCATION));
    assertEquals("Unknown host function: missing", unknownError.getMessage());
  }

  @Test
  void wrapsHostExceptionsAndInvalidReturnValuesAtTheCallLocation() {
    var functionFailure = new IllegalStateException("broken");
    var options = RenderOptions.builder()
        .hostFunction("throws", arguments -> { throw functionFailure; })
        .hostFunction("badReturn", arguments -> 'x')
        .build();

    var thrownError = assertHostFailure(
        () -> HostFunctions.invoke(options, "throws", List.of(), false, CALL_LOCATION));
    assertEquals("Host function 'throws' failed", thrownError.getMessage());
    assertSame(functionFailure, thrownError.getCause());

    var returnError = assertHostFailure(
        () -> HostFunctions.invoke(options, "badReturn", List.of(), false, CALL_LOCATION));
    assertEquals("Host function 'badReturn' returned an unsupported value", returnError.getMessage());
    var conversionCause = assertInstanceOf(TemplateRenderException.class, returnError.getCause());
    assertEquals(ErrorCategory.HOST_CONVERSION, conversionCause.category());
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
