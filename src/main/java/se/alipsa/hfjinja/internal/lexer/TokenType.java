package se.alipsa.hfjinja.internal.lexer;

/**
 * Token kinds understood by the lexer, ported one-to-one from upstream {@code TOKEN_TYPES}.
 *
 * <p>{@code CallOperator} is never emitted by the lexer itself; it is reserved for
 * {@code internal.parser} to synthesize, matching upstream's own comment. Declared {@code public},
 * unlike the flat {@code internal} package's usual package-private convention, because the
 * not-yet-written {@code internal.parser} package needs cross-package access; {@code module-info}
 * exports only {@code se.alipsa.hfjinja}, so this stays unreachable from outside the module.
 */
public enum TokenType {
  Text,
  NumericLiteral,
  StringLiteral,
  Identifier,
  Equals,
  OpenParen,
  CloseParen,
  OpenStatement,
  CloseStatement,
  OpenExpression,
  CloseExpression,
  OpenSquareBracket,
  CloseSquareBracket,
  OpenCurlyBracket,
  CloseCurlyBracket,
  Comma,
  Dot,
  Colon,
  Pipe,
  CallOperator,
  AdditiveBinaryOperator,
  MultiplicativeBinaryOperator,
  ComparisonBinaryOperator,
  UnaryOperator,
  Comment
}
