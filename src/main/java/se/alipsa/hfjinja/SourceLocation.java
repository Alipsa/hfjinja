package se.alipsa.hfjinja;

/** A zero-based source offset and one-based line and column. */
public record SourceLocation(int offset, int line, int column) {
  public SourceLocation {
    if (offset < 0 || line < 1 || column < 1) {
      throw new IllegalArgumentException("Invalid source location");
    }
  }
}
