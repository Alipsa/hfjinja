package se.alipsa.hfjinja;

import java.util.Map;
import java.util.Objects;
import se.alipsa.hfjinja.internal.ast.Statement;
import se.alipsa.hfjinja.internal.lexer.Lexer;
import se.alipsa.hfjinja.internal.parser.Parser;

/** An immutable parsed template. Parser and interpreter behavior land in subsequent work packages. */
public final class Template {
  private final Statement.Program program;

  private Template(Statement.Program program) {
    this.program = program;
  }

  /**
   * Parses a template with {@link TemplateOptions#DEFAULT}.
   *
   * @param source the template source
   * @return the parsed template
   */
  public static Template parse(String source) {
    return parse(source, TemplateOptions.DEFAULT);
  }

  /**
   * Parses a template.
   *
   * @param source the template source
   * @param options parse-time limits and syntax options
   * @return the parsed template
   */
  public static Template parse(String source, TemplateOptions options) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(options, "options");
    return new Template(Parser.parse(Lexer.tokenize(source, options), options));
  }

  /**
   * Renders this template with {@link RenderOptions#DEFAULT}.
   *
   * @param context the top-level template variables
   * @return the rendered output
   */
  public String render(Map<String, ?> context) {
    return render(context, RenderOptions.DEFAULT);
  }

  /**
   * Renders this template.
   *
   * @param context the top-level template variables
   * @param options render-time clock/zone and host-function options
   * @return the rendered output
   */
  public String render(Map<String, ?> context, RenderOptions options) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(options, "options");
    throw unsupported();
  }

  /**
   * Renders this template with {@link RenderOptions#DEFAULT}, appending output as it is produced.
   *
   * @param context the top-level template variables
   * @param output the destination for rendered output
   */
  public void render(Map<String, ?> context, Appendable output) {
    render(context, output, RenderOptions.DEFAULT);
  }

  /**
   * Renders this template, appending output as it is produced.
   *
   * @param context the top-level template variables
   * @param output the destination for rendered output
   * @param options render-time clock/zone and host-function options
   */
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
