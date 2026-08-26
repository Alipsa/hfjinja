# WP6 Slice 2 — Resource-Limit Boundary Coverage

## Goal

Close WP6 item 2 from `req/implementation-plan.md`: exercise source, token, AST-depth, render
step, loop-iteration, macro-depth, and output limits at their exact boundaries, independently of
the Node parity corpus. The suite must prove the public `TemplateOptions`/`RenderOptions` limits
fail predictably as `TemplateRenderException(RESOURCE_LIMIT)` without depending on upstream's
unbounded runtime behavior.

## Why this is next

PR #20 completed WP6 item 1 by proving that one parsed template is safe to render concurrently.
The next numbered WP6 requirement is limit coverage. Some limit assertions already exist beside
their implementation tests: step/output share one test, lexer limits only assert rejection, and
`InterpreterTest` already pins the ordinary recursive macro-depth edge. This slice collects the
release contract in a dedicated boundary suite, pins the missing counter paths and every
pass/fail edge consistently, and leaves the corpus effectively unbounded as required by WP4's
parity gate.

## Scope and invariants

- Test only existing public limit knobs: `TemplateOptions.maxSourceLength`, `maxTokenCount`, and
  `maxAstDepth`; `RenderOptions.maxSteps`, `maxLoopIterations`, `maxMacroDepth`, and
  `maxOutputLength`. Do not add time, heap, recursion, or host-function-call limits in this slice.
- Every case has an exact success-at-limit assertion and a failure-one-below (or one-above for
  source/token/depth input) assertion. A rejection-only test cannot detect an off-by-one limit.
- Assert `ErrorCategory.RESOURCE_LIMIT`, a stable limit-specific message fragment, and the relevant
  source location where that is deterministic. Do not require Node message parity: these are local
  safety limits, deliberately absent from the oracle corpus.
- Keep each witness small, finite, and single-purpose. In particular, avoid templates where a
  lower-priority budget can fire first and make a passing assertion ambiguous.
- Exercise `range` separately from `for` materialization: `RenderBudget` has distinct
  `rangeElements` and `iterations` counters which share the configured `maxLoopIterations` ceiling.
  A `for` charges each source item during its pre-filter materialization pass, before evaluating
  the body; it does not charge body traversal. Both paths can regress independently.
- Preserve the current buffered String/Appendable contract. The output tests establish cumulative
  render charging, including block capture/filter/call re-rendering; they do not introduce
  streaming partial-write behavior.

## Implementation plan

1. **Add one dedicated public-boundary limit test class.**

   Create `src/test/java/se/alipsa/hfjinja/TemplateResourceLimitTest.java` in the public package.
   Drive parse-time checks through `Template.parse(source, options)` and render-time checks through
   the four supported `Template.render` entry points only where overload behavior matters. Do not
   construct `Lexer`, `Parser`, `RenderBudget`, or `Interpreter` directly; their existing focused
   tests remain implementation-unit coverage, while this class is the release-facing contract.

   Add compact helpers that return the thrown `TemplateRenderException` and assert category/message
   once per case. Use exact `SourceLocation` assertions for a stable token/AST/render witness, but
   do not fabricate a location for the pre-scan source-length rejection: the implementation
   correctly has no token location at that point, so assert its location is empty/null instead.

2. **Pin parse-time source, token, and AST-depth boundaries.**

   - Source length: parse a five-character plain-text template with `maxSourceLength(5)` and assert
     its output. With the same option, parse the explicit failing input `"abcde\n"`: its source
     length is six but preprocessing removes its final newline and would leave five. Assert
     RESOURCE_LIMIT, `"Source length 6 exceeds the configured limit of 5"`, and no location. This
     pins that the check happens before preprocessing.
   - Token count: use a small expression whose lexical token count is independently obvious (for
     example `{{ a }}`), establish the exact count from the token grammar in a comment, and parse
     it at that count. Set the limit to one fewer and assert RESOURCE_LIMIT at the start of the
     token that would exceed the limit. Do not derive the expected count by calling `Lexer` in the
     test; that would compare the scanner to itself and make the caller-graph non-vacuity mistake
     in another form.
   - AST depth: use `{{ ((1)) }}`, which is exactly depth two. Assert it renders at
     `maxAstDepth(2)` and fails at one with RESOURCE_LIMIT at offset five/line one/column six: the
     `1`, because `Cursor.nested` reports `locationHere()` after consuming the opening delimiter.
     Keep token count/source length generous so they cannot win first. Record why the syntax adds
     the two nesting levels and why the reported anchor is the literal rather than a parenthesis;
     this makes a parser refactor update the expectation deliberately rather than weakening it.

3. **Pin render step and loop boundaries with disjoint witnesses.**

   Use independently counted templates and deliberately high values for every unrelated render
   limit.

   - Steps: a sequence of simple output statements with a known number of top-level statements.
     Assert success at that number and RESOURCE_LIMIT at one fewer, at the first statement that
     cannot be charged. For `{{ 1 }}{{ 2 }}` at `maxSteps(1)`, pin offset ten/line one/column eleven:
     the `2`, rather than the second `{{` at offset seven, because `chargeStep` receives the
     expression statement's node location. The success output makes this more than a counter-only
     test.
   - `for` materialization: render `{% for x in [1,2] %}x{% endfor %}`. Set
     `maxLoopIterations` to two for success and one for failure. The charge occurs in the pre-filter
     materialization pass before any body runs; pin its failure at the `for` statement, offset zero,
     line one, column one.
   - Nested `for` materialization: use the two-level, two-by-three source fixture described below.
     Assert success at eight charges and failure at seven: two outer source items plus three inner
     source items materialized for each outer iteration. Pin the failing inner statement at offset
     20, line one, column 21. This guards the re-materialization multiplier a loop refactor could
     otherwise lose.

     ```jinja
     {% for x in [1,2] %}{% for y in [1,2,3] %}a{% endfor %}{% endfor %}
     ```
   - Range construction: render `{{ range(0, 3) }}` without a `for` body. Assert success at three
     and failure at two, anchored at the `range` identifier (offset three, line one, column four).
     This proves `chargeRangeElement`, which runs before any `for` evaluator, is covered separately
     from `chargeLoopIteration`.

   Assert the "Maximum loop iterations exceeded" message for all three failures. Include a short
   comment that one configured limit bounds two separate counters charged by different execution
   paths.

4. **Pin macro-depth entry/exit, including call-block invocation.**

   `InterpreterTest.macroDepthLimitIsExactlyEnforcedAtTheConfiguredBoundary` already uses the public
   API to prove a shallow recursive macro succeeds at ten and fails at nine. Keep that test in its
   existing interpreter-focused home rather than duplicating it. This suite adds a non-recursive
   repeated macro witness that succeeds with `maxMacroDepth(1)`, demonstrating `exitMacro()`
   returns the counter after each invocation rather than accumulating sequential calls.

   Include a separate call-block case (not only ordinary macro calls), because it enters the same
   budget via a distinct interpreter statement path. Count the macro/caller invocations it creates
   in a test comment, then give it an exact passing `maxMacroDepth` and one-less failing limit.
   Choose a shallow call block that avoids stack-overflow behavior; this is an exact budget
   boundary, not a JVM-stack test.

5. **Pin output accounting and Appendable atomicity at the public boundary.**

   Assert a one-character expression succeeds at `maxOutputLength(1)`. Builder rejection at zero
   is already covered for all render limits in `PublicApiTest`; do not duplicate it here. For a
   renderable boundary, use two charged characters: success at two and failure at one, asserting
   RESOURCE_LIMIT and "Maximum output length exceeded". Use `{{ 'a' }}{{ 'b' }}` and pin the
   failure to the second literal at offset 12, line one, column 13; output charging, like steps,
   receives the expression-node location rather than the opening delimiter.

   Add exact independent witnesses for the known cumulative paths, each with success at its complete
   charge and failure one below it; keep non-output budgets high. Use these measured templates and
   charges, rather than a broad in-between limit:

   - `{% set x %}abcdef{% endset %}{{ x }}`: 12 cumulative characters, six visible. The
     interpolation is required: the capture alone charges its body once and has no second charge.
   - `{% filter upper %}abcdef{% endfilter %}`: 12 cumulative characters, six visible.
   - `{% macro wrap() %}<{{ caller() }}>{% endmacro %}{% call wrap() %}abcdef{% endcall %}`:
     22 cumulative characters, eight visible.

   These assert that body and captured/filtered/callee output are charged at their separate points,
   which a loose limit between visible and cumulative output would not establish. Pin the exact
   `SourceLocation` for each one-below failure in the test, with a short comment identifying the
   charge point (captured-body text, filter-statement result, or call-statement result); do not
   reduce this to a category/message-only assertion.

   Run a failing output render through `render(context, appendable, options)` with a pre-populated
   `StringBuilder`, then assert the destination remains exactly unchanged. This checks the public
   buffering guarantee rather than comparing an Appendable to a live alias. Also assert the String
   overload's equivalent RESOURCE_LIMIT result as a public-contract restatement, while documenting
   that it delegates to the same Appendable implementation rather than treating it as an independent
   execution-path witness; successful appendable output is already covered by
   `TemplateConcurrencyTest`.

6. **Falsify each boundary family before accepting the suite.**

   After the normal focused suite passes, make temporary, uncommitted mutations and run
   `TemplateResourceLimitTest` after each:

   - Change the source/token/AST gates so the configured boundary is rejected (the exact operator
     differs because token counting checks before adding a token), one implementation at a time.
     Confirm the corresponding success-at-limit assertion fails, then revert.
   - Change one render counter comparison to reject equality and confirm its exact-at-limit success
     fails. Separately change it to allow one extra unit and confirm the one-below failure assertion
     fails. Use `chargeStep` for the two polarity checks, then repeat an equality-boundary mutation
     once on `chargeLoopIteration`, `chargeRangeElement`, and `chargeOutput` to demonstrate each
     counter path has power.
   - Make `exitMacro()` temporarily a no-op. Confirm the repeated sequential-macro assertion at
     `maxMacroDepth(1)` fails, then revert. The call-block boundary alone is intentionally
     insufficient for this falsification: its nested depth can still pass/fail correctly when
     completed invocations never decrement depth.
   - Mutate `enterMacro` separately, recording the correct polarity: because it checks
     `macroDepth >= max` *before* incrementing, changing `>=` to `>` loosens the boundary and must
     make the call-block fail-at-one assertion fail; tightening it (for example, testing the next
     depth too early) must make its pass-at-two assertion fail. Revert each mutation.
   - Temporarily remove the output charge for the filter result (or call-block callee result) and
     confirm the cumulative-output assertion fails, then revert.
   - Temporarily append directly to the supplied output before evaluation in `Template.render` and
     confirm the failing-Appendable unchanged assertion fails, then revert.

   Do not commit these mutations. Record focused-test failure evidence in the implementation PR
   description or commit notes. This falsification is required: a suite whose limits never become
   binding can pass vacuously.

7. **Keep existing unit tests only when they add non-duplicated detail, and verify.**

   Do not delete parser/lexer/interpreter unit tests merely because this public suite overlaps them;
   retain their white-box source-location and recursion diagnostics. Remove or simplify only a
   truly duplicate assertion if the new test provides the same boundary and failure evidence.

   Before interpreting a failure as a code defect, confirm JDK 21 and the Node version in
   `upstream/upstream-lock.json`. After Java edits, run:

   ```bash
   ./gradlew spotlessApply
   ./gradlew test --tests se.alipsa.hfjinja.TemplateResourceLimitTest
   ./gradlew check
   git diff --check
   ```

## Acceptance criteria

- The public API proves exact pass/fail boundaries for source, token, AST-depth, step, `for`
  materialization (including nested re-materialization), range materialization, macro/call-block
  depth, and output budgets.
- Every limit breach is a `TemplateRenderException` with `RESOURCE_LIMIT`, a limit-specific message,
  and the applicable stable source location (or no location for pre-scan source length).
- Cumulative output paths and a failed Appendable render preserve the documented buffered-output
  contract.
- The suite detects deliberately tightened and loosened counter comparisons, a no-op `exitMacro()`,
  removed cumulative output charging, and an early Appendable write; failure evidence is recorded
  without committing mutations.
- `./gradlew check` passes on JDK 21 with the locked Node version.

## Deliberately deferred

- New resource-limit options or changes to defaults/public API.
- Wall-clock cancellation, allocation/heap limits, host-function quotas, and streaming partial-write
  semantics.
- Licensing/NOTICE revalidation, publication/reproducibility/API documentation, tokenizer example,
  clean-checkout build, dependency review, and the release checklist (remaining WP6 items 3–5).
