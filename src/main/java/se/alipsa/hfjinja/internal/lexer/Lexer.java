package se.alipsa.hfjinja.internal.lexer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.SourceLocation;
import se.alipsa.hfjinja.TemplateOptions;
import se.alipsa.hfjinja.TemplateRenderException;
import se.alipsa.hfjinja.TemplateSyntaxException;

/**
 * Scanner ported from upstream {@code lexer.ts}: whitespace controls, tokens, and source spans.
 *
 * <p>Reported {@link SourceLocation}s refer to the caller's original string. Preprocessing (the
 * trailing newline strip and, when enabled, {@code trim_blocks}/{@code lstrip_blocks}/the {@code
 * generation} tag strip) records every removal in a per-character origin map, and scanner positions
 * are mapped back through it before a location is constructed.
 */
public final class Lexer {
  /**
   * The ECMA-262 WhiteSpace and LineTerminator character set, as a regex bracket-class body built
   * from explicit backslash-u escapes. It is wider than Java's default whitespace notions: it adds
   * NBSP, the Unicode {@code Zs} space separators, U+2028/U+2029, and U+FEFF, none of which {@link
   * Character#isWhitespace} or {@link Pattern}'s default {@code \s} treat as whitespace. Kept in
   * sync by hand with {@link #isJsWhitespace(char)} below — a bracket character class and a
   * per-char predicate can't share one definition, since the class needs the {@code X-Y} range
   * syntax that a plain predicate expresses as a numeric comparison instead.
   */
  private static final String JS_WHITESPACE_CHAR_CLASS =
      "\\t\\n\\u000B\\f\\r\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";

  /*
   * ECMAScript's multiline ^ recognizes only LF, CR, LS, and PS as line terminators. Java also
   * recognizes NEL (U+0085), so use an explicit preceding-character assertion rather than (?m).
   */
  private static final Pattern LSTRIP_BLOCKS_PATTERN =
      Pattern.compile("(?:^|(?<=[\\n\\r\\u2028\\u2029]))[ \\t]*(\\{[#%-])");
  private static final Pattern TRIM_BLOCKS_PATTERN = Pattern.compile("([#%-]\\})\\n");

  /**
   * Strips the HF-transformers-specific {@code {% generation %}}/{@code {% endgeneration %}} tags,
   * honoring their {@code -} whitespace-control modifiers. Upstream compiles this with JS's {@code
   * gs} flags. Its literal <code>&#123;%</code> prefix is deliberately matched before surrounding
   * whitespace is examined, preventing unsuccessful searches through long whitespace runs from
   * becoming quadratic.
   */
  private static final Pattern GENERATION_TAG_PATTERN =
      Pattern.compile(
          "\\{%(-?)["
              + JS_WHITESPACE_CHAR_CLASS
              + "]*(?:end)?generation["
              + JS_WHITESPACE_CHAR_CLASS
              + "]*(-?)%\\}");

  private static final Map<Character, Character> ESCAPE_CHARACTERS =
      Map.of(
          'n', '\n', 't', '\t', 'r', '\r', 'b', '\b', 'f', '\f', 'v', '\u000B', '\'', '\'', '"',
          '"', '\\', '\\');

  /** Match order is behaviorally significant: longer prefixes must be tried before shorter ones. */
  private static final List<TokenPattern> ORDERED_MAPPING_TABLE =
      List.of(
          new TokenPattern("{%", TokenType.OpenStatement),
          new TokenPattern("%}", TokenType.CloseStatement),
          new TokenPattern("{{", TokenType.OpenExpression),
          new TokenPattern("}}", TokenType.CloseExpression),
          new TokenPattern("(", TokenType.OpenParen),
          new TokenPattern(")", TokenType.CloseParen),
          new TokenPattern("{", TokenType.OpenCurlyBracket),
          new TokenPattern("}", TokenType.CloseCurlyBracket),
          new TokenPattern("[", TokenType.OpenSquareBracket),
          new TokenPattern("]", TokenType.CloseSquareBracket),
          new TokenPattern(",", TokenType.Comma),
          new TokenPattern(".", TokenType.Dot),
          new TokenPattern(":", TokenType.Colon),
          new TokenPattern("|", TokenType.Pipe),
          new TokenPattern("<=", TokenType.ComparisonBinaryOperator),
          new TokenPattern(">=", TokenType.ComparisonBinaryOperator),
          new TokenPattern("==", TokenType.ComparisonBinaryOperator),
          new TokenPattern("!=", TokenType.ComparisonBinaryOperator),
          new TokenPattern("<", TokenType.ComparisonBinaryOperator),
          new TokenPattern(">", TokenType.ComparisonBinaryOperator),
          new TokenPattern("+", TokenType.AdditiveBinaryOperator),
          new TokenPattern("-", TokenType.AdditiveBinaryOperator),
          new TokenPattern("~", TokenType.AdditiveBinaryOperator),
          new TokenPattern("*", TokenType.MultiplicativeBinaryOperator),
          new TokenPattern("/", TokenType.MultiplicativeBinaryOperator),
          new TokenPattern("%", TokenType.MultiplicativeBinaryOperator),
          new TokenPattern("=", TokenType.Equals));

  private Lexer() {}

  /**
   * Scans {@code source} into a token list.
   *
   * @param source the template source
   * @param options parse-time limits and syntax options
   * @return the scanned tokens, in source order
   */
  public static List<Token> tokenize(String source, TemplateOptions options) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(options, "options");
    if (source.length() > options.maxSourceLength()) {
      throw new TemplateRenderException(
          "Source length "
              + source.length()
              + " exceeds the configured limit of "
              + options.maxSourceLength(),
          ErrorCategory.RESOURCE_LIMIT,
          null);
    }
    return new Scanner(source, options).scan();
  }

  private static PreprocessedSource preprocess(String source, TemplateOptions options) {
    var offsets = new int[source.length() + 1];
    for (var i = 0; i <= source.length(); i++) {
      offsets[i] = i;
    }
    var length = source.length();
    if (length > 0 && source.charAt(length - 1) == '\n') {
      length--;
    }
    var text = buildText(source, offsets, length);
    if (options.lstripBlocks()) {
      var matcher = LSTRIP_BLOCKS_PATTERN.matcher(text);
      var ranges = new ArrayList<int[]>();
      while (matcher.find()) {
        ranges.add(new int[] {matcher.start(), matcher.start(1)});
      }
      if (!ranges.isEmpty()) {
        length = removeRanges(offsets, length, ranges);
        text = buildText(source, offsets, length);
      }
    }
    if (options.trimBlocks()) {
      var matcher = TRIM_BLOCKS_PATTERN.matcher(text);
      var ranges = new ArrayList<int[]>();
      while (matcher.find()) {
        ranges.add(new int[] {matcher.end(1), matcher.end()});
      }
      if (!ranges.isEmpty()) {
        length = removeRanges(offsets, length, ranges);
        text = buildText(source, offsets, length);
      }
    }
    if (text.indexOf("generation") >= 0) {
      var ranges = generationRemovalRanges(text);
      if (!ranges.isEmpty()) {
        length = removeRanges(offsets, length, ranges);
        text = buildText(source, offsets, length);
      }
    }
    offsets[length] = length > 0 ? offsets[length - 1] + 1 : 0;
    return new PreprocessedSource(text, Arrays.copyOf(offsets, length + 1), lineStarts(source));
  }

  private static String buildText(String source, int[] offsets, int length) {
    var text = new StringBuilder(length);
    for (var i = 0; i < length; i++) {
      text.append(source.charAt(offsets[i]));
    }
    return text.toString();
  }

  /**
   * Removes {@code ranges} — ascending, non-overlapping {@code [start, end)} pairs in current-text
   * coordinates — from the kept-offsets array, returning the new logical length.
   */
  private static int removeRanges(int[] offsets, int length, List<int[]> ranges) {
    var write = 0;
    var rangeIndex = 0;
    for (var read = 0; read < length; read++) {
      while (rangeIndex < ranges.size() && read >= ranges.get(rangeIndex)[1]) {
        rangeIndex++;
      }
      if (rangeIndex >= ranges.size() || read < ranges.get(rangeIndex)[0]) {
        offsets[write++] = offsets[read];
      }
    }
    return write;
  }

  /**
   * Computes the character ranges removed by the {@code generation}-tag strip, in {@code template}
   * coordinates. The tag itself is always removed; the surrounding JS-whitespace runs are removed
   * only on the sides carrying a {@code -} modifier. {@code copyPosition} bounds each whitespace
   * scan to the region after the previous match, so the returned ranges are ascending and
   * non-overlapping.
   */
  private static List<int[]> generationRemovalRanges(String template) {
    var matcher = GENERATION_TAG_PATTERN.matcher(template);
    var ranges = new ArrayList<int[]>();
    var copyPosition = 0;
    while (matcher.find()) {
      var whitespaceBefore = matcher.start();
      while (whitespaceBefore > copyPosition
          && isJsWhitespace(template.charAt(whitespaceBefore - 1))) {
        whitespaceBefore--;
      }
      var whitespaceAfter = matcher.end();
      while (whitespaceAfter < template.length()
          && isJsWhitespace(template.charAt(whitespaceAfter))) {
        whitespaceAfter++;
      }
      ranges.add(
          new int[] {
            matcher.group(1).isEmpty() ? matcher.start() : whitespaceBefore,
            matcher.group(2).isEmpty() ? matcher.end() : whitespaceAfter
          });
      copyPosition = whitespaceAfter;
    }
    return ranges;
  }

  /**
   * Computes the original-source offsets at which each line begins, using the scanner's
   * line-terminator rules: LF, CR, LS, and PS each end a line, and CRLF counts as one break.
   */
  private static int[] lineStarts(String source) {
    var starts = new ArrayList<Integer>();
    starts.add(0);
    for (var i = 0; i < source.length(); i++) {
      var c = source.charAt(i);
      if (isLineTerminator(c)) {
        if (c == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n') {
          i++;
        }
        starts.add(i + 1);
      }
    }
    return starts.stream().mapToInt(Integer::intValue).toArray();
  }

  private static boolean isLineTerminator(char c) {
    return c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029';
  }

  /**
   * The preprocessed template text plus the data needed to map positions in it back to the caller's
   * original string.
   *
   * @param text the text the scanner works on, after all preprocessing removals
   * @param originalOffsets {@code originalOffsets[p]} is the original-source offset of the
   *     character at preprocessed position {@code p}; the entry at {@code text.length()} is a
   *     sentinel holding the original offset just past the last kept character, so end-of-input
   *     locations also map
   * @param lineStarts original-source offsets at which each line begins
   */
  private record PreprocessedSource(String text, int[] originalOffsets, int[] lineStarts) {

    SourceLocation locationAt(int preprocessedPosition) {
      // The scanner may advance past end of input while diagnosing malformed constructs. Preserve
      // that behavior by extending locations beyond the mapped input rather than indexing past the
      // origin map's end sentinel.
      var lastPosition = originalOffsets.length - 1;
      var offset =
          preprocessedPosition <= lastPosition
              ? originalOffsets[preprocessedPosition]
              : originalOffsets[lastPosition] + preprocessedPosition - lastPosition;
      var insertion = Arrays.binarySearch(lineStarts, offset);
      var lineIndex = insertion >= 0 ? insertion : -insertion - 2;
      return new SourceLocation(offset, lineIndex + 1, offset - lineStarts[lineIndex] + 1);
    }
  }

  private static boolean isWordChar(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
  }

  private static boolean isIntegerChar(char c) {
    return c >= '0' && c <= '9';
  }

  /** See {@link #JS_WHITESPACE_CHAR_CLASS} for why this can't share a definition with it. */
  private static boolean isJsWhitespace(char c) {
    return c == '\t'
        || c == '\n'
        || c == '\u000B'
        || c == '\f'
        || c == '\r'
        || c == '\u0020'
        || c == '\u00A0'
        || c == '\u1680'
        || (c >= '\u2000' && c <= '\u200A')
        || c == '\u2028'
        || c == '\u2029'
        || c == '\u202F'
        || c == '\u205F'
        || c == '\u3000'
        || c == '\uFEFF';
  }

  private record TokenPattern(String sequence, TokenType type) {}

  @FunctionalInterface
  private interface CharPredicate {
    boolean test(char c);
  }

  private static final class Scanner {
    private final PreprocessedSource preprocessed;
    private final String src;
    private final TemplateOptions options;
    private final List<Token> tokens = new ArrayList<>();
    private int cursorPosition;
    private int curlyBracketDepth;

    Scanner(String source, TemplateOptions options) {
      this.preprocessed = preprocess(source, options);
      this.src = preprocessed.text();
      this.options = options;
    }

    List<Token> scan() {
      main:
      while (cursorPosition < src.length()) {
        var lastType = lastTokenType();
        if (lastType == null
            || lastType == TokenType.CloseStatement
            || lastType == TokenType.CloseExpression
            || lastType == TokenType.Comment) {
          var start = currentLocation();
          var text = new StringBuilder();
          while (cursorPosition < src.length()
              && !(charAt(cursorPosition) == '{'
                  && (charAt(cursorPosition + 1) == '%'
                      || charAt(cursorPosition + 1) == '{'
                      || charAt(cursorPosition + 1) == '#'))) {
            text.append(charAt(cursorPosition));
            advance();
          }
          if (text.length() > 0) {
            addToken(TokenType.Text, text.toString(), start);
            continue;
          }
        }

        if (charAt(cursorPosition) == '{' && charAt(cursorPosition + 1) == '#') {
          var start = currentLocation();
          advance(2); // skip "{#"
          var stripBefore = charAt(cursorPosition) == '-';
          if (stripBefore) {
            advance();
          }
          var comment = new StringBuilder();
          while (charAt(cursorPosition) != '#' || charAt(cursorPosition + 1) != '}') {
            if (cursorPosition + 2 >= src.length()) {
              throw syntaxError("Missing end of comment tag", currentLocation());
            }
            comment.append(charAt(cursorPosition));
            advance();
          }
          var stripAfter = comment.length() > 0 && comment.charAt(comment.length() - 1) == '-';
          if (stripAfter) {
            comment.setLength(comment.length() - 1);
          }
          if (stripBefore) {
            stripTrailingWhitespace();
          }
          addToken(TokenType.Comment, comment.toString(), start);
          advance(2); // skip "#}"
          if (stripAfter) {
            skipLeadingWhitespace();
          }
          continue;
        }

        if (regionMatches("{%-")) {
          stripTrailingWhitespace();
          addToken(TokenType.OpenStatement, "{%", currentLocation());
          advance(3);
          continue;
        }

        if (regionMatches("{{-")) {
          stripTrailingWhitespace();
          addToken(TokenType.OpenExpression, "{{", currentLocation());
          curlyBracketDepth = 0;
          advance(3);
          continue;
        }

        consumeWhile(Lexer::isJsWhitespace);

        if (regionMatches("-%}")) {
          addToken(TokenType.CloseStatement, "%}", currentLocation());
          advance(3);
          skipLeadingWhitespace();
          continue;
        }

        if (regionMatches("-}}")) {
          addToken(TokenType.CloseExpression, "}}", currentLocation());
          advance(3);
          skipLeadingWhitespace();
          continue;
        }

        var currentChar = charAt(cursorPosition);

        if (currentChar == '-' || currentChar == '+') {
          var lastOperatorType = lastTokenType();
          if (lastOperatorType == null || lastOperatorType == TokenType.Text) {
            throw syntaxError("Unexpected character: " + currentChar, currentLocation());
          }
          switch (lastOperatorType) {
            case Identifier, NumericLiteral, StringLiteral, CloseParen, CloseSquareBracket -> {
              // Part of a binary operator: fall through to the mapping-table scan below.
            }
            default -> {
              var start = currentLocation();
              advance(); // consume the unary operator character
              var digits = consumeWhile(Lexer::isIntegerChar);
              addToken(
                  digits.isEmpty() ? TokenType.UnaryOperator : TokenType.NumericLiteral,
                  digits.isEmpty() ? String.valueOf(currentChar) : currentChar + digits,
                  start);
              continue main;
            }
          }
        }

        for (var pattern : ORDERED_MAPPING_TABLE) {
          if (pattern.sequence().equals("}}") && curlyBracketDepth > 0) {
            continue;
          }
          if (regionMatches(pattern.sequence())) {
            addToken(pattern.type(), pattern.sequence(), currentLocation());
            if (pattern.type() == TokenType.OpenExpression) {
              curlyBracketDepth = 0;
            } else if (pattern.type() == TokenType.OpenCurlyBracket) {
              curlyBracketDepth++;
            } else if (pattern.type() == TokenType.CloseCurlyBracket) {
              curlyBracketDepth--;
            }
            advance(pattern.sequence().length());
            continue main;
          }
        }

        if (currentChar == '\'' || currentChar == '"') {
          var quote = currentChar;
          var start = currentLocation();
          advance(); // skip opening quote
          var value = consumeWhile(c -> c != quote);
          addToken(TokenType.StringLiteral, value, start);
          advance(); // skip closing quote
          continue;
        }

        if (isIntegerChar(currentChar)) {
          var start = currentLocation();
          var number = consumeWhile(Lexer::isIntegerChar);
          if (lastTokenType() != TokenType.Dot
              && charAt(cursorPosition) == '.'
              && isIntegerChar(charAt(cursorPosition + 1))) {
            advance(); // consume '.'
            var fraction = consumeWhile(Lexer::isIntegerChar);
            number = number + "." + fraction;
          }
          addToken(TokenType.NumericLiteral, number, start);
          continue;
        }

        if (isWordChar(currentChar)) {
          var start = currentLocation();
          var word = consumeWhile(Lexer::isWordChar);
          addToken(TokenType.Identifier, word, start);
          continue;
        }

        throw syntaxError("Unexpected character: " + currentChar, currentLocation());
      }
      return List.copyOf(tokens);
    }

    private TokenType lastTokenType() {
      return tokens.isEmpty() ? null : tokens.get(tokens.size() - 1).type();
    }

    private char charAt(int index) {
      return index >= 0 && index < src.length() ? src.charAt(index) : '\0';
    }

    private boolean regionMatches(String literal) {
      return src.regionMatches(cursorPosition, literal, 0, literal.length());
    }

    private void advance() {
      cursorPosition++;
    }

    private void advance(int count) {
      for (var i = 0; i < count; i++) {
        advance();
      }
    }

    private SourceLocation currentLocation() {
      return preprocessed.locationAt(cursorPosition);
    }

    /**
     * Consumes characters while {@code predicate} holds, resolving backslash escapes inline. Only
     * the string-literal call site ever exercises the escape branch: {@code '\\'} never satisfies
     * {@link #isJsWhitespace}, {@link #isIntegerChar}, or {@link #isWordChar}, so it's structurally
     * unreachable from the other three call sites. Faithfully preserves an upstream quirk: the "ran
     * off the end" check fires unconditionally right after consuming any character (escaped or not)
     * — this is upstream's only mechanism for detecting an unterminated tag, since more content (a
     * closing delimiter) is always grammatically expected while scanning inside one.
     */
    private String consumeWhile(CharPredicate predicate) {
      var text = new StringBuilder();
      while (predicate.test(charAt(cursorPosition))) {
        if (charAt(cursorPosition) == '\\') {
          var escapeLocation = currentLocation();
          advance();
          if (cursorPosition >= src.length()) {
            throw syntaxError("Unexpected end of input", escapeLocation);
          }
          var escaped = charAt(cursorPosition);
          advance();
          var unescaped = ESCAPE_CHARACTERS.get(escaped);
          if (unescaped == null) {
            throw syntaxError("Unexpected escaped character: " + escaped, escapeLocation);
          }
          text.append(unescaped.charValue());
          continue;
        }
        text.append(charAt(cursorPosition));
        advance();
        if (cursorPosition >= src.length()) {
          throw syntaxError("Unexpected end of input", currentLocation());
        }
      }
      return text.toString();
    }

    private void stripTrailingWhitespace() {
      if (tokens.isEmpty()) {
        return;
      }
      var lastIndex = tokens.size() - 1;
      var last = tokens.get(lastIndex);
      if (last.type() != TokenType.Text) {
        return;
      }
      var trimmed = trimTrailingJsWhitespace(last.value());
      if (trimmed.isEmpty()) {
        tokens.remove(lastIndex);
      } else {
        tokens.set(lastIndex, new Token(TokenType.Text, trimmed, last.start()));
      }
    }

    private static String trimTrailingJsWhitespace(String value) {
      var end = value.length();
      while (end > 0 && isJsWhitespace(value.charAt(end - 1))) {
        end--;
      }
      return value.substring(0, end);
    }

    private void skipLeadingWhitespace() {
      while (cursorPosition < src.length() && isJsWhitespace(charAt(cursorPosition))) {
        advance();
      }
    }

    private void addToken(TokenType type, String value, SourceLocation start) {
      if (tokens.size() >= options.maxTokenCount()) {
        throw new TemplateRenderException(
            "Token count exceeds the configured limit of " + options.maxTokenCount(),
            ErrorCategory.RESOURCE_LIMIT,
            start);
      }
      tokens.add(new Token(type, value, start));
    }

    private static TemplateSyntaxException syntaxError(String message, SourceLocation location) {
      return new TemplateSyntaxException(message, location);
    }
  }
}
