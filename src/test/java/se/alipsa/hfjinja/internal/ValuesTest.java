package se.alipsa.hfjinja.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.TemplateRenderException;

class ValuesTest {
  @Test
  void acceptsJsonStyleNumbersWhenTheirShortestJsFormMatches() {
    assertEquals(new FloatValue(0.7d), Values.fromHost(new BigDecimal("0.7")));
    assertEquals(new IntegerValue(42d), Values.fromHost(new BigDecimal("42.0")));
  }

  @Test
  void rejectsDecimalNarrowingAndUnsafeIntegers() {
    assertConversionFailure(new BigDecimal("0.1234567890123456789"));
    assertConversionFailure(new BigDecimal("9007199254740992"));
  }

  @Test
  void distinguishesNullAndCopiesNestedValues() {
    var value = assertInstanceOf(
        ObjectValue.class, Values.fromHost(Map.of("items", java.util.Arrays.asList(1, null))));
    var array = assertInstanceOf(ArrayValue.class, value.values().get("items"));
    assertEquals(NullValue.INSTANCE, array.values().get(1));
  }

  @Test
  void rejectsCyclesAndNonStringMapKeys() {
    var cycle = new java.util.ArrayList<>();
    cycle.add(cycle);
    assertConversionFailure(cycle);
    assertConversionFailure(Map.of(1, "no"));
  }

  private static void assertConversionFailure(Object input) {
    var error = assertThrows(TemplateRenderException.class, () -> Values.fromHost(input));
    assertEquals(ErrorCategory.HOST_CONVERSION, error.category());
  }
}
