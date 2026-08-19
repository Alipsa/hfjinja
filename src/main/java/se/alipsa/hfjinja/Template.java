package se.alipsa.hfjinja;

import java.util.Map;
import java.util.Objects;

/** An immutable parsed template. Parser and interpreter behavior land in subsequent work packages. */
public final class Template {
  private final String source;

  private Template(String source) {
    this.source = source;
  }

  public static Template parse(String source) {
    return parse(source, TemplateOptions.DEFAULT);
  }

  public static Template parse(String source, TemplateOptions options) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(options, "options");
    return new Template(source);
  }

  /** Returns the exact source supplied when this template was parsed. */
  public String source() {
    return source;
  }

  public String render(Map<String, ?> context) {
    return render(context, RenderOptions.DEFAULT);
  }

  public String render(Map<String, ?> context, RenderOptions options) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(options, "options");
    throw unsupported();
  }

  public void render(Map<String, ?> context, Appendable output) {
    render(context, output, RenderOptions.DEFAULT);
  }

  public void render(Map<String, ?> context, Appendable output, RenderOptions options) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(output, "output");
    Objects.requireNonNull(options, "options");
    throw unsupported();
  }

  private UnsupportedOperationException unsupported() {
    return new UnsupportedOperationException("Template rendering is not implemented yet");
  }
}
