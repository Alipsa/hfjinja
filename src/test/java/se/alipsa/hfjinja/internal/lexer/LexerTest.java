package se.alipsa.hfjinja.internal.lexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.SourceLocation;
import se.alipsa.hfjinja.TemplateOptions;
import se.alipsa.hfjinja.TemplateRenderException;
import se.alipsa.hfjinja.TemplateSyntaxException;

class LexerTest {
  @Nested
  class HappyPaths {
    @Test
    void tokenizesTextExpressionStatementAndComment() {
      assertShapes(
          "Hello {{ name }}!",
          shape(TokenType.Text, "Hello "),
          shape(TokenType.OpenExpression, "{{"),
          shape(TokenType.Identifier, "name"),
          shape(TokenType.CloseExpression, "}}"),
          shape(TokenType.Text, "!"));

      assertShapes(
          "{% if x %}A{% endif %}",
          shape(TokenType.OpenStatement, "{%"),
          shape(TokenType.Identifier, "if"),
          shape(TokenType.Identifier, "x"),
          shape(TokenType.CloseStatement, "%}"),
          shape(TokenType.Text, "A"),
          shape(TokenType.OpenStatement, "{%"),
          shape(TokenType.Identifier, "endif"),
          shape(TokenType.CloseStatement, "%}"));

      assertShapes(
          "a{# comment #}b",
          shape(TokenType.Text, "a"),
          shape(TokenType.Comment, " comment "),
          shape(TokenType.Text, "b"));
    }

    @Test
    void reproducesRepresentativeUpstreamTestParsedFixtures() {
      // upstream/vendor/test/templates.test.js: TEST_STRINGS.NO_TEMPLATE / TEST_PARSED.NO_TEMPLATE
      assertShapes("Hello world!", shape(TokenType.Text, "Hello world!"));

      // TEST_STRINGS.TEXT_NODES / TEST_PARSED.TEXT_NODES
      assertShapes(
          "0{{ 'A' }}1{{ 'B' }}{{ 'C' }}2{{ 'D' }}3",
          shape(TokenType.Text, "0"),
          shape(TokenType.OpenExpression, "{{"),
          shape(TokenType.StringLiteral, "A"),
          shape(TokenType.CloseExpression, "}}"),
          shape(TokenType.Text, "1"),
          shape(TokenType.OpenExpression, "{{"),
          shape(TokenType.StringLiteral, "B"),
          shape(TokenType.CloseExpression, "}}"),
          shape(TokenType.OpenExpression, "{{"),
          shape(TokenType.StringLiteral, "C"),
          shape(TokenType.CloseExpression, "}}"),
          shape(TokenType.Text, "2"),
          shape(TokenType.OpenExpression, "{{"),
          shape(TokenType.StringLiteral, "D"),
          shape(TokenType.CloseExpression, "}}"),
          shape(TokenType.Text, "3"));
    }
  }

  @Nested
  class WhitespaceControl {
    @Test
    void stripsLeadingWhitespaceBeforeAHyphenatedOpenStatement() {
      assertShapes(
          "  {%- if x %}",
          shape(TokenType.OpenStatement, "{%"),
          shape(TokenType.Identifier, "if"),
          shape(TokenType.Identifier, "x"),
          shape(TokenType.CloseStatement, "%}"));
    }

    @Test
    void stripsLeadingWhitespaceBeforeAHyphenatedOpenExpression() {
      assertShapes(
          "  {{- x }}",
          shape(TokenType.OpenExpression, "{{"),
          shape(TokenType.Identifier, "x"),
          shape(TokenType.CloseExpression, "}}"));
    }

    @Test
    void skipsTrailingWhitespaceAfterAHyphenatedCloseStatement() {
      assertShapes(
          "{% if x -%}  A",
          shape(TokenType.OpenStatement, "{%"),
          shape(TokenType.Identifier, "if"),
          shape(TokenType.Identifier, "x"),
          shape(TokenType.CloseStatement, "%}"),
          shape(TokenType.Text, "A"));
    }

    @Test
    void skipsTrailingWhitespaceAfterAHyphenatedCloseExpression() {
      assertShapes(
          "{{ x -}}  A",
          shape(TokenType.OpenExpression, "{{"),
          shape(TokenType.Identifier, "x"),
          shape(TokenType.CloseExpression, "}}"),
          shape(TokenType.Text, "A"));
    }

    @Test
    void appliesLeadingAndTrailingHyphensAroundAComment() {
      assertShapes(
          "  {#- comment -#}  A",
          shape(TokenType.Comment, " comment "),
          shape(TokenType.Text, "A"));
    }
  }

  @Nested
  class NestedCurlyBrackets {
    @Test
    void doesNotCloseTheExpressionOnAnObjectLiteralsClosingBrace() {
      assertShapes(
          "{{ {\"a\": 1}}}",
          shape(TokenType.OpenExpression, "{{"),
          shape(TokenType.OpenCurlyBracket, "{"),
          shape(TokenType.StringLiteral, "a"),
          shape(TokenType.Colon, ":"),
          shape(TokenType.NumericLiteral, "1"),
          shape(TokenType.CloseCurlyBracket, "}"),
          shape(TokenType.CloseExpression, "}}"));
    }
  }

  @Nested
  class UnaryVersusBinaryOperators {
    @Test
    void treatsAnOperatorAfterAnOperandAsBinary() {
      assertShapes(
          "{{ a - 1 }}",
          shape(TokenType.OpenExpression, "{{"),
          shape(TokenType.Identifier, "a"),
          shape(TokenType.AdditiveBinaryOperator, "-"),
          shape(TokenType.NumericLiteral, "1"),
          shape(TokenType.CloseExpression, "}}"));
    }

    @Test
    void treatsALeadingOperatorAsUnaryAndFoldsItIntoTheNumber() {
      assertShapes(
          "{{ -1 }}",
          shape(TokenType.OpenExpression, "{{"),
          shape(TokenType.NumericLiteral, "-1"),
          shape(TokenType.CloseExpression, "}}"));
    }

    @Test
    void disambiguatesConsecutiveOperators() {
      assertShapes(
          "{{ 1 - -1 }}",
          shape(TokenType.OpenExpression, "{{"),
          shape(TokenType.NumericLiteral, "1"),
          shape(TokenType.AdditiveBinaryOperator, "-"),
          shape(TokenType.NumericLiteral, "-1"),
          shape(TokenType.CloseExpression, "}}"));
    }
  }

  @Nested
  class NumericLiterals {
    @Test
    void doesNotMergeADotIndexWithAFollowingFraction() {
      assertShapes(
          "{{ route.0.5 }}",
          shape(TokenType.OpenExpression, "{{"),
          shape(TokenType.Identifier, "route"),
          shape(TokenType.Dot, "."),
          shape(TokenType.NumericLiteral, "0"),
          shape(TokenType.Dot, "."),
          shape(TokenType.NumericLiteral, "5"),
          shape(TokenType.CloseExpression, "}}"));
    }

    @Test
    void tokenizesIntegersAndFractions() {
      assertShapes(
          "{{ 123 }}",
          shape(TokenType.OpenExpression, "{{"),
          shape(TokenType.NumericLiteral, "123"),
          shape(TokenType.CloseExpression, "}}"));
      assertShapes(
          "{{ 1.5 }}",
          shape(TokenType.OpenExpression, "{{"),
          shape(TokenType.NumericLiteral, "1.5"),
          shape(TokenType.CloseExpression, "}}"));
    }
  }

  @Nested
  class StringEscapes {
    @Test
    void resolvesAValidEscapeSequence() {
      assertShapes(
          "{{ 'it\\'s' }}",
          shape(TokenType.OpenExpression, "{{"),
          shape(TokenType.StringLiteral, "it's"),
          shape(TokenType.CloseExpression, "}}"));
    }

    @Test
    void rejectsAnUnknownEscapeCharacter() {
      var error = assertSyntaxError("{{ '\\q' }}");
      assertEquals("Unexpected escaped character: q", error.getMessage());
      assertEquals(new SourceLocation(4, 1, 5), error.location().orElseThrow());
    }
  }

  /** Verbatim upstream/vendor/test/templates.test.js "Lexing errors" cases (lines 5440-5459). */
  @Nested
  class UpstreamLexingErrors {
    @Test
    void missingClosingCurlyBrace() {
      assertSyntaxError("{{ variable");
    }

    @Test
    void unclosedStringLiteral() {
      assertSyntaxError("{{ 'unclosed string }}");
    }

    @Test
    void unexpectedCharacter() {
      var error = assertSyntaxError("{{ invalid ! invalid }}");
      assertEquals("Unexpected character: !", error.getMessage());
    }

    @Test
    void invalidSmartQuoteCharacters() {
      assertSyntaxError("{{ ‘text’ }}");
    }
  }

  @Nested
  class GenerationTagPreprocessing {
    @Test
    void stripsTheTagButKeepsSurroundingWhitespaceWithoutHyphens() {
      assertShapes(
          " {% generation %}A{% endgeneration %} ",
          shape(TokenType.Text, " A "));
    }

    @Test
    void stripsTheTagAndSurroundingWhitespaceWithHyphens() {
      assertShapes(
          " {%- generation -%}A{%- endgeneration -%} ",
          shape(TokenType.Text, "A"));
    }
  }

  @Nested
  class TrimAndLstripBlocks {
    @Test
    void lstripBlocksRemovesLeadingSpacesBeforeATag() {
      var options = TemplateOptions.builder().lstripBlocks(true).build();
      assertShapes(
          "  {% if %}A",
          options,
          shape(TokenType.OpenStatement, "{%"),
          shape(TokenType.Identifier, "if"),
          shape(TokenType.CloseStatement, "%}"),
          shape(TokenType.Text, "A"));
    }

    @Test
    void trimBlocksRemovesTheNewlineImmediatelyAfterATag() {
      var options = TemplateOptions.builder().trimBlocks(true).build();
      assertShapes(
          "{% if %}\nA",
          options,
          shape(TokenType.OpenStatement, "{%"),
          shape(TokenType.Identifier, "if"),
          shape(TokenType.CloseStatement, "%}"),
          shape(TokenType.Text, "A"));
    }

    @Test
    void withoutTrimBlocksTheNewlineIsPreserved() {
      assertShapes(
          "{% if %}\nA",
          shape(TokenType.OpenStatement, "{%"),
          shape(TokenType.Identifier, "if"),
          shape(TokenType.CloseStatement, "%}"),
          shape(TokenType.Text, "\nA"));
    }

    @Test
    void lstripBlocksRecognizesACrlfLineEnding() {
      // lstrip_blocks strips only the leading spaces/tabs before the tag on its line, never the
      // preceding line terminator itself, so "\r\n" survives into the Text token unchanged. This
      // confirms Java's MULTILINE "^" recognizes a "\r\n" boundary the same way JS's "m" flag does
      // — a genuine JS/Java divergence risk (flagged in the design plan) that turned out not to
      // manifest here, verified empirically rather than assumed.
      var options = TemplateOptions.builder().lstripBlocks(true).build();
      assertShapes(
          "A\r\n  {% if %}B",
          options,
          shape(TokenType.Text, "A\r\n"),
          shape(TokenType.OpenStatement, "{%"),
          shape(TokenType.Identifier, "if"),
          shape(TokenType.CloseStatement, "%}"),
          shape(TokenType.Text, "B"));
    }

    @Test
    void lstripBlocksDoesNotTreatNelAsALineBoundary() {
      var options = TemplateOptions.builder().lstripBlocks(true).build();
      assertShapes(
          "A\u0085  {% if %}B",
          options,
          shape(TokenType.Text, "A\u0085  "),
          shape(TokenType.OpenStatement, "{%"),
          shape(TokenType.Identifier, "if"),
          shape(TokenType.CloseStatement, "%}"),
          shape(TokenType.Text, "B"));
    }
  }

  @Nested
  class ResourceLimits {
    @Test
    void rejectsSourceLongerThanTheConfiguredLimit() {
      var options = TemplateOptions.builder().maxSourceLength(5).build();
      var error = assertThrows(
          TemplateRenderException.class, () -> Lexer.tokenize("123456", options));
      assertEquals(ErrorCategory.RESOURCE_LIMIT, error.category());
    }

    @Test
    void rejectsTokenCountBeyondTheConfiguredLimit() {
      var options = TemplateOptions.builder().maxTokenCount(2).build();
      var error = assertThrows(
          TemplateRenderException.class, () -> Lexer.tokenize("{{ a }}", options));
      assertEquals(ErrorCategory.RESOURCE_LIMIT, error.category());
    }
  }

  @Nested
  class SourceLocations {
    @Test
    void tracksLineAndColumnAcrossANewline() {
      var tokens = Lexer.tokenize("A\n{{ x }}", TemplateOptions.DEFAULT);
      assertEquals(new SourceLocation(0, 1, 1), tokens.get(0).start());
      assertEquals("A\n", tokens.get(0).value());
      assertEquals(new SourceLocation(2, 2, 1), tokens.get(1).start());
      assertEquals(TokenType.OpenExpression, tokens.get(1).type());
    }

    @Test
    void tracksAllRecognizedLineTerminators() {
      var error = assertThrows(TemplateSyntaxException.class, () ->
          Lexer.tokenize("A\rB\u0085C\u2028D\u2029{{ ! }}", TemplateOptions.DEFAULT));
      assertEquals(new SourceLocation(11, 5, 4), error.location().orElseThrow());
    }
  }

  private record TokenShape(TokenType type, String value) {}

  private static TokenShape shape(TokenType type, String value) {
    return new TokenShape(type, value);
  }

  private static void assertShapes(String source, TokenShape... expected) {
    assertShapes(source, TemplateOptions.DEFAULT, expected);
  }

  private static void assertShapes(String source, TemplateOptions options, TokenShape... expected) {
    var actual = Lexer.tokenize(source, options).stream()
        .map(token -> new TokenShape(token.type(), token.value()))
        .collect(Collectors.toList());
    assertEquals(List.of(expected), actual);
  }

  private static TemplateSyntaxException assertSyntaxError(String source) {
    return assertThrows(
        TemplateSyntaxException.class, () -> Lexer.tokenize(source, TemplateOptions.DEFAULT));
  }
}
