package se.alipsa.hfjinja.internal.ast;

import java.util.List;
import java.util.Objects;
import se.alipsa.hfjinja.SourceLocation;
import se.alipsa.hfjinja.internal.lexer.Token;

/** Expression nodes ported from upstream {@code ast.ts}. */
public sealed interface Expression extends Statement {
  /** A member access expression. */
  record MemberExpression(
      Expression object, Expression property, boolean computed, SourceLocation location)
      implements Expression {
    public MemberExpression {
      Objects.requireNonNull(object);
      Objects.requireNonNull(property);
      Objects.requireNonNull(location);
    }
  }

  /** A call expression. */
  record CallExpression(Expression callee, List<Expression> args, SourceLocation location)
      implements Expression {
    public CallExpression {
      Objects.requireNonNull(callee);
      args = List.copyOf(args);
      Objects.requireNonNull(location);
    }
  }

  /** An identifier expression. */
  record Identifier(String value, SourceLocation location) implements Expression {
    public Identifier {
      Objects.requireNonNull(value);
      Objects.requireNonNull(location);
    }
  }

  /** An integer-syntax numeric literal. */
  record IntegerLiteral(double value, SourceLocation location) implements Expression {
    public IntegerLiteral {
      Objects.requireNonNull(location);
    }
  }

  /** A decimal numeric literal. */
  record FloatLiteral(double value, SourceLocation location) implements Expression {
    public FloatLiteral {
      Objects.requireNonNull(location);
    }
  }

  /** A string literal. */
  record StringLiteral(String value, SourceLocation location) implements Expression {
    public StringLiteral {
      Objects.requireNonNull(value);
      Objects.requireNonNull(location);
    }
  }

  /** An array literal. */
  record ArrayLiteral(List<Expression> value, SourceLocation location) implements Expression {
    public ArrayLiteral {
      value = List.copyOf(value);
      Objects.requireNonNull(location);
    }
  }

  /** A tuple literal. */
  record TupleLiteral(List<Expression> value, SourceLocation location) implements Expression {
    public TupleLiteral {
      value = List.copyOf(value);
      Objects.requireNonNull(location);
    }
  }

  /** One object-literal entry. */
  record ObjectEntry(Expression key, Expression value) {
    public ObjectEntry {
      Objects.requireNonNull(key);
      Objects.requireNonNull(value);
    }
  }

  /** An object literal retaining duplicate keys and insertion order. */
  record ObjectLiteral(List<ObjectEntry> value, SourceLocation location) implements Expression {
    public ObjectLiteral {
      value = List.copyOf(value);
      Objects.requireNonNull(location);
    }
  }

  /** A binary operator expression. */
  record BinaryExpression(
      Token operator, Expression left, Expression right, SourceLocation location)
      implements Expression {
    public BinaryExpression {
      Objects.requireNonNull(operator);
      Objects.requireNonNull(left);
      Objects.requireNonNull(right);
      Objects.requireNonNull(location);
    }
  }

  /** An expression transformed by a filter. */
  record FilterExpression(Expression operand, Expression filter, SourceLocation location)
      implements Expression {
    public FilterExpression {
      Objects.requireNonNull(operand);
      Objects.requireNonNull(filter);
      Objects.requireNonNull(location);
    }
  }

  /** A conditional select expression. */
  record SelectExpression(Expression lhs, Expression test, SourceLocation location)
      implements Expression {
    public SelectExpression {
      Objects.requireNonNull(lhs);
      Objects.requireNonNull(test);
      Objects.requireNonNull(location);
    }
  }

  /** An {@code is} test expression. */
  record TestExpression(
      Expression operand, boolean negate, Identifier test, SourceLocation location)
      implements Expression {
    public TestExpression {
      Objects.requireNonNull(operand);
      Objects.requireNonNull(test);
      Objects.requireNonNull(location);
    }
  }

  /** A unary operator expression. */
  record UnaryExpression(Token operator, Expression argument, SourceLocation location)
      implements Expression {
    public UnaryExpression {
      Objects.requireNonNull(operator);
      Objects.requireNonNull(argument);
      Objects.requireNonNull(location);
    }
  }

  /** A slice whose {@code start}, {@code stop}, and {@code step} components may each be null. */
  record SliceExpression(
      Expression start, Expression stop, Expression step, SourceLocation location)
      implements Expression {
    public SliceExpression {
      Objects.requireNonNull(location);
    }
  }

  /** A keyword call argument. */
  record KeywordArgumentExpression(Identifier key, Expression value, SourceLocation location)
      implements Expression {
    public KeywordArgumentExpression {
      Objects.requireNonNull(key);
      Objects.requireNonNull(value);
      Objects.requireNonNull(location);
    }
  }

  /** A spread call argument. */
  record SpreadExpression(Expression argument, SourceLocation location) implements Expression {
    public SpreadExpression {
      Objects.requireNonNull(argument);
      Objects.requireNonNull(location);
    }
  }

  /** A three-branch conditional expression. */
  record Ternary(
      Expression condition, Expression trueExpr, Expression falseExpr, SourceLocation location)
      implements Expression {
    public Ternary {
      Objects.requireNonNull(condition);
      Objects.requireNonNull(trueExpr);
      Objects.requireNonNull(falseExpr);
      Objects.requireNonNull(location);
    }
  }
}
