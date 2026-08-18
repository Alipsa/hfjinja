package se.alipsa.hfjinja;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
  void parsedTemplateIsImmutableBeforeInterpreterLands() {
    var template = Template.parse("literal");
    var error = assertThrows(TemplateRenderException.class, () -> template.render(Map.of()));
    assertEquals(ErrorCategory.VALUE, error.category());
  }
}
