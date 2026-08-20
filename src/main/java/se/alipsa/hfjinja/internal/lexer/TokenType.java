package se.alipsa.hfjinja.internal.lexer;

/**
 * Token kinds understood by the lexer, ported one-to-one from upstream {@code TOKEN_TYPES}.
 *
 * <p>{@code CallOperator} is never emitted by the lexer itself; it is reserved for
 * {@code internal.parser} to synthesize, matching upstream's own comment. Declared {@code public},
 * unlike the flat {@code internal} package's usual package-private convention, because the
 * not-yet-written {@code internal.parser} package needs cross-package access. It remains an
 * internal implementation type by package naming and API convention; Java module exports do not
 * prevent classpath consumers from accessing it.
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
