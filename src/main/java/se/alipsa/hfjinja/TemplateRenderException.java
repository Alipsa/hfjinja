package se.alipsa.hfjinja;

/** A host-boundary or render-time error. */
public final class TemplateRenderException extends HfJinjaException {
  private static final long serialVersionUID = 1L;

  public TemplateRenderException(String message, ErrorCategory category, SourceLocation location) {
    super(message, category, location);
  }

  public TemplateRenderException(
      String message, Throwable cause, ErrorCategory category, SourceLocation location) {
    super(message, cause, category, location);
  }
}
