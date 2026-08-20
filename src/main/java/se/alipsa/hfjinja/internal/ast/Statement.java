package se.alipsa.hfjinja.internal.ast;

import java.util.List;
import java.util.Objects;
import se.alipsa.hfjinja.SourceLocation;

/** Statement nodes ported from upstream {@code ast.ts}. */
public sealed interface Statement permits Statement.Program, Statement.If, Statement.For,
    Statement.Break, Statement.Continue, Statement.SetStatement, Statement.Macro,
    Statement.Comment, Statement.FilterStatement, Statement.CallStatement, Expression {
  SourceLocation location();

  record Program(List<Statement> body, SourceLocation location) implements Statement {
    public Program { body = List.copyOf(body); Objects.requireNonNull(location); }
  }
  record If(Expression test, List<Statement> body, List<Statement> alternate,
      SourceLocation location) implements Statement {
    public If { Objects.requireNonNull(test); body = List.copyOf(body); alternate = List.copyOf(alternate); Objects.requireNonNull(location); }
  }
  record For(Expression loopVariable, Expression iterable, List<Statement> body,
      List<Statement> defaultBlock, SourceLocation location) implements Statement {
    public For { Objects.requireNonNull(loopVariable); Objects.requireNonNull(iterable); body = List.copyOf(body); defaultBlock = List.copyOf(defaultBlock); Objects.requireNonNull(location); }
  }
  record Break(SourceLocation location) implements Statement { public Break { Objects.requireNonNull(location); } }
  record Continue(SourceLocation location) implements Statement { public Continue { Objects.requireNonNull(location); } }
  record SetStatement(Expression assignee, Expression value, List<Statement> body,
      SourceLocation location) implements Statement {
    public SetStatement { Objects.requireNonNull(assignee); body = List.copyOf(body); Objects.requireNonNull(location); }
  }
  record Macro(Expression.Identifier name, List<Expression> args, List<Statement> body,
      SourceLocation location) implements Statement {
    public Macro { Objects.requireNonNull(name); args = List.copyOf(args); body = List.copyOf(body); Objects.requireNonNull(location); }
  }
  record Comment(String value, SourceLocation location) implements Statement { public Comment { Objects.requireNonNull(value); Objects.requireNonNull(location); } }
  record FilterStatement(Expression filter, List<Statement> body, SourceLocation location)
      implements Statement { public FilterStatement { Objects.requireNonNull(filter); body = List.copyOf(body); Objects.requireNonNull(location); } }
  record CallStatement(Expression.CallExpression call, List<Expression> callerArgs,
      List<Statement> body, SourceLocation location) implements Statement {
    public CallStatement { Objects.requireNonNull(call); callerArgs = callerArgs == null ? null : List.copyOf(callerArgs); body = List.copyOf(body); Objects.requireNonNull(location); }
  }
}
