package se.alipsa.hfjinja;

import java.util.Objects;
import java.util.Optional;

/** Base class for all documented hfjinja failures. */
public class HfJinjaException extends RuntimeException {
  private final ErrorCategory category;
  private final SourceLocation location;

  public HfJinjaException(String message, ErrorCategory category, SourceLocation location) {
    super(message);
    this.category = Objects.requireNonNull(category, "category");
    this.location = location;
  }

  public HfJinjaException(
      String message, Throwable cause, ErrorCategory category, SourceLocation location) {
    super(message, cause);
    this.category = Objects.requireNonNull(category, "category");
    this.location = location;
  }

  public ErrorCategory category() {
    return category;
  }

  public Optional<SourceLocation> location() {
    return Optional.ofNullable(location);
  }
}
