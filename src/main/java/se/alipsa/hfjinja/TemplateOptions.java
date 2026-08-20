package se.alipsa.hfjinja;

/** Immutable parse-time limits and syntax options. */
public final class TemplateOptions {
  private static final int DEFAULT_MAX_SOURCE_LENGTH = 1_048_576;
  private static final int DEFAULT_MAX_TOKEN_COUNT = 200_000;

  public static final TemplateOptions DEFAULT = builder().build();

  private final int maxSourceLength;
  private final int maxTokenCount;
  private final boolean trimBlocks;
  private final boolean lstripBlocks;

  private TemplateOptions(int maxSourceLength, int maxTokenCount, boolean trimBlocks, boolean lstripBlocks) {
    this.maxSourceLength = maxSourceLength;
    this.maxTokenCount = maxTokenCount;
    this.trimBlocks = trimBlocks;
    this.lstripBlocks = lstripBlocks;
  }

  /** Starts construction of immutable parse-time options. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns the maximum accepted source length in {@code char}s, checked before preprocessing. */
  public int maxSourceLength() {
    return maxSourceLength;
  }

  /** Returns the maximum accepted token count while scanning. */
  public int maxTokenCount() {
    return maxTokenCount;
  }

  /** Returns whether the first newline after a template tag is stripped automatically. */
  public boolean trimBlocks() {
    return trimBlocks;
  }

  /** Returns whether leading spaces/tabs before a template tag are stripped automatically. */
  public boolean lstripBlocks() {
    return lstripBlocks;
  }

  /** Builder for {@link TemplateOptions}. */
  public static final class Builder {
    private int maxSourceLength = DEFAULT_MAX_SOURCE_LENGTH;
    private int maxTokenCount = DEFAULT_MAX_TOKEN_COUNT;
    private boolean trimBlocks;
    private boolean lstripBlocks;

    private Builder() {}

    public Builder maxSourceLength(int maxSourceLength) {
      if (maxSourceLength <= 0) {
        throw new IllegalArgumentException("maxSourceLength must be positive");
      }
      this.maxSourceLength = maxSourceLength;
      return this;
    }

    public Builder maxTokenCount(int maxTokenCount) {
      if (maxTokenCount <= 0) {
        throw new IllegalArgumentException("maxTokenCount must be positive");
      }
      this.maxTokenCount = maxTokenCount;
      return this;
    }

    public Builder trimBlocks(boolean trimBlocks) {
      this.trimBlocks = trimBlocks;
      return this;
    }

    public Builder lstripBlocks(boolean lstripBlocks) {
      this.lstripBlocks = lstripBlocks;
      return this;
    }

    /** Creates immutable parse-time options. */
    public TemplateOptions build() {
      return new TemplateOptions(maxSourceLength, maxTokenCount, trimBlocks, lstripBlocks);
    }
  }
}
