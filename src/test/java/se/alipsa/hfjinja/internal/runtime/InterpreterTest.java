package se.alipsa.hfjinja.internal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.RenderOptions;
import se.alipsa.hfjinja.Template;
import se.alipsa.hfjinja.TemplateRenderException;
import se.alipsa.hfjinja.internal.JsFormat;

class InterpreterTest {
  @Test
  void treatsNaNAsFalsy() {
    assertFalse(Interpreter.truthy(new se.alipsa.hfjinja.internal.Value.IntegerValue(Double.NaN)));
    assertFalse(Interpreter.truthy(new se.alipsa.hfjinja.internal.Value.FloatValue(Double.NaN)));
    assertTrue(Interpreter.truthy(new se.alipsa.hfjinja.internal.Value.IntegerValue(1d)));
    assertTrue(Interpreter.truthy(new se.alipsa.hfjinja.internal.Value.FloatValue(1d)));
  }

  @Test
  void evaluatesExpressionOperatorsWithUpstreamValueSemantics() {
    assertEquals("03", Template.parse("{{ 0 and 5 }}{{ 3 or 5 }}").render(Map.of()));
    assertEquals("false", Template.parse("{{ false and raise_exception('boom') }}").render(Map.of()));
    assertEquals("true", Template.parse("{{ true or raise_exception('boom') }}").render(Map.of()));
    assertEquals("truefalse", Template.parse("{{ none == missing }}{{ none != missing }}").render(Map.of()));
    assertEquals("true", Template.parse("{{ '1' == true }}").render(Map.of()));
    assertEquals("truetruetruetrue", Template.parse("{{ 1 == true }}{{ 0 == false }}{{ true == 1 }}{{ false == 0 }}").render(Map.of()));
    assertEquals("truetruefalse", Template.parse("{{ true == true }}{{ false == false }}{{ true != true }}").render(Map.of()));
    assertEquals("truefalse", Template.parse("{{ 'abc'[5] == missing }}{{ 'abc'[5] != missing }}").render(Map.of()));
    assertEquals("3|3.0|ab|true|true", Template.parse("{{ 1 + 2 }}|{{ 6.0 / 2 }}|{{ 'a' ~ 'b' }}|{{ 'a' in 'cat' }}|{{ 'a' in {'a': 1} }}").render(Map.of()));
    assertEquals("[1, 2]", Template.parse("{{ [1] + [2] }}").render(Map.of()));
    assertEquals("[1, 2, 3]|true", Template.parse("{{ (1, 2) + [3] }}|{{ 1 in (1, 2) }}").render(Map.of()));
    assertEquals("[object Map]|1.0|[object Map]", Template.parse("{{ {'a': 1} ~ '' }}|{{ [1.0] ~ '' }}|{{ '' + {'a': 1} }}").render(Map.of()));
    assertEquals("1|1.0", Template.parse("{{ 1.0 ~ '' }}|{{ [1.0] ~ '' }}").render(Map.of()));
    assertEquals("undefined", Template.parse("{{ [none] ~ '' }}").render(Map.of()));
    var directUndefined =
        assertThrows(
            TemplateRenderException.class, () -> Template.parse("{{ 'abc'[5] ~ '' }}").render(Map.of()));
    assertEquals(ErrorCategory.UNDEFINED_OR_ACCESS, directUndefined.category());
    var nestedTuple =
        assertThrows(
            TemplateRenderException.class, () -> Template.parse("{{ [(1, 2)] ~ '' }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, nestedTuple.category());
    assertEquals("Cannot convert to JSON: TupleValue", nestedTuple.getMessage());
    assertEquals("truetruetrue", Template.parse("{{ 1 < 1.5 }}{{ 1.0 <= 1 }}{{ 1.0 in [1] }}").render(Map.of()));
    assertEquals("false", Template.parse("{{ 'x' in missing }}").render(Map.of()));
    assertEquals("true", Template.parse("{{ 'x' not in missing }}").render(Map.of()));
    var arrayNeedle =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ (1, 2) in [(1, 2)] }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, arrayNeedle.category());
    assertEquals(
        "Unknown operator \"in\" between TupleValue and ArrayValue", arrayNeedle.getMessage());
  }

  @Test
  void evaluatesSelectedFiltersAndTestsWithPinnedRuntimeSemantics() {
    assertEquals(
        "x|true|x|abc|3|1-true-x|12|1.25|fallback|{\"b\": 1}",
        Template.parse(
                "{{ missing | default('x') }}|{{ none | default('x') is none }}|"
                    + "{{ false | default('x', true) }}|{{ '  AbC  ' | trim | lower }}|"
                    + "{{ 'ABC' | lower | length }}|{{ [1, true, 'x'] | join('-') }}|"
                    + "{{ '12.9x' | int }}|{{ '1.25x' | float }}|"
                    + "{{ 'bad' | int(default='fallback') }}|{{ {'b': 1} | tojson }}")
            .render(Map.of()));
    assertEquals(
        "truetruetruetruetruetruetruetrue",
        Template.parse(
                "{{ missing is undefined }}{{ none is defined }}{{ none is none }}"
                    + "{{ true is boolean }}{{ 1 is number }}{{ 'x' is string }}"
                    + "{{ [1] is iterable }}{{ {'x': 1} is sequence }}")
            .render(Map.of()));
    assertEquals("a-b-c", Template.parse("{{ 'abc' | join('-') }}").render(Map.of()));
    assertEquals("null", Template.parse("{{ (1.0 / 0.0) | tojson }}").render(Map.of()));
    assertEquals(
        "1|1.0|1|-2|Infinity|Infinity",
        Template.parse(
                "{{ true | int }}|{{ true | float }}|{{ 1.9 | int }}|{{ (0 - 1.9) | int }}|"
                    + "{{ (1.0 / 0.0) | int }}|{{ (1.0 / 0.0) | float }}")
            .render(Map.of()));
  }

  @Test
  void categorizesFilterAndTestFailures() {
    var unknownFilter = assertThrows(
        TemplateRenderException.class, () -> Template.parse("{{ 'x' | no_such_filter }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, unknownFilter.category());
    assertTrue(unknownFilter.location().isPresent());
    var receiver = assertThrows(
        TemplateRenderException.class, () -> Template.parse("{{ 1 | lower }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, receiver.category());
    var arity = assertThrows(
        TemplateRenderException.class, () -> Template.parse("{{ 'x' | trim(1) }}").render(Map.of()));
    assertEquals(ErrorCategory.ARITY, arity.category());
    var toJsonArgument = assertThrows(
        TemplateRenderException.class, () -> Template.parse("{{ {} | tojson(indent=2) }}").render(Map.of()));
    assertEquals(ErrorCategory.ARITY, toJsonArgument.category());
    var defaultFlag = assertThrows(
        TemplateRenderException.class, () -> Template.parse("{{ missing | default('x', 1) }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, defaultFlag.category());
    var value = assertThrows(
        TemplateRenderException.class, () -> Template.parse("{{ 'x' | join(other='-') }}").render(Map.of()));
    assertEquals(ErrorCategory.VALUE, value.category());
    var unknownTest = assertThrows(
        TemplateRenderException.class, () -> Template.parse("{{ 1 is no_such_test }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, unknownTest.category());
    var deferredEquality = assertThrows(
        TemplateRenderException.class, () -> Template.parse("{{ 1 is equalto }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, deferredEquality.category());
  }

  @Test
  void distinguishesUnaryRawTruthinessAndOperatorErrors() {
    assertEquals("falsefalse", Template.parse("{{ not [] }}{{ not {} }}").render(Map.of()));
    assertEquals("falsefalse", Template.parse("{% if [] %}true{% else %}false{% endif %}{% if {} %}true{% else %}false{% endif %}").render(Map.of()));
    assertEquals("0.0|-Infinity", Template.parse("{{ 0.0 * (0.0 - 1.0) }}|{{ 1.0 / (0.0 * (0.0 - 1.0)) }}").render(Map.of()));
    assertEquals("Infinity|-Infinity", Template.parse("{{ 1.0 / 0.0 }}|{{ 1.0 / (0.0 * (0.0 - 1.0)) }}").render(Map.of()));
    var nil = assertThrows(TemplateRenderException.class, () -> Template.parse("{{ none == 0 }}").render(Map.of()));
    assertEquals(ErrorCategory.UNDEFINED_OR_ACCESS, nil.category());
    var unsupported = assertThrows(TemplateRenderException.class, () -> Template.parse("{{ true + 1 }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, unsupported.category());
  }

  @Test
  void rendersCoreLiteralsCallsAndAssignments() {
    assertEquals("12", Template.parse("{% set (a, b) = (1, 2) %}{{ a }}{{ b }}").render(Map.of()));
    assertEquals("[0, 1, 2]", Template.parse("{{ range(3) }}").render(Map.of()));
  }

  @Test
  void preservesTupleRenderQuirk() {
    var error =
        assertThrows(
            TemplateRenderException.class, () -> Template.parse("{{ (1, 2) }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, error.category());
  }

  @Test
  void rendersLoopsMembersAndAliases() {
    assertEquals(
        "123",
        Template.parse("{% for x in range(3) %}{{ loop.index }}{% endfor %}").render(Map.of()));
    assertEquals(
        "2",
        Template.parse("{% set y = x %}{% set y.a = 2 %}{{ x.a }}").render(Map.of("x", Map.of())));
    assertEquals("[0, 1, 2]", Template.parse("{% set r = range %}{{ r(3) }}").render(Map.of()));
    assertEquals(
        "EMPTY",
        Template.parse("{% for x in [1, 2] if false %}{{ x }}{% else %}EMPTY{% endfor %}")
            .render(Map.of()));
  }

  @Test
  void retainsAssignmentsAcrossIfBodiesAndExposesCompleteLoopMetadata() {
    assertEquals(
        "1", Template.parse("{% if true %}{% set x = 1 %}{% endif %}{{ x }}").render(Map.of()));
    assertEquals(
        "12",
        Template.parse(
                "{% for i in [1,2] %}{% if true %}{% set s = i %}{% endif %}{{ s }}{% endfor %}")
            .render(Map.of()));
    assertEquals(
        "-2;1-;",
        Template.parse("{% for i in [1,2] %}{{ loop.previtem }}-{{ loop.nextitem }};{% endfor %}")
            .render(Map.of()));
    assertEquals(
        "D11",
        Template.parse(
                "{% for i in [1,2,3] %}{% break %}{% else %}D{{ loop.index }}{{ i }}{% endfor %}")
            .render(Map.of()));
  }

  @Test
  void distinguishesInvalidMemberAndObjectKeyTypes() {
    assertEquals("", Template.parse("{{ 'abc'.foo }}").render(Map.of()));
    var objectProperty =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ obj[1] }}").render(Map.of("obj", Map.of("a", 1))));
    assertEquals(ErrorCategory.TYPE, objectProperty.category());
    var objectKey =
        assertThrows(
            TemplateRenderException.class, () -> Template.parse("{{ {1: 'a'} }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, objectKey.category());
    var raised =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ raise_exception(none) }}").render(Map.of()));
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
    var error =
        assertThrows(
            TemplateRenderException.class,
            () ->
                Template.parse("{% for x in range(20) %}{% break %}{% endfor %}")
                    .render(Map.of(), options));
    assertEquals(ErrorCategory.RESOURCE_LIMIT, error.category());
    assertEquals(
        "1;2;", Template.parse("{% for loop in [1,2] %}{{ loop }};{% endfor %}").render(Map.of()));
  }

  @Test
  void repeatedHostMapsDoNotAliasAfterTemplateMutation() {
    var shared = Map.of("k", 1);
    assertEquals(
        "{\"k\": 2}{\"k\": 1}",
        Template.parse("{% set a.k = 2 %}{{ a }}{{ b }}").render(Map.of("a", shared, "b", shared)));
  }

  @Test
  void rangeTreatsNoneAndUndefinedAsJavaScriptUndefined() {
    assertEquals("[0, 1, 2, 3, 4]", Template.parse("{{ range(0, 5, none) }}").render(Map.of()));
    assertEquals(
        "[0, 1, 2, 3, 4]", Template.parse("{{ range(0, 5, undefinedvar) }}").render(Map.of()));
    assertEquals("[]", Template.parse("{{ range(none, 3) }}").render(Map.of()));
    var error =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ range(0, 5, 0.0) }}").render(Map.of()));
    assertEquals(ErrorCategory.VALUE, error.category());
  }

  @Test
  void globalsIgnoreExtraArgumentsAndRenderStableTimeDigits() {
    assertEquals(
        "",
        assertThrows(
                TemplateRenderException.class,
                () -> Template.parse("{{ raise_exception() }}").render(Map.of()))
            .getMessage());
    assertEquals(
        "a",
        assertThrows(
                TemplateRenderException.class,
                () -> Template.parse("{{ raise_exception('a', 'b') }}").render(Map.of()))
            .getMessage());
    assertEquals(
        "1,2",
        assertThrows(
                TemplateRenderException.class,
                () -> Template.parse("{{ raise_exception([1, 2]) }}").render(Map.of()))
            .getMessage());
    var options =
        RenderOptions.builder()
            .clock(Clock.fixed(Instant.parse("2026-08-21T09:05:00Z"), ZoneId.of("UTC")))
            .zoneId(ZoneId.of("UTC"))
            .build();
    assertEquals(
        "2026", Template.parse("{{ strftime_now('%Y', 'ignored') }}").render(Map.of(), options));
    assertEquals(
        "a\0%", Template.parse("{{ strftime_now(fmt) }}").render(Map.of("fmt", "a\0%%"), options));
    var previous = Locale.getDefault(Locale.Category.FORMAT);
    try {
      Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-SA"));
      assertEquals(
          "2026-08-21 09:05",
          Template.parse("{{ strftime_now('%Y-%m-%d %H:%M') }}").render(Map.of(), options));
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, previous);
    }
  }

  @Test
  void matchesRuntimeExceptionStringificationAndCallEvaluationOrder() {
    assertEquals("1,2", raisedMessage("{{ raise_exception((1, 2)) }}", Map.of()));
    assertEquals("1", raisedMessage("{{ raise_exception(1.0) }}", Map.of()));
    assertEquals(
        "[object Map]", raisedMessage("{{ raise_exception(obj) }}", Map.of("obj", Map.of("a", 1))));
    assertEquals("undefined", raisedMessage("{{ raise_exception([none]) }}", Map.of()));
    assertEquals("[1, 2]", raisedMessage("{{ raise_exception([[1, 2]]) }}", Map.of()));
    assertEquals("boom", raisedMessage("{{ nofn(raise_exception('boom')) }}", Map.of()));
    assertEquals(
        "boom", raisedMessage("{{ obj[1](raise_exception('boom')) }}", Map.of("obj", Map.of())));
  }

  @Test
  void rendersNamespaceKeywordArgumentsAndRejectsCyclicValues() {
    assertEquals("1", Template.parse("{% set ns = namespace(x=1) %}{{ ns.x }}").render(Map.of()));
    assertEquals(
        "2",
        Template.parse("{% set ns = namespace(x=1) %}{% set ns.x = 2 %}{{ ns.x }}")
            .render(Map.of()));
    assertEquals(
        "a", Template.parse("{% for k in namespace(a=1) %}{{ k }}{% endfor %}").render(Map.of()));
    assertEquals(
        "`namespace` expects either zero arguments or a single object argument",
        raisedMessage("{% set ns = namespace(x, y=1) %}{{ ns.a }}", Map.of("x", Map.of("a", 9))));
    assertEquals(
        "Cannot convert to JSON: KeywordArgumentsValue",
        raisedMessage("{% set ns = namespace(a=1,b=2) %}{{ ns }}", Map.of()));
    var error =
        assertThrows(
            TemplateRenderException.class,
            () ->
                Template.parse("{% set ns = namespace() %}{% set ns.self = ns %}{{ ns }}")
                    .render(Map.of()));
    assertEquals(ErrorCategory.TYPE, error.category());
  }

  @Test
  void rangeReclassifiesIntegralFloatStarts() {
    assertEquals(
        "2;3;4;",
        Template.parse("{% for i in range(2.0, 5) %}{{ i }};{% endfor %}").render(Map.of()));
  }

  @Test
  void rangeUsesJavaScriptStepBranchingAndNumberCoercion() {
    assertEquals("[3]", Template.parse("{{ range(3, 0, 'x') }}").render(Map.of()));
    assertEquals("[0, 1, 2]", Template.parse("{{ range(0, '0x3') }}").render(Map.of()));
    assertEquals("[]", Template.parse("{{ range(0, '1d') }}").render(Map.of()));
    assertEquals("[0, 1, 2]", Template.parse("{{ range(0, '\u00a03\u00a0') }}").render(Map.of()));
    assertEquals("[0, 1, 2]", Template.parse("{{ range(0, '\u20073\u2007') }}").render(Map.of()));
    assertEquals("[]", Template.parse("{{ range(0, '\u001c3\u001c') }}").render(Map.of()));
  }

  @Test
  void rejectsPositionalArgumentsAfterKeywordsBeforeEvaluation() {
    var error =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ namespace(a=1, raise_exception('boom')) }}").render(Map.of()));
    assertEquals(ErrorCategory.SYNTAX, error.category());
    assertEquals("Positional arguments cannot follow keyword arguments", error.getMessage());
  }

  @Test
  void invalidAssignmentUsesJsonAstDiagnostic() {
    var error =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{% set [1] = 2 %}").render(Map.of()));
    assertEquals(
        "Invalid LHS inside assignment expression:"
            + " {\"type\":\"ArrayLiteral\",\"value\":[{\"type\":\"IntegerLiteral\",\"value\":1}]}",
        error.getMessage());
  }

  @Test
  void loopIterableUsesItsFreshScopeNamespaceBinding() {
    var error =
        assertThrows(
            TemplateRenderException.class,
            () ->
                Template.parse(
                        "{% set namespace = [1] %}{% for x in namespace %}{{ x }}{% endfor %}")
                    .render(Map.of()));
    assertEquals(ErrorCategory.TYPE, error.category());
  }

  @Test
  void renderBudgetsLimitStepsAndOutput() {
    var stepError =
        assertThrows(
            TemplateRenderException.class,
            () ->
                Template.parse("{{ 1 }}{{ 2 }}")
                    .render(Map.of(), RenderOptions.builder().maxSteps(1).build()));
    assertEquals(ErrorCategory.RESOURCE_LIMIT, stepError.category());
    var outputError =
        assertThrows(
            TemplateRenderException.class,
            () ->
                Template.parse("{{ 'ab' }}")
                    .render(Map.of(), RenderOptions.builder().maxOutputLength(1).build()));
    assertEquals(ErrorCategory.RESOURCE_LIMIT, outputError.category());
  }

  private static String raisedMessage(String source, Map<String, ?> context) {
    return assertThrows(TemplateRenderException.class, () -> Template.parse(source).render(context))
        .getMessage();
  }

  @Test
  void quoteEscapesUnpairedSurrogates() {
    assertEquals("\"x\\ud800y\"", JsFormat.quote("x\uD800y"));
  }

  @Test
  void outOfRangeStringIndexReturnsUndefinedString() {
    assertEquals("undefined", Template.parse("{{ 'abc'[5] }}").render(Map.of()));
    assertEquals(
        "no", Template.parse("{% if 'abc'[5] %}yes{% else %}no{% endif %}").render(Map.of()));
    assertEquals("[]", Template.parse("{{ ['abc'[5]] }}").render(Map.of()));
    assertEquals("[undefined]", Template.parse("{{ [missing] }}").render(Map.of()));
    assertEquals(
        "", Template.parse("{{ {'undefined': 1}['abc'[5]] }}").render(Map.of()));
    assertEquals("[]", Template.parse("{{ range(0, 'abc'[5]) }}").render(Map.of()));
    assertEquals("[0, 1, 2]", Template.parse("{{ range(0, 3, 'abc'[5]) }}").render(Map.of()));
    assertEquals("", raisedMessage("{{ raise_exception('abc'[5]) }}", Map.of()));
    assertEquals("{undefined: 1}", Template.parse("{{ {'abc'[5]: 1} }}").render(Map.of()));
    assertEquals(
        "1",
        Template.parse("{% set o = {'abc'[5]: 1} %}{{ o['abc'[5]] }}").render(Map.of()));
  }
}
