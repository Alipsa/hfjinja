package se.alipsa.hfjinja.internal.ast;

import java.util.List;
import java.util.Objects;
import se.alipsa.hfjinja.SourceLocation;

/** Statement nodes ported from upstream {@code ast.ts}. */
public sealed interface Statement
    permits Statement.Program,
        Statement.If,
        Statement.For,
        Statement.Break,
        Statement.Continue,
        Statement.SetStatement,
        Statement.Macro,
        Statement.Comment,
        Statement.FilterStatement,
        Statement.CallStatement,
        Expression {
  SourceLocation location();

  /** A whole parsed template. */
  record Program(List<Statement> body, SourceLocation location) implements Statement {
    public Program {
      body = List.copyOf(body);
      Objects.requireNonNull(location);
    }
  }

  /** A conditional statement. */
  record If(
      Expression test, List<Statement> body, List<Statement> alternate, SourceLocation location)
      implements Statement {
    public If {
      Objects.requireNonNull(test);
      body = List.copyOf(body);
      alternate = List.copyOf(alternate);
      Objects.requireNonNull(location);
    }
  }

  /** An iteration statement. */
  record For(
      Expression loopVariable,
      Expression iterable,
      List<Statement> body,
      List<Statement> defaultBlock,
      SourceLocation location)
      implements Statement {
    public For {
      Objects.requireNonNull(loopVariable);
      Objects.requireNonNull(iterable);
      body = List.copyOf(body);
      defaultBlock = List.copyOf(defaultBlock);
      Objects.requireNonNull(location);
    }
  }

  /** A loop break statement. */
  record Break(SourceLocation location) implements Statement {
    public Break {
      Objects.requireNonNull(location);
    }
  }

  /** A loop continue statement. */
  record Continue(SourceLocation location) implements Statement {
    public Continue {
      Objects.requireNonNull(location);
    }
  }

  /** An assignment or block-capture statement; {@code value} is null for block capture. */
  record SetStatement(
      Expression assignee, Expression value, List<Statement> body, SourceLocation location)
      implements Statement {
    public SetStatement {
      Objects.requireNonNull(assignee);
      body = List.copyOf(body);
      Objects.requireNonNull(location);
    }
  }

  /** A macro declaration. */
  record Macro(
      Expression.Identifier name,
      List<Expression> args,
      List<Statement> body,
      SourceLocation location)
      implements Statement {
    public Macro {
      Objects.requireNonNull(name);
      args = List.copyOf(args);
      body = List.copyOf(body);
      Objects.requireNonNull(location);
    }
  }

  /** A template comment. */
  record Comment(String value, SourceLocation location) implements Statement {
    public Comment {
      Objects.requireNonNull(value);
      Objects.requireNonNull(location);
    }
  }

  /** A block passed through a filter. */
  record FilterStatement(Expression filter, List<Statement> body, SourceLocation location)
      implements Statement {
    public FilterStatement {
      Objects.requireNonNull(filter);
      body = List.copyOf(body);
      Objects.requireNonNull(location);
    }
  }

  /** A caller block; {@code callerArgs} is null when the call declares no caller arguments. */
  record CallStatement(
      Expression.CallExpression call,
      List<Expression> callerArgs,
      List<Statement> body,
      SourceLocation location)
      implements Statement {
    public CallStatement {
      Objects.requireNonNull(call);
      callerArgs = callerArgs == null ? null : List.copyOf(callerArgs);
      body = List.copyOf(body);
      Objects.requireNonNull(location);
    }
  }
}
