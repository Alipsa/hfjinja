# WP6 Slice 1 — Concurrent Rendering and Template Retention

## Goal

Close the first WP6 item from `req/implementation-plan.md`: prove that one parsed `Template` can
be rendered safely and independently by many threads, without lazy per-template state or retention
of a caller's context graph. This is a release-hardening test slice. It must not change template
language behavior, the public API, the pinned upstream package, or add a dependency.

## Why this is next

PR #19 closes WP5 item 4, the last remaining WP5 implementation item. WP5 item 5 was closed by
slice 7; item 3's separately tracked follow-up is importing retained model goldens into
`v1.jsonl`, which first needs a model-provenance schema change. WP6 is sequential after WP5. Its
first listed requirement is concurrent rendering of a single parsed template; parsing,
lexer/parser limits, render limits, packaging, and release review are separate WP6 work packages
and must not be bundled into this proof slice.

The present design is intended to satisfy the contract: `Template` stores one parsed
`Statement.Program`; each `render` call converts its supplied context and `Interpreter.render`
creates a new `Environment` and `RenderBudget`. The test must exercise that promise under genuine
parallel overlap, rather than merely rendering repeatedly in a loop.

## Scope and invariants

- Reuse exactly one `Template` instance across all workers. Parsing a template per worker does not
  test the contract.
- Give every render its own context, `StringBuilder`/`Appendable`, and immutable `RenderOptions`.
  `Appendable` thread safety is not part of `Template`'s guarantee.
- Exercise stateful language paths: scoped assignment, loop metadata, a macro/call block, a filter
  block, and a context object that is mutated by `{% set object.key = ... %}`. This detects leaked
  `Environment`, macro, loop, and converted-value state; literal-only rendering would not.
- Do not use timing assertions as correctness evidence. A start barrier creates overlap; bounded
  waits and a separate-thread JUnit timeout make a deadlock fail rather than hang the test worker.
- Do not introduce caches, synchronization, static render state, or mutable fields to make a test
  pass. Rendering state belongs to an invocation, not the immutable parsed template.
- This slice validates the current String and Appendable contracts. Streaming/partial-write
  behavior and resource-budget boundaries belong to later WP6 slices.

## Implementation plan

1. **Add a public-boundary concurrent-render test.**

   Create `src/test/java/se/alipsa/hfjinja/TemplateConcurrencyTest.java`. Keep it in the public
   package so it tests only supported `Template`, `RenderOptions`, and exception behavior; do not
   reach into the interpreter to manufacture concurrency.

   Parse one feature-rich template once. It should accept a per-call `id` and mutable nested
   `state`, assign `state.seen`, run a loop using `loop.index`, define/invoke a macro through a
   call block, apply a filter block, and set both `state.seen` and `ns.seen`, where `ns` is created
   with `{% set ns = namespace(seen=false) %}`. A zero-argument `namespace()` returns an
   `ObjectValue`; the keyword argument is required here because it makes `namespace` return its
   `KeywordArgumentsValue` bag verbatim. The two assignments therefore exercise `evaluateSet`'s
   `ObjectValue` and `KeywordArgumentsValue` targets. Each worker supplies a unique id and fresh
   `LinkedHashMap`/`ArrayList` graph, renders with the same immutable options, and asserts one
   precisely derived output containing only that worker's id and loop values. Take an independent
   deep copy (or construct an independent expected graph) before rendering, then compare the
   caller-owned graph to it after every render; host conversion must copy the graph before template
   assignment.

   Use a 16-thread `ExecutorService`, submit **exactly one task per pool thread**, and loop several
   rounds *inside* each task. Only round zero participates in `CountDownLatch ready/start`; later
   rounds begin after that common start. This cannot starve the latch by queuing more barrier tasks
   than there are pool threads. Call `ready.await` with a bound, release `start`, and use
   `Future.get(timeout, unit)` for every worker; include worker id/round in propagated failures.
   Use a daemon `ThreadFactory`. In `finally`, call `shutdown`, use bounded `awaitTermination`,
   then call `shutdownNow` and bounded `awaitTermination` again if graceful termination fails.
   Annotate the test with `@Timeout(value = ..., unit = SECONDS, threadMode =
   ThreadMode.SEPARATE_THREAD)`. The timeout is a final deadlock guard, not render-performance
   evidence; the bounded latch/future/termination operations are the primary failure paths.

2. **Cover both output overloads and option isolation.**

   Divide the concurrent calls deterministically among all four public overloads:
   `render(context)`, `render(context, options)`, `render(context, appendable)`, and
   `render(context, appendable, options)`, using a distinct `StringBuilder` for every Appendable
   call. Assert equal expected text from both default-options overloads and both explicit-options
   overloads. The default overloads are exercised directly rather than assumed from delegation.
   Put any host-function call behind an id/context-keyed branch: default-overload workers leave it
   false, while explicit-options workers set it true. This permits one template to exercise the
   default API without registering a host function and the explicit API with one.

   Include a per-render `HostFunction` whose result incorporates that call's id, registered in one
   shared immutable `RenderOptions` object. Its implementation may use an `AtomicInteger` only to
   count calls; it must not supply worker identity from shared mutable state. Place the call in the
   explicit-options branch *before* the id-keyed raise branch, so every explicit-options render,
   including those that subsequently raise, invokes it exactly once. Assert the final count equals
   the known number of explicit-options calls. This confirms the interpreter takes the
   function/options read-only while each invocation retains its own scopes and converted arguments.

   Make a deterministic subset of workers take an id-keyed `{% if %}` branch that calls
   `raise_exception(id)`. Assert `TemplateRenderException`, its `EXPLICIT_RAISE` category, and a
   message containing that worker's id. Continue collecting all worker rounds and assert adjacent
   successful workers retain their exact expected output. This is the abort-path isolation check:
   a failed render must not leak its environment or budget into another render.

3. **Pin template non-retention/non-cache structure with a narrow white-box assertion.**

   Add a separate test in the same class that parses once, renders distinct caller graphs, and
   verifies via reflection that `Template` has exactly one declared non-synthetic field: `program`,
   of type `Statement.Program`, and `Modifier.isFinal(...)`. Assert its value is unchanged before
   and after many renders. This is intentionally mechanical; do not attempt to infer what an
   arbitrary `Object`-typed field might retain. If a future legitimate immutable parse cache is
   proposed, update this invariant only after reviewing its thread safety and retention properties.
   Do not use `WeakReference`/forced GC: collection timing is nondeterministic and cannot prove
   non-retention in CI.

   This is the first test that would need adjustment if tests move to a strict module-path setup:
   reflecting on the private `Template` field would then require introducing a `--patch-module`
   arrangement plus a qualified `opens`. Record that build dependency next to the reflection helper
   rather than weakening the assertion.

   The behavioral half of this check is the nested caller-graph mutation assertion in step 1. It
   proves a render never reuses a prior conversion result; the structural half prevents silently
   adding a place on `Template` to retain those results.

4. **Falsify the test before accepting it.**

   After the tests pass normally, make two temporary, uncommitted mutations and run the focused
   test after each:

   - Add a mutable `Template` field that caches the last converted `Value.ObjectValue` and reuse it
     on a later render. Confirm the step-1 isolated-output/caller-graph assertions fail, then
     revert the mutation.
   - Hoist `Environment` from `Interpreter.render` into static mutable state, and deliberately
     reuse it through `setVariable` rather than constructing a fresh environment. Confirm the
     step-1 isolated-output assertions fail from cross-render state leakage, then revert the
     mutation. Do not use ordinary `Environment.set` for this mutation: its redeclaration check
     instead produces a `TemplateRenderException(VALUE)`, which demonstrates failure but does not
     falsify the isolation-output assertions.
   - Leave the temporary `Template` field in place long enough to confirm the step-3 exact-field
     structural assertion fails, then revert it.

   Record the focused-test failure evidence in the implementation PR description or commit notes;
   do not commit either mutation. This demonstrates that the test has power both against leaked
   runtime state and against the retention/cache shape it claims to prevent.

5. **Update release-facing documentation only if evidence exposes a mismatch.**

   README already promises immutable templates that are safe for concurrent rendering and says to
   parse once and reuse across threads. Leave it unchanged when the suite passes. If the test finds
   a real limitation, first fix the implementation and then correct the relevant README/API
   documentation in the same change; do not weaken the promise merely to make the test pass.

6. **Verify in the required toolchain.**

   Before interpreting failures as code defects, confirm `java -version` is JDK 21 and
   `node --version` is the lockfile's pinned version. After any Java edits, run:

   ```bash
   ./gradlew spotlessApply
   ./gradlew test --tests se.alipsa.hfjinja.TemplateConcurrencyTest
   ./gradlew check
   git diff --check
   ```

   `check` is the release-slice gate: it re-runs the Node oracle, upstream verification, and the
   existing deterministic fuzz suite as well as the new concurrency test.

## Acceptance criteria

- One parsed template is rendered concurrently with isolated, deterministic output for every
  worker and round.
- Loop, macro/call/filter, both assignment targets, host-function, explicit-failure, and all four
  output overload paths show no cross-render state leakage.
- Caller-owned nested context graphs equal their independently captured pre-render state after
  every render.
- `Template` retains only immutable parsed state; the test catches a future lazy per-template
  context/render cache before it ships, as shown by the deliberate cache mutation.
- The deliberate shared-`Environment` mutation makes the behavioral isolation assertions fail.
- The focused test and `./gradlew check` pass on JDK 21 with the locked Node version.

## Deliberately deferred

- Individual source/token/AST-depth/step/loop/macro/output limit boundary tests (WP6 item 2).
- NOTICE/licensing revalidation, publication/reproducibility/API docs, consumer tokenizer example,
  clean-checkout build, dependency review, and release checklist (WP6 items 3–5).
- Render/output fuzzing and shared-Appendable semantics.
