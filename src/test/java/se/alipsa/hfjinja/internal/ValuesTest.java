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
  void rejectsHugeExponentsWithoutExpandingThem() {
    assertConversionFailure(new BigDecimal("1E+100000000"));
  }

  @Test
  void acceptsSubnormalNumbersUsingTheJsShortestForm() {
    assertEquals(new FloatValue(Double.MIN_VALUE), Values.fromHost(new BigDecimal("5E-324")));
    assertEquals(new FloatValue(Double.MIN_VALUE), Values.fromHost(Double.MIN_VALUE));
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

  @Test
  void reusesConvertedDagNodesInsteadOfRewalkingThem() {
    var shared = List.of("value");
    var converted = assertInstanceOf(ArrayValue.class, Values.fromHost(List.of(shared, shared)));
    assertEquals(converted.values().get(0), converted.values().get(1));
    org.junit.jupiter.api.Assertions.assertSame(converted.values().get(0), converted.values().get(1));
  }

  @Test
  void convertsDeepSharedDagWithoutExponentialWork() {
    Object graph = List.of("leaf");
    for (int depth = 0; depth < 40; depth++) {
      graph = List.of(graph, graph);
    }
    assertInstanceOf(ArrayValue.class, Values.fromHost(graph));
  }

  private static void assertConversionFailure(Object input) {
    var error = assertThrows(TemplateRenderException.class, () -> Values.fromHost(input));
    assertEquals(ErrorCategory.HOST_CONVERSION, error.category());
  }
}
