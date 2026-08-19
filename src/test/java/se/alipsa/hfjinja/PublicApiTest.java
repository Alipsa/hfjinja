package se.alipsa.hfjinja;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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
    assertEquals("literal", template.source());
    assertThrows(UnsupportedOperationException.class, () -> template.render(Map.of()));
  }
}
