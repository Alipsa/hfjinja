package se.alipsa.hfjinja.internal.lexer;

import java.util.Objects;
import se.alipsa.hfjinja.SourceLocation;

/**
 * A single lexed token: its kind, raw source value, and start location.
 *
 * <p>Declared {@code public} for the same reason as {@link TokenType}: {@code internal.parser}
 * needs cross-package access. It is internal by package naming and API convention; Java module
 * exports do not prevent classpath consumers from accessing it.
 */
public record Token(TokenType type, String value, SourceLocation start) {
  public Token {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(start, "start");
  }
}
