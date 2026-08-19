package se.alipsa.hfjinja;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicApiTest {
  @Test
  void sourceLocationsAreValidated() {
    assertThrows(IllegalArgumentException.class, () -> new SourceLocation(-1, 1, 1));
    assertEquals(new SourceLocation(0, 1, 1), new SourceLocation(0, 1, 1));
  }

  @Test
  void conversionCategoryCanHaveNoLocation() {
    var error = new TemplateRenderException("bad input", ErrorCategory.HOST_CONVERSION, null);
    assertEquals(ErrorCategory.HOST_CONVERSION, error.category());
    assertFalse(error.location().isPresent());
  }

  @Test
  void exceptionsWithLocationsAreSerializable() throws Exception {
    var original = new TemplateSyntaxException("bad template", new SourceLocation(3, 1, 4));
    var bytes = new ByteArrayOutputStream();
    try (var output = new ObjectOutputStream(bytes)) {
      output.writeObject(original);
    }
    TemplateSyntaxException restored;
    try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      restored = (TemplateSyntaxException) input.readObject();
    }
    assertEquals(original.getMessage(), restored.getMessage());
    assertEquals(original.category(), restored.category());
    assertEquals(original.location(), restored.location());
  }

  @Test
  void parsedTemplateIsImmutableBeforeInterpreterLands() {
    var template = Template.parse("literal");
    assertThrows(UnsupportedOperationException.class, () -> template.render(Map.of()));
  }

  @Test
  void renderOptionsAreImmutableAndRejectInvalidFunctionRegistrations() {
    var clock = Clock.fixed(Instant.parse("2025-01-02T03:04:05Z"), ZoneOffset.UTC);
    HostFunction formatter = arguments -> "formatted";
    var options = RenderOptions.builder()
        .clock(clock)
        .zoneId(ZoneOffset.UTC)
        .hostFunction("format_tool", formatter)
        .build();

    assertEquals(clock, options.clock().orElseThrow());
    assertEquals(ZoneOffset.UTC, options.zoneId().orElseThrow());
    assertEquals(formatter, options.hostFunctions().get("format_tool"));
    assertThrows(UnsupportedOperationException.class,
        () -> options.hostFunctions().put("other", formatter));
    assertThrows(IllegalArgumentException.class, () -> RenderOptions.builder()
        .hostFunction("duplicate", formatter)
        .hostFunction("duplicate", formatter)
        .build());
    assertThrows(IllegalArgumentException.class,
        () -> RenderOptions.builder().hostFunction("range", formatter).build());
  }
}
