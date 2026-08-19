package se.alipsa.hfjinja;

import java.io.Serializable;

/** A zero-based source offset and one-based line and column. */
public record SourceLocation(int offset, int line, int column) implements Serializable {
  private static final long serialVersionUID = 1L;

  public SourceLocation {
    if (offset < 0 || line < 1 || column < 1) {
      throw new IllegalArgumentException("Invalid source location");
    }
  }
}
