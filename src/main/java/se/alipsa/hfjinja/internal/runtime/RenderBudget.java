package se.alipsa.hfjinja.internal.runtime;

import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.RenderOptions;
import se.alipsa.hfjinja.SourceLocation;
import se.alipsa.hfjinja.TemplateRenderException;

final class RenderBudget {
  private final RenderOptions options;
  private long steps, iterations, output;
  private long rangeElements;

  RenderBudget(RenderOptions options) {
    this.options = options;
  }

  void chargeStep(SourceLocation location) {
    if (++steps > options.maxSteps()) fail("Maximum render steps exceeded", location);
  }

  void chargeLoopIteration(SourceLocation location) {
    if (++iterations > options.maxLoopIterations())
      fail("Maximum loop iterations exceeded", location);
  }

  void chargeOutput(int length, SourceLocation location) {
    if ((output += length) > options.maxOutputLength())
      fail("Maximum output length exceeded", location);
  }

  void chargeRangeElement(SourceLocation location) {
    if (++rangeElements > options.maxLoopIterations())
      fail("Maximum loop iterations exceeded", location);
  }

  private static void fail(String message, SourceLocation location) {
    throw new TemplateRenderException(message, ErrorCategory.RESOURCE_LIMIT, location);
  }
}
