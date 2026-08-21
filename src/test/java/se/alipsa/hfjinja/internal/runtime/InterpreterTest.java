package se.alipsa.hfjinja.internal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.RenderOptions;
import se.alipsa.hfjinja.Template;
import se.alipsa.hfjinja.TemplateRenderException;
import se.alipsa.hfjinja.internal.JsFormat;

class InterpreterTest {
  @Test
  void rendersCoreLiteralsCallsAndAssignments() {
    assertEquals("12", Template.parse("{% set (a, b) = (1, 2) %}{{ a }}{{ b }}").render(Map.of()));
    assertEquals("[0, 1, 2]", Template.parse("{{ range(3) }}").render(Map.of()));
  }

  @Test
  void preservesTupleRenderQuirk() {
    var error = assertThrows(TemplateRenderException.class, () -> Template.parse("{{ (1, 2) }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, error.category());
  }

  @Test
  void rendersLoopsMembersAndAliases() {
    assertEquals("123", Template.parse("{% for x in range(3) %}{{ loop.index }}{% endfor %}").render(Map.of()));
    assertEquals("2", Template.parse("{% set y = x %}{% set y.a = 2 %}{{ x.a }}").render(Map.of("x", Map.of())));
    assertEquals("[0, 1, 2]", Template.parse("{% set r = range %}{{ r(3) }}").render(Map.of()));
    assertEquals("EMPTY", Template.parse("{% for x in [1, 2] if false %}{{ x }}{% else %}EMPTY{% endfor %}").render(Map.of()));
  }

  @Test
  void retainsAssignmentsAcrossIfBodiesAndExposesCompleteLoopMetadata() {
    assertEquals("1", Template.parse("{% if true %}{% set x = 1 %}{% endif %}{{ x }}").render(Map.of()));
    assertEquals("12", Template.parse("{% for i in [1,2] %}{% if true %}{% set s = i %}{% endif %}{{ s }}{% endfor %}").render(Map.of()));
    assertEquals("-2;1-;", Template.parse("{% for i in [1,2] %}{{ loop.previtem }}-{{ loop.nextitem }};{% endfor %}").render(Map.of()));
    assertEquals("D11", Template.parse("{% for i in [1,2,3] %}{% break %}{% else %}D{{ loop.index }}{{ i }}{% endfor %}").render(Map.of()));
  }

  @Test
  void distinguishesInvalidMemberAndObjectKeyTypes() {
    assertEquals("", Template.parse("{{ 'abc'.foo }}").render(Map.of()));
    var objectProperty = assertThrows(TemplateRenderException.class, () -> Template.parse("{{ obj[1] }}").render(Map.of("obj", Map.of("a", 1))));
    assertEquals(ErrorCategory.TYPE, objectProperty.category());
    var objectKey = assertThrows(TemplateRenderException.class, () -> Template.parse("{{ {1: 'a'} }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, objectKey.category());
    var raised = assertThrows(TemplateRenderException.class, () -> Template.parse("{{ raise_exception(none) }}").render(Map.of()));
    assertEquals(ErrorCategory.EXPLICIT_RAISE, raised.category());
  }

  @Test
  void rangeMatchesJavaScriptMissingAndCoercedArguments() {
    assertEquals("[]", Template.parse("{{ range() }}").render(Map.of()));
    assertEquals("[\"3\"]", Template.parse("{{ range('3', 5, 1, 99) }}").render(Map.of()));
  }

  @Test
  void rangeIsBoundedBeforeItAllocatesAndLoopNamesCanShadowMetadata() {
    var options = RenderOptions.builder().maxLoopIterations(10).build();
    var error = assertThrows(TemplateRenderException.class, () -> Template.parse("{% for x in range(20) %}{% break %}{% endfor %}").render(Map.of(), options));
    assertEquals(ErrorCategory.RESOURCE_LIMIT, error.category());
    assertEquals("1;2;", Template.parse("{% for loop in [1,2] %}{{ loop }};{% endfor %}").render(Map.of()));
  }

  @Test
  void repeatedHostMapsDoNotAliasAfterTemplateMutation() {
    var shared = Map.of("k", 1);
    assertEquals("{\"k\": 2}{\"k\": 1}", Template.parse("{% set a.k = 2 %}{{ a }}{{ b }}").render(Map.of("a", shared, "b", shared)));
  }

  @Test
  void rangeTreatsNoneAndUndefinedAsJavaScriptUndefined() {
    assertEquals("[0, 1, 2, 3, 4]", Template.parse("{{ range(0, 5, none) }}").render(Map.of()));
    assertEquals("[0, 1, 2, 3, 4]", Template.parse("{{ range(0, 5, undefinedvar) }}").render(Map.of()));
    assertEquals("[]", Template.parse("{{ range(none, 3) }}").render(Map.of()));
    var error = assertThrows(TemplateRenderException.class, () -> Template.parse("{{ range(0, 5, 0.0) }}").render(Map.of()));
    assertEquals(ErrorCategory.VALUE, error.category());
  }

  @Test
  void globalsIgnoreExtraArgumentsAndRenderStableTimeDigits() {
    assertEquals("", assertThrows(TemplateRenderException.class, () -> Template.parse("{{ raise_exception() }}").render(Map.of())).getMessage());
    assertEquals("a", assertThrows(TemplateRenderException.class, () -> Template.parse("{{ raise_exception('a', 'b') }}").render(Map.of())).getMessage());
    assertEquals("1,2", assertThrows(TemplateRenderException.class, () -> Template.parse("{{ raise_exception([1, 2]) }}").render(Map.of())).getMessage());
    var options = RenderOptions.builder().clock(Clock.fixed(Instant.parse("2026-08-21T09:05:00Z"), ZoneId.of("UTC"))).zoneId(ZoneId.of("UTC")).build();
    assertEquals("2026", Template.parse("{{ strftime_now('%Y', 'ignored') }}").render(Map.of(), options));
    assertEquals("a\0%", Template.parse("{{ strftime_now(fmt) }}").render(Map.of("fmt", "a\0%%"), options));
    var previous = Locale.getDefault(Locale.Category.FORMAT);
    try {
      Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-SA"));
      assertEquals("2026-08-21 09:05", Template.parse("{{ strftime_now('%Y-%m-%d %H:%M') }}").render(Map.of(), options));
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, previous);
    }
  }

  @Test
  void quoteEscapesUnpairedSurrogates() {
    assertEquals("\"x\\ud800y\"", JsFormat.quote("x\uD800y"));
  }
}
