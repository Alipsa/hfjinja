package se.alipsa.hfjinja.internal.ast;

import java.util.List;
import java.util.Objects;
import se.alipsa.hfjinja.SourceLocation;
import se.alipsa.hfjinja.internal.lexer.Token;

/** Expression nodes ported from upstream {@code ast.ts}. */
public sealed interface Expression extends Statement {
  record MemberExpression(Expression object, Expression property, boolean computed, SourceLocation location) implements Expression { public MemberExpression { Objects.requireNonNull(object); Objects.requireNonNull(property); Objects.requireNonNull(location); } }
  record CallExpression(Expression callee, List<Expression> args, SourceLocation location) implements Expression { public CallExpression { Objects.requireNonNull(callee); args = List.copyOf(args); Objects.requireNonNull(location); } }
  record Identifier(String value, SourceLocation location) implements Expression { public Identifier { Objects.requireNonNull(value); Objects.requireNonNull(location); } }
  record IntegerLiteral(double value, SourceLocation location) implements Expression { public IntegerLiteral { Objects.requireNonNull(location); } }
  record FloatLiteral(double value, SourceLocation location) implements Expression { public FloatLiteral { Objects.requireNonNull(location); } }
  record StringLiteral(String value, SourceLocation location) implements Expression { public StringLiteral { Objects.requireNonNull(value); Objects.requireNonNull(location); } }
  record ArrayLiteral(List<Expression> value, SourceLocation location) implements Expression { public ArrayLiteral { value = List.copyOf(value); Objects.requireNonNull(location); } }
  record TupleLiteral(List<Expression> value, SourceLocation location) implements Expression { public TupleLiteral { value = List.copyOf(value); Objects.requireNonNull(location); } }
  record ObjectEntry(Expression key, Expression value) { public ObjectEntry { Objects.requireNonNull(key); Objects.requireNonNull(value); } }
  record ObjectLiteral(List<ObjectEntry> value, SourceLocation location) implements Expression { public ObjectLiteral { value = List.copyOf(value); Objects.requireNonNull(location); } }
  record BinaryExpression(Token operator, Expression left, Expression right, SourceLocation location) implements Expression { public BinaryExpression { Objects.requireNonNull(operator); Objects.requireNonNull(left); Objects.requireNonNull(right); Objects.requireNonNull(location); } }
  record FilterExpression(Expression operand, Expression filter, SourceLocation location) implements Expression { public FilterExpression { Objects.requireNonNull(operand); Objects.requireNonNull(filter); Objects.requireNonNull(location); } }
  record SelectExpression(Expression lhs, Expression test, SourceLocation location) implements Expression { public SelectExpression { Objects.requireNonNull(lhs); Objects.requireNonNull(test); Objects.requireNonNull(location); } }
  record TestExpression(Expression operand, boolean negate, Identifier test, SourceLocation location) implements Expression { public TestExpression { Objects.requireNonNull(operand); Objects.requireNonNull(test); Objects.requireNonNull(location); } }
  record UnaryExpression(Token operator, Expression argument, SourceLocation location) implements Expression { public UnaryExpression { Objects.requireNonNull(operator); Objects.requireNonNull(argument); Objects.requireNonNull(location); } }
  record SliceExpression(Expression start, Expression stop, Expression step, SourceLocation location) implements Expression { public SliceExpression { Objects.requireNonNull(location); } }
  record KeywordArgumentExpression(Identifier key, Expression value, SourceLocation location) implements Expression { public KeywordArgumentExpression { Objects.requireNonNull(key); Objects.requireNonNull(value); Objects.requireNonNull(location); } }
  record SpreadExpression(Expression argument, SourceLocation location) implements Expression { public SpreadExpression { Objects.requireNonNull(argument); Objects.requireNonNull(location); } }
  record Ternary(Expression condition, Expression trueExpr, Expression falseExpr, SourceLocation location) implements Expression { public Ternary { Objects.requireNonNull(condition); Objects.requireNonNull(trueExpr); Objects.requireNonNull(falseExpr); Objects.requireNonNull(location); } }
}
