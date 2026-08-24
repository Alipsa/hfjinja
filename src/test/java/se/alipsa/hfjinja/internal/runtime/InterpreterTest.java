package se.alipsa.hfjinja.internal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.RenderOptions;
import se.alipsa.hfjinja.SourceLocation;
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
  void evaluatesTernaryExpressionsWithPinnedRuntimeTruthiness() {
    assertEquals(
        "yes|no|no|no|no|no|no|no|no|no|yes|yes|yes|yes|yes|yes|yes|yes",
        Template.parse(
                "{{ 'yes' if true else 'no' }}|{{ 'yes' if false else 'no' }}|"
                    + "{{ 'yes' if missing else 'no' }}|{{ 'yes' if none else 'no' }}|"
                    + "{{ 'yes' if '' else 'no' }}|{{ 'yes' if 0 else 'no' }}|"
                    + "{{ 'yes' if 0.0 else 'no' }}|{{ 'yes' if [] else 'no' }}|"
                    + "{{ 'yes' if {} else 'no' }}|{{ 'yes' if (0.0 / 0.0) else 'no' }}|"
                    + "{{ 'yes' if 'x' else 'no' }}|{{ 'yes' if 1 else 'no' }}|"
                    + "{{ 'yes' if 1.0 else 'no' }}|{{ 'yes' if [1] else 'no' }}|"
                    + "{{ 'yes' if {'x': 1} else 'no' }}|{{ 'yes' if (1, 2) else 'no' }}|"
                    + "{{ 'yes' if namespace(a=1) else 'no' }}|{{ 'yes' if range else 'no' }}")
            .render(Map.of()));
    assertEquals(
        "b", Template.parse("{{ 'a' if false else ('b' if true else 'c') }}").render(Map.of()));
    assertEquals(
        "safe",
        Template.parse("{{ raise_exception('boom') if false else 'safe' }}").render(Map.of()));
    var selectedBranch =
        assertThrows(
            TemplateRenderException.class,
            () ->
                Template.parse("{{ raise_exception('boom') if true else 'safe' }}")
                    .render(Map.of()));
    assertEquals(ErrorCategory.EXPLICIT_RAISE, selectedBranch.category());
    assertEquals(new SourceLocation(3, 1, 4), selectedBranch.location().orElseThrow());
  }

  @Test
  void evaluatesSequenceSlicesWithPinnedRuntimeSemantics() {
    assertEquals(
        "[1, 3]|[1, 2, 3]|[]|[]|[]||[2, 1]|😀B|olleh",
        Template.parse(
                "{{ [0,1,2,3,4][1:4:2] }}|{{ [0,1,2,3,4][-4:-1] }}|"
                    + "{{ [0,1,2][3000000000:] }}|{{ [0,1][::0] }}|{{ [][:] }}|"
                    + "{{ ''[:] }}|{{ (1,2)[::-1] }}|{{ 'A😀BC'[1:3] }}|"
                    + "{{ 'hello'[::-1] }}")
            .render(Map.of()));
    assertEquals(
        "[0, 1, 2]|[2]|[]",
        Template.parse("{{ [0,1,2][-3000000000:] }}|{{ [0,1,2][(1 + 1):] }}|{{ [][:] }}")
            .render(Map.of()));
    assertEquals("false", Template.parse("{{ (1,2)[:] is string }}").render(Map.of()));

    var receiver =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ {'a': 1}[:] }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, receiver.category());
    assertEquals(new SourceLocation(3, 1, 4), receiver.location().orElseThrow());
    assertEquals("Slice object must be an array or string", receiver.getMessage());
    var receiverBeforeBounds =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ {'a': 1}[missing + 1:] }}").render(Map.of()));
    assertEquals("Slice object must be an array or string", receiverBeforeBounds.getMessage());

    var component =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ [1]['x':] }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, component.category());
    assertEquals(new SourceLocation(7, 1, 8), component.location().orElseThrow());
    assertEquals("Slice start must be numeric or undefined", component.getMessage());
    assertEquals(
        "Slice stop must be numeric or undefined",
        assertThrows(
                TemplateRenderException.class,
                () -> Template.parse("{{ [1][:'x'] }}").render(Map.of()))
            .getMessage());
    assertEquals(
        "Slice step must be numeric or undefined",
        assertThrows(
                TemplateRenderException.class,
                () -> Template.parse("{{ [1][::true] }}").render(Map.of()))
            .getMessage());
    assertEquals(
        "Slice start must be numeric or undefined",
        assertThrows(
                TemplateRenderException.class,
                () -> Template.parse("{{ [1][1.0:] }}").render(Map.of()))
            .getMessage());
    assertEquals(
        "Slice start must be numeric or undefined",
        assertThrows(
                TemplateRenderException.class,
                () -> Template.parse("{{ [0,1,2][(6 / 2):] }}").render(Map.of()))
            .getMessage());

    var evaluatedBeforeValidation =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ [0,1]['a': missing + 1] }}").render(Map.of()));
    assertEquals(
        "Cannot perform operation + on undefined values", evaluatedBeforeValidation.getMessage());
    assertEquals(new SourceLocation(14, 1, 15), evaluatedBeforeValidation.location().orElseThrow());

    // Upstream throws TypeError: undefined is not iterable (cannot read property
    // Symbol(Symbol.iterator)).
    assertEquals("", Template.parse("{{ 'abc'[9][0:1] }}").render(Map.of()));
    var undefinedProperty =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ 'abc'[9][missing + 1] }}").render(Map.of()));
    assertEquals("Cannot perform operation + on undefined values", undefinedProperty.getMessage());
  }

  @Test
  void evaluatesExpressionOperatorsWithUpstreamValueSemantics() {
    assertEquals("03", Template.parse("{{ 0 and 5 }}{{ 3 or 5 }}").render(Map.of()));
    assertEquals(
        "false", Template.parse("{{ false and raise_exception('boom') }}").render(Map.of()));
    assertEquals("true", Template.parse("{{ true or raise_exception('boom') }}").render(Map.of()));
    assertEquals(
        "truefalse", Template.parse("{{ none == missing }}{{ none != missing }}").render(Map.of()));
    assertEquals(
        "falsetruefalsetrue",
        Template.parse("{{ none == 1 }}{{ none != 1 }}{{ missing == 1 }}{{ missing != 1 }}")
            .render(Map.of()));
    assertEquals(
        "Cannot perform operation on null values",
        assertThrows(
                TemplateRenderException.class,
                () -> Template.parse("{{ 1 + none }}").render(Map.of()))
            .getMessage());
    assertEquals("true", Template.parse("{{ '1' == true }}").render(Map.of()));
    assertEquals(
        "truetruetruetrue",
        Template.parse("{{ 1 == true }}{{ 0 == false }}{{ true == 1 }}{{ false == 0 }}")
            .render(Map.of()));
    assertEquals(
        "truetruefalse",
        Template.parse("{{ true == true }}{{ false == false }}{{ true != true }}")
            .render(Map.of()));
    assertEquals(
        "truefalse",
        Template.parse("{{ 'abc'[5] == missing }}{{ 'abc'[5] != missing }}").render(Map.of()));
    assertEquals(
        "3|3.0|ab|true|true",
        Template.parse(
                "{{ 1 + 2 }}|{{ 6.0 / 2 }}|{{ 'a' ~ 'b' }}|{{ 'a' in 'cat' }}|{{ 'a' in {'a': 1} }}")
            .render(Map.of()));
    assertEquals("[1, 2]", Template.parse("{{ [1] + [2] }}").render(Map.of()));
    assertEquals(
        "[1, 2, 3]|true", Template.parse("{{ (1, 2) + [3] }}|{{ 1 in (1, 2) }}").render(Map.of()));
    assertEquals(
        "[object Map]|1.0|[object Map]",
        Template.parse("{{ {'a': 1} ~ '' }}|{{ [1.0] ~ '' }}|{{ '' + {'a': 1} }}")
            .render(Map.of()));
    assertEquals("1|1.0", Template.parse("{{ 1.0 ~ '' }}|{{ [1.0] ~ '' }}").render(Map.of()));
    assertEquals("undefined", Template.parse("{{ [none] ~ '' }}").render(Map.of()));
    var directUndefined =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ 'abc'[5] ~ '' }}").render(Map.of()));
    assertEquals(ErrorCategory.UNDEFINED_OR_ACCESS, directUndefined.category());
    var nestedTuple =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ [(1, 2)] ~ '' }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, nestedTuple.category());
    assertEquals("Cannot convert to JSON: TupleValue", nestedTuple.getMessage());
    assertEquals(
        "truetruetrue",
        Template.parse("{{ 1 < 1.5 }}{{ 1.0 <= 1 }}{{ 1.0 in [1] }}").render(Map.of()));
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
        "truetruetruetruetruetruetruetruefalse",
        Template.parse(
                "{{ missing is undefined }}{{ none is defined }}{{ none is none }}"
                    + "{{ true is boolean }}{{ 1 is number }}{{ 'x' is string }}"
                    + "{{ [1] is iterable }}{{ {'x': 1} is sequence }}{{ (1, 2) is iterable }}")
            .render(Map.of()));
    assertEquals(
        "fallback|true|false",
        Template.parse(
                "{{ 'abc'[5] | default('fallback') }}|{{ 'abc'[5] is undefined }}|"
                    + "{{ 'abc'[5] is defined }}")
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
    var unknownFilter =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ 'x' | no_such_filter }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, unknownFilter.category());
    assertTrue(unknownFilter.location().isPresent());
    var receiver =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ 1 | lower }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, receiver.category());
    var arity =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ 'x' | trim(1) }}").render(Map.of()));
    assertEquals(ErrorCategory.ARITY, arity.category());
    var toJsonArgument =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ {} | tojson(indent=2) }}").render(Map.of()));
    assertEquals(ErrorCategory.ARITY, toJsonArgument.category());
    var defaultFlag =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ missing | default('x', 1) }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, defaultFlag.category());
    var defaultIdentifier =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ 'x' | default }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, defaultIdentifier.category());
    var value =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ 'x' | join(other='-') }}").render(Map.of()));
    assertEquals(ErrorCategory.VALUE, value.category());
    var unknownTest =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ 1 is no_such_test }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, unknownTest.category());
    var deferredEquality =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ 1 is equalto }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, deferredEquality.category());
  }

  @Test
  void pinsFilterEdgeCasesAgainstNodeOracle() {
    assertEquals("abc", Template.parse("{{ '\u00a0abc\u00a0' | trim }}").render(Map.of()));
    assertEquals(
        "Infinity|Infinity|0.0",
        Template.parse("{{ 'Infinity' | float }}|{{ 'Infinityx' | float }}|{{ 'NaN' | float }}")
            .render(Map.of()));
    assertEquals("1,2-3,4", Template.parse("{{ [[1,2], [3,4]] | join('-') }}").render(Map.of()));
    assertEquals(
        "1--x-", Template.parse("{{ [1, missing, 'x', none] | join('-') }}").render(Map.of()));
    assertEquals(
        "null|[null]|{\"a\": null}",
        Template.parse(
                "{{ missing | tojson }}|{{ [missing] | tojson }}|"
                    + "{{ {'a': missing} | tojson }}")
            .render(Map.of()));
  }

  @Test
  void distinguishesUnaryRawTruthinessAndOperatorErrors() {
    assertEquals("falsefalse", Template.parse("{{ not [] }}{{ not {} }}").render(Map.of()));
    assertEquals(
        "falsefalse",
        Template.parse(
                "{% if [] %}true{% else %}false{% endif %}{% if {} %}true{% else %}false{% endif %}")
            .render(Map.of()));
    assertEquals(
        "0.0|-Infinity",
        Template.parse("{{ 0.0 * (0.0 - 1.0) }}|{{ 1.0 / (0.0 * (0.0 - 1.0)) }}").render(Map.of()));
    assertEquals(
        "Infinity|-Infinity",
        Template.parse("{{ 1.0 / 0.0 }}|{{ 1.0 / (0.0 * (0.0 - 1.0)) }}").render(Map.of()));
    assertEquals("false", Template.parse("{{ none == 0 }}").render(Map.of()));
    var unsupported =
        assertThrows(
            TemplateRenderException.class, () -> Template.parse("{{ true + 1 }}").render(Map.of()));
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
    assertEquals("", Template.parse("{{ {'undefined': 1}['abc'[5]] }}").render(Map.of()));
    assertEquals("[]", Template.parse("{{ range(0, 'abc'[5]) }}").render(Map.of()));
    assertEquals("[0, 1, 2]", Template.parse("{{ range(0, 3, 'abc'[5]) }}").render(Map.of()));
    assertEquals("", raisedMessage("{{ raise_exception('abc'[5]) }}", Map.of()));
    assertEquals("{undefined: 1}", Template.parse("{{ {'abc'[5]: 1} }}").render(Map.of()));
    assertEquals(
        "1", Template.parse("{% set o = {'abc'[5]: 1} %}{{ o['abc'[5]] }}").render(Map.of()));
  }

  @Test
  void spreadExpandsArraysAndTuplesAsPositionalCallArguments() {
    assertEquals("[1, 2, 3]", Template.parse("{{ range(*[1,4]) }}").render(Map.of()));
    assertEquals("[1, 2, 3]", Template.parse("{{ range(*(1,4)) }}").render(Map.of()));
  }

  @Test
  void spreadExpandsFilterArguments() {
    assertEquals("1-2", Template.parse("{{ [1,2] | join(*['-']) }}").render(Map.of()));
  }

  @Test
  void spreadExpandsExactlyOneLevel() {
    assertEquals("[]", Template.parse("{{ range(*[[1,4]]) }}").render(Map.of()));
  }

  @Test
  void spreadArgumentsPreserveUpstreamOrderingSemantics() {
    assertEquals("[1]", Template.parse("{{ range(*[1,4], 9) }}").render(Map.of()));
    assertEquals("[1]", Template.parse("{{ range(*[1,4], *[9]) }}").render(Map.of()));
    assertEquals("[]", Template.parse("{{ range(1, stop=4, *[2]) }}").render(Map.of()));
  }

  @Test
  void spreadAfterFilterKeywordBypassesPositionalAfterKeywordRejection() {
    assertEquals(
        "1+2", Template.parse("{{ [1,2] | join(separator='-', *['+']) }}").render(Map.of()));
  }

  @Test
  void filterArgumentsStillRejectOrdinaryPositionalAfterKeyword() {
    var error =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ 1 | int(a=1, 2) }}").render(Map.of()));
    assertEquals(ErrorCategory.SYNTAX, error.category());
    assertEquals("Positional arguments cannot follow keyword arguments", error.getMessage());
  }

  @Test
  void spreadArgumentEvaluationPrecedesCalleeValidation() {
    var validCallee =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ range(*[raise_exception('boom')]) }}").render(Map.of()));
    assertEquals(ErrorCategory.EXPLICIT_RAISE, validCallee.category());
    var invalidCallee =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ nofn(*[raise_exception('boom')]) }}").render(Map.of()));
    assertEquals(ErrorCategory.EXPLICIT_RAISE, invalidCallee.category());
  }

  @Test
  void spreadRejectsNonArrayNonTupleReceiversWithLocatedTypeError() {
    var error =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ range(*'14') }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, error.category());
    assertEquals("Cannot unpack non-iterable type: StringValue", error.getMessage());
    assertEquals(new SourceLocation(9, 1, 10), error.location().orElseThrow());

    assertSpreadReceiverTypeError("{{ range(*{'a':1}) }}", "ObjectValue");
    assertSpreadReceiverTypeError("{{ range(*none) }}", "NullValue");
    assertSpreadReceiverTypeError("{{ range(*missing) }}", "UndefinedValue");
  }

  private static void assertSpreadReceiverTypeError(String source, String valueType) {
    var error =
        assertThrows(TemplateRenderException.class, () -> Template.parse(source).render(Map.of()));
    assertEquals(ErrorCategory.TYPE, error.category());
    assertEquals("Cannot unpack non-iterable type: " + valueType, error.getMessage());
  }

  @Test
  void unknownFilterKeywordReportsFirstKeyInSourceOrder() {
    // z, a, m is deliberately not alphabetical: under java.util.HashMap these three short string
    // keys iterate as [a, z, m], reporting "a" first instead of the source-order "z" — so this
    // assertion fails if the implementation regresses from an insertion-ordered map to a HashMap.
    // (a, b, c would not catch that regression: HashMap happens to iterate that set in the same
    // order it was inserted.)
    assertEquals(
        "Unknown `int` filter argument: z",
        raisedMessage("{{ 1 | int(z=1, a=2, m=3) }}", Map.of()));
  }

  @Test
  void interpreterSourceContainsNoMapCopyOfSubstring() throws Exception {
    var source =
        Files.readString(
            Path.of("src/main/java/se/alipsa/hfjinja/internal/runtime/Interpreter.java"));
    assertFalse(source.contains("Map.copyOf"));
  }

  @Test
  void filterDispatchOrder_isKnownDivergenceFromUpstream() {
    var error =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ 5 | join(*none) }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, error.category());
    assertEquals("Cannot unpack non-iterable type: NullValue", error.getMessage());
  }

  @Test
  void joinArityCap_isKnownDivergenceFromUpstream() {
    var error =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ [1,2] | join(*['-','+']) }}").render(Map.of()));
    assertEquals(ErrorCategory.ARITY, error.category());
    assertEquals("`join` filter accepts at most one argument", error.getMessage());
  }

  @Test
  void defaultArityCap_isKnownDivergenceFromUpstream() {
    var error =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ missing | default(*['a', true, 'c']) }}").render(Map.of()));
    assertEquals(ErrorCategory.ARITY, error.category());
    assertEquals("`default` filter accepts at most two arguments", error.getMessage());
  }

  @Test
  void intArityCap_isKnownDivergenceFromUpstream() {
    var error =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ 'x' | int(*[1,2,3]) }}").render(Map.of()));
    assertEquals(ErrorCategory.ARITY, error.category());
    assertEquals("`int` filter accepts at most one argument", error.getMessage());
  }

  @Test
  void floatArityCap_isKnownDivergenceFromUpstream() {
    var error =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ 'x' | float(*[1,2,3]) }}").render(Map.of()));
    assertEquals(ErrorCategory.ARITY, error.category());
    assertEquals("`float` filter accepts at most one argument", error.getMessage());
  }

  @Test
  void unknownFilterKeyword_isKnownDivergenceFromUpstream() {
    var error =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ 1 | int(a=1, b=2, c=3) }}").render(Map.of()));
    assertEquals(ErrorCategory.VALUE, error.category());
  }

  @Test
  void tojsonArguments_isKnownDivergenceFromUpstream() {
    var error =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{{ [1,2] | tojson(*[9]) }}").render(Map.of()));
    assertEquals(ErrorCategory.ARITY, error.category());
    assertEquals("`tojson` filter accepts no arguments", error.getMessage());
  }

  @Test
  void macroBindsPositionalAndKeywordArgumentsWithDefaults() {
    assertEquals(
        "5-1",
        Template.parse("{% macro f(a,b=1) %}{{ a }}-{{ b }}{% endmacro %}{{ f(5) }}")
            .render(Map.of()));
  }

  @Test
  void macroMissingPositionalArgumentIsArity() {
    var error =
        assertThrows(
            TemplateRenderException.class,
            () ->
                Template.parse("{% macro f(a) %}{{ a }}{% endmacro %}{{ f() }}").render(Map.of()));
    assertEquals(ErrorCategory.ARITY, error.category());
    assertEquals("Missing positional argument: a", error.getMessage());
  }

  @Test
  void macroIgnoresExtraPositionalArgumentsAndUnknownKeywords() {
    assertEquals(
        "1",
        Template.parse("{% macro f(a) %}{{ a }}{% endmacro %}{{ f(1,2,3) }}").render(Map.of()));
    assertEquals(
        "1",
        Template.parse("{% macro f(a=1) %}{{ a }}{% endmacro %}{{ f(z=9) }}").render(Map.of()));
  }

  @Test
  void macroPositionalArgumentWinsOverKeyword() {
    assertEquals(
        "5",
        Template.parse("{% macro f(a=1) %}{{ a }}{% endmacro %}{{ f(5, a=9) }}").render(Map.of()));
  }

  @Test
  void macroDefaultArgumentsEvaluateAtCallTimeInCallerScope() {
    assertEquals(
        "Hi shadowed",
        Template.parse(
                "{% set x='outer' %}{% macro g(name=x) %}Hi {{ name }}{% endmacro %}"
                    + "{% set x='shadowed' %}{{ g() }}")
            .render(Map.of()));
  }

  @Test
  void macroLaterDefaultArgumentSeesEarlierBoundParameter() {
    assertEquals(
        "1-1",
        Template.parse("{% macro f(a,b=a) %}{{ a }}-{{ b }}{% endmacro %}{{ f(1) }}")
            .render(Map.of()));
  }

  @Test
  void macroSpreadParameterIsSyntaxError() {
    var error =
        assertThrows(
            TemplateRenderException.class,
            () ->
                Template.parse("{% macro f(*x) %}{{ x }}{% endmacro %}{{ f(1,2) }}")
                    .render(Map.of()));
    assertEquals(ErrorCategory.SYNTAX, error.category());
    assertEquals("Unknown argument type: SpreadExpression", error.getMessage());
  }

  @Test
  void macroBareBreak_isKnownDivergenceFromUpstream() {
    var error =
        assertThrows(
            TemplateRenderException.class,
            () ->
                Template.parse("{% macro f() %}{% break %}{% endmacro %}{{ f() }}")
                    .render(Map.of()));
    assertEquals(ErrorCategory.SYNTAX, error.category());
    assertEquals("break or continue outside a for loop", error.getMessage());
  }

  @Test
  void macroRedeclarationDoesNotThrow() {
    assertEquals(
        "2",
        Template.parse("{% macro f() %}1{% endmacro %}{% macro f() %}2{% endmacro %}{{ f() }}")
            .render(Map.of()));
  }

  @Test
  void callBlockInvokesCallerAndBindsCallerArguments() {
    assertEquals(
        "[body]",
        Template.parse(
                "{% macro f() %}[{{ caller() }}]{% endmacro %}{% call f() %}body{% endcall %}")
            .render(Map.of()));
    assertEquals(
        "[1-2]",
        Template.parse(
                "{% macro f() %}[{{ caller(1,2) }}]{% endmacro %}"
                    + "{% call(a,b) f() %}{{ a }}-{{ b }}{% endcall %}")
            .render(Map.of()));
  }

  @Test
  void callBlockBodySeesMacroLocalStateSetBeforeCallerRuns() {
    assertEquals(
        "[x=5]",
        Template.parse(
                "{% macro f() %}{% set x=5 %}[{{ caller() }}]{% endmacro %}"
                    + "{% call f() %}x={{ x }}{% endcall %}")
            .render(Map.of()));
  }

  @Test
  void callBlockNonIdentifierCallerParameterIsSyntaxError() {
    var error =
        assertThrows(
            TemplateRenderException.class,
            () ->
                Template.parse(
                        "{% macro f() %}{{ caller(1) }}{% endmacro %}"
                            + "{% call(a.b) f() %}{{ a }}{% endcall %}")
                    .render(Map.of()));
    assertEquals(ErrorCategory.SYNTAX, error.category());
    assertEquals(
        "Caller parameter must be an identifier, got MemberExpression", error.getMessage());
  }

  @Test
  void callerOutsideCallBlockIsUnboundIdentifier() {
    var error =
        assertThrows(
            TemplateRenderException.class, () -> Template.parse("{{ caller() }}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, error.category());
    assertEquals(
        "Cannot call something that is not a function: got UndefinedValue", error.getMessage());
  }

  @Test
  void callBlockBareBreak_isKnownDivergenceFromUpstream() {
    var error =
        assertThrows(
            TemplateRenderException.class,
            () ->
                Template.parse(
                        "{% macro f() %}[{{ caller() }}]{% endmacro %}"
                            + "{% call f() %}{% break %}{% endcall %}")
                    .render(Map.of()));
    assertEquals(ErrorCategory.SYNTAX, error.category());
    assertEquals("break or continue outside a for loop", error.getMessage());
  }

  @Test
  void callBlockPushesKeywordArgumentsBagUnconditionallyForNonMacroCallees() {
    assertEquals("[]", Template.parse("{% call range(3) %}x{% endcall %}").render(Map.of()));
    var error =
        assertThrows(
            TemplateRenderException.class,
            () -> Template.parse("{% call namespace() %}x{% endcall %}").render(Map.of()));
    assertEquals(ErrorCategory.TYPE, error.category());
  }

  @Test
  void callBlockHostFunctionDoesNotObserveExtraTrailingEmptyMapArgument() {
    // The unconditional keyword-arguments-bag push in evaluateCallStatement (needed to match the
    // oracle for range()/namespace()/macro callees) would otherwise leak into host functions as
    // an extra trailing empty map; HostFunctions.invoke strips it since host functions have no
    // such upstream contract and no template-visible way to construct one on their own.
    var seen = new java.util.ArrayList<java.util.List<Object>>();
    var options =
        se.alipsa.hfjinja.RenderOptions.builder()
            .hostFunction(
                "record",
                arguments -> {
                  seen.add(arguments);
                  return "";
                })
            .build();
    Template.parse("{% call record(1) %}x{% endcall %}").render(Map.of(), options);
    Template.parse("{% call record() %}x{% endcall %}").render(Map.of(), options);
    assertEquals(2, seen.size());
    assertEquals(java.util.List.of(1L), seen.get(0));
    assertEquals(java.util.List.of(), seen.get(1));
  }

  @Test
  void deepMacroRecursionFailsWithResourceLimitNotStackOverflow_isKnownDivergenceFromUpstream() {
    // Empirically, on this toolchain, an unguarded macro recursion overflows the native JVM stack
    // around n=850-900 (flaky in that band; see
    // docs/superpowers/plans/2026-08-24-wp5-slice3-macros-call-and-filter-blocks.md, Step 4, for
    // how the shipped default of 500 was chosen with margin below that). Upstream has no
    // equivalent guard and instead produces a toolchain-dependent, nonsensical error (or worse, a
    // wrong answer) at its own unguarded stack limit — see this plan's Known Gaps section.
    var error =
        assertThrows(
            TemplateRenderException.class,
            () ->
                Template.parse(
                        "{% macro f(n) %}{% if n <= 0 %}done{% else %}{{ f(n-1) }}{% endif %}"
                            + "{% endmacro %}{{ f(5000) }}")
                    .render(Map.of()));
    assertEquals(ErrorCategory.RESOURCE_LIMIT, error.category());
    assertEquals("Maximum macro call depth exceeded", error.getMessage());
  }

  @Test
  void macroRecursionThroughDefaultArgumentExpressionIsGuarded() {
    // A default-argument expression is evaluated before the macro's body — enterMacro must guard
    // the whole binding loop, not just evaluateBlock(n.body(), ...), or recursion hidden inside a
    // default argument bypasses maxMacroDepth entirely and overflows the native JVM stack instead
    // of failing with a clean RESOURCE_LIMIT error.
    var error =
        assertThrows(
            TemplateRenderException.class,
            () ->
                Template.parse("{% macro m(a=m()) %}x{{ a }}{% endmacro %}{{ m() }}")
                    .render(Map.of()));
    assertEquals(ErrorCategory.RESOURCE_LIMIT, error.category());
    assertEquals("Maximum macro call depth exceeded", error.getMessage());
  }

  @Test
  void nonRecursiveSequentialMacroCallsDoNotAccumulateDepth() {
    var builder = new StringBuilder("{% macro f() %}x{% endmacro %}");
    builder.append("{{ f() }}".repeat(600));
    assertEquals("x".repeat(600), Template.parse(builder.toString()).render(Map.of()));
  }

  @Test
  void macroDepthLimitIsExactlyEnforcedAtTheConfiguredBoundary() {
    // f(9) nests exactly 10 macro invocations (f(9), f(8), ..., f(0), each still active while
    // the next is evaluated). This pins the boundary itself: neither `>=` in place of `>` in
    // RenderBudget.enterMacro, nor an off-by-one in maxMacroDepth's threshold, could pass both
    // assertions below.
    var template =
        Template.parse(
            "{% macro f(n) %}{% if n <= 0 %}done{% else %}{{ f(n-1) }}{% endif %}{% endmacro %}"
                + "{{ f(9) }}");
    assertEquals(
        "done", template.render(Map.of(), RenderOptions.builder().maxMacroDepth(10).build()));
    var error =
        assertThrows(
            TemplateRenderException.class,
            () -> template.render(Map.of(), RenderOptions.builder().maxMacroDepth(9).build()));
    assertEquals(ErrorCategory.RESOURCE_LIMIT, error.category());
    assertEquals("Maximum macro call depth exceeded", error.getMessage());
  }

  @Test
  void defaultMacroDepthLimitAllowsOrdinaryNestedRecursion() {
    // Pins a floor on RenderOptions.DEFAULT's maxMacroDepth (500) without hardcoding the exact
    // value: nested macro recursion is an ordinary template pattern, and a regression collapsing
    // the effective default down to a handful of levels must fail this, even though the existing
    // f(5000)-scale test above cannot (any limit under 5000 also fails that one).
    assertEquals(
        "done",
        Template.parse(
                "{% macro f(n) %}{% if n <= 0 %}done{% else %}{{ f(n-1) }}{% endif %}"
                    + "{% endmacro %}{{ f(99) }}")
            .render(Map.of()));
  }

  @Test
  void filterBlockRendersBodyThenAppliesNamedFilter() {
    assertEquals("HI", Template.parse("{% filter upper %}hi{% endfilter %}").render(Map.of()));
    assertEquals(
        "1-2",
        Template.parse(
                "{% filter join('-') %}{% for i in [1,2] %}{{ i }}{% endfor %}{% endfilter %}")
            .render(Map.of()));
  }

  @Test
  void filterBlockPropagatesBreakToEnclosingLoop() {
    assertEquals(
        "",
        Template.parse(
                "{% for i in [1,2,3] %}{{ i }}{% filter upper %}{% break %}{% endfilter %}{% endfor %}")
            .render(Map.of()));
  }

  @Test
  void filterBlockChargesOutputForBodyAndFilteredResultSeparately() {
    // maxOutputLength bounds cumulative rendered characters, not final output size: the 6-char
    // body is charged once inside evaluateBlock, then the 6-char filtered result is charged
    // again, so a limit of 10 is exceeded even though the visible output is only 6 characters.
    // Matches evaluateSet's pre-existing {% set x %}...{% endset %} block-capture behavior — not
    // a regression introduced by filter/call blocks.
    var options = se.alipsa.hfjinja.RenderOptions.builder().maxOutputLength(10).build();
    var error =
        assertThrows(
            TemplateRenderException.class,
            () ->
                Template.parse("{% filter upper %}abcdef{% endfilter %}")
                    .render(Map.of(), options));
    assertEquals(ErrorCategory.RESOURCE_LIMIT, error.category());
  }

  @Test
  void callBlockChargesOutputForBodyAndCalleeResultSeparately() {
    var options = se.alipsa.hfjinja.RenderOptions.builder().maxOutputLength(10).build();
    var error =
        assertThrows(
            TemplateRenderException.class,
            () ->
                Template.parse(
                        "{% macro wrap() %}<{{ caller() }}>{% endmacro %}"
                            + "{% call wrap() %}abcdef{% endcall %}")
                    .render(Map.of(), options));
    assertEquals(ErrorCategory.RESOURCE_LIMIT, error.category());
  }

  @Test
  void macroCannotBeUsedAsAFilter() {
    // Upstream reports "Unknown StringValue filter: f" here — the pre-existing, documented,
    // out-of-scope divergence in this plan's "Discovered, out-of-scope divergence" note (Java's
    // unknown-filter message omits the operand type). The property this test actually pins is that
    // filters are resolved from a fixed table, never from the variable/macro namespace: `f` is not
    // found as a filter even though it is a perfectly callable macro.
    var error =
        assertThrows(
            TemplateRenderException.class,
            () ->
                Template.parse("{% macro f(x) %}{{ x|upper }}{% endmacro %}{{ 'hi' | f }}")
                    .render(Map.of()));
    assertEquals(ErrorCategory.TYPE, error.category());
    assertEquals("Unknown filter: f", error.getMessage());
  }
}
