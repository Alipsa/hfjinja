package se.alipsa.hfjinja.internal.runtime;

import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.RenderOptions;
import se.alipsa.hfjinja.SourceLocation;
import se.alipsa.hfjinja.TemplateRenderException;

final class RenderBudget {
  private final RenderOptions options;
  private long steps, iterations, output;
  private long rangeElements;
  private int macroDepth;

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

  // Unlike the counters above (monotonic totals), macro depth must go up on entry and back down
  // on exit. enterMacro increments before it checks the limit, so on the throwing path the
  // increment has already happened; callers must call this INSIDE the try block whose finally
  // calls exitMacro(), not before it — otherwise that finally never runs for the throwing call and
  // this counter leaks a level (see
  // docs/superpowers/plans/2026-08-24-wp5-slice3-macros-call-and-filter-blocks.md,
  // Step 4).
  void enterMacro(SourceLocation location) {
    if (++macroDepth > options.maxMacroDepth()) fail("Maximum macro call depth exceeded", location);
  }

  void exitMacro() {
    macroDepth--;
  }

  private static void fail(String message, SourceLocation location) {
    throw new TemplateRenderException(message, ErrorCategory.RESOURCE_LIMIT, location);
  }
}
