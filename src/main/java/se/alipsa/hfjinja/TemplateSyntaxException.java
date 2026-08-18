package se.alipsa.hfjinja;

/** A template source error. */
public final class TemplateSyntaxException extends HfJinjaException {
  public TemplateSyntaxException(String message, SourceLocation location) {
    super(message, ErrorCategory.SYNTAX, location);
  }
}
