package se.alipsa.hfjinja.internal.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.TemplateOptions;
import se.alipsa.hfjinja.TemplateRenderException;
import se.alipsa.hfjinja.TemplateSyntaxException;
import se.alipsa.hfjinja.internal.ast.Expression;
import se.alipsa.hfjinja.internal.ast.Statement;
import se.alipsa.hfjinja.internal.lexer.Lexer;
import org.junit.jupiter.api.Test;

class ParserTest {
  private static final TemplateOptions RAW = TemplateOptions.builder().build();

  @Test void parsesTextAndComment() {
    var program = parse("a{# note #}b");
    assertEquals(3, program.body().size());
    assertEquals(" note ", ((Statement.Comment) program.body().get(1)).value());
  }
  @Test void reportsLocatedUnexpectedEnd() {
    var error = assertThrows(TemplateSyntaxException.class, () -> parse("{{ variable }}{{"));
    assertEquals(ErrorCategory.SYNTAX, error.category()); assertEquals(14, error.location().orElseThrow().offset());
  }
  @Test void concatenatesStringsAndPreservesDuplicateObjectKeys() {
    assertEquals("abc", ((Expression.StringLiteral) expression("{{ 'a' 'b' 'c' }}")).value());
    assertEquals(2, ((Expression.ObjectLiteral) expression("{{ {'a':1,'a':2} }}")).value().size());
  }
  @Test void parsesPrecedenceAndStatements() {
    var outer = (Expression.BinaryExpression) expression("{{ 'a' in 'apple' == 'b' in 'banana' }}");
    assertEquals("in", outer.operator().value());
    assertEquals("in", outer.operator().value());
    assertEquals("==", ((Expression.BinaryExpression) outer.left()).operator().value());
    assertInstanceOf(Expression.MemberExpression.class, ((Expression.UnaryExpression) expression("{{ not test.x }}")).argument());
    assertInstanceOf(Statement.If.class, parse("{% if a %}yes{% else %}no{% endif %}").body().get(0));
    assertInstanceOf(Statement.For.class, parse("{% for x in xs %}{{ x }}{% endfor %}").body().get(0));
  }
  @Test void deeplyNestedInputIsResourceLimited() {
    var source = "{{ " + "(".repeat(5_000) + "1" + ")".repeat(5_000) + " }}";
    var error = assertThrows(TemplateRenderException.class, () -> parse(source));
    assertEquals(ErrorCategory.RESOURCE_LIMIT, error.category());
  }
  private static Statement.Program parse(String source) { return Parser.parse(Lexer.tokenize(source, RAW), RAW); }
  private static Expression expression(String source) { return (Expression) parse(source).body().get(0); }
}
