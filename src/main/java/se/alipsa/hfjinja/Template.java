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

  public static Template parse(String source) {
    return parse(source, TemplateOptions.DEFAULT);
  }

  public static Template parse(String source, TemplateOptions options) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(options, "options");
    return new Template(Parser.parse(Lexer.tokenize(source, options), options));
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
