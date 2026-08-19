package se.alipsa.hfjinja;

/** A template source error. */
public final class TemplateSyntaxException extends HfJinjaException {
  private static final long serialVersionUID = 1L;

  public TemplateSyntaxException(String message, SourceLocation location) {
    super(message, ErrorCategory.SYNTAX, location);
  }
}
