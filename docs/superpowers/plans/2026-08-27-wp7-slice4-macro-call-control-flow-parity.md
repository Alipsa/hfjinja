# WP7 Slice 4 — Macro and Call-Block Control-Flow Parity

## Goal

Close the next bounded normal-rendering divergence in WP7 item 1 of
[`req/implementation-plan.md`](../../../req/implementation-plan.md): make a `break` or `continue`
executed in a macro body or a `{% call %}` body propagate to the nearest `for` loop currently
evaluating its body, just as it does in pinned `@huggingface/jinja` 0.5.9.

This follows Slice 3. It changes internal control transport only; it does not broaden the public
`Value.CallableValue` protocol, alter macro argument binding, or relax Java's render-budget and
host-function safety contracts.

## Evidence and scope

Upstream represents loop control as `BreakControl` and `ContinueControl` exceptions in
`runtime.ts`. A `for` catches them around `evaluateBlock(node.body, scope)`. Ordinary Java
`ExecResult.Break`/`Continue` values already reproduce this when the control statement occurs
directly in a loop body, filter block, or captured block. They cannot cross
`Value.CallableValue.Callable.invoke(...)`, which returns a `Value` rather than an `ExecResult`.
`Interpreter.evaluateMacro` and the `caller` closure in `evaluateCallStatement` therefore currently
turn a non-normal result into Java's `break or continue outside a for loop` `SYNTAX` error.

The Node 26.7.0 / upstream-0.5.9 oracle establishes these normal-rendering results:

| Shape | Pinned result | Meaning |
| --- | --- | --- |
| macro `continue` conditional on the first item | `"23"` | `continue` discards item 1 and proceeds with later items |
| equivalent macro `break` conditional on the first item | `""` | `break` discards item 1 and stops the loop |
| equivalent `{% call %}`-body `continue` / `break` through `caller()` | `"23"` / `""` | the caller closure preserves the distinct control kinds |
| macro control in a loop with `{% else %}` | `"E"` | no iteration completed normally, so upstream renders the loop else block |
| callable-originated `break` in a macro-owned inner loop | `"AZAZ"` | the inner loop, while evaluating its body, catches the transported control before the outer loop |
| macro emits `M` before breaking on item 2 | `"1M"` | completed prior iterations survive; only the controlling iteration is discarded |

The output before control is intentionally absent: upstream's exception unwinds through the
expression and statement that had begun accumulating it, and `evaluateFor` appends a loop body's
text only after that body completes normally. The Java port must preserve this rule for both
existing direct `ExecResult` control and the new callable-boundary transport.

Direct calls with no enclosing loop, for example
`{% macro f() %}{% break %}{% endmacro %}{{ f() }}`, are not corpus candidates. Upstream throws a
raw `BreakControl` error with an empty, unclassifiable message; Java deliberately retains its
stable `SYNTAX` `TemplateRenderException`. The same applies to an unguarded `continue` and to
call-block equivalents. This is a documented Java-only safety contract, not normal-rendering
parity.

## Compatibility contract

- A `break` or `continue` from a macro or `caller()` invocation crosses every callable, expression,
  conditional, filter-block, and call-statement frame until the nearest `for` loop *currently
  evaluating its body* handles it. This is deliberately narrower than the nearest lexically or
  dynamically enclosing loop: upstream catches control only around its loop body's
  `evaluateBlock` call, not while evaluating the iterable, a `for ... if` predicate, or the
  `{% else %}` block.
- That loop discards all text produced by its current body iteration, including text before the
  macro/call invocation. `break` stops the loop; `continue` moves to the next item. A loop's
  `{% else %}` block runs when no iteration completes normally, matching the existing direct-control
  behavior.
- An inner `for` remains the handler for control that originates within it, including when the
  inner loop is inside a macro. Control must never skip a nearer loop.
- Preserve `RenderBudget.enterMacro`/`exitMacro` in `finally` blocks. A transported control signal
  is not an error that may leak macro-depth accounting, and it must not be caught or wrapped as a
  host-function failure.
- Do not change the `Value.CallableValue.Callable` signature or make `ExecResult` public. The
  bridge is private to `Interpreter`.
- Direct, unhandled macro/call-block `break`/`continue` keeps Java's exact stable message
  `break or continue outside a for loop` and `SYNTAX` category. Do not add a catch-all classifier
  for the upstream's blank raw error.

## Implementation plan

1. **Add Node-backed control-flow records before production changes.**

   Add `wp7.*` records to `src/test/resources/corpus/v1.jsonl`, all sourced
   `self-authored; verified against @huggingface/jinja 0.5.9`. Each successful record needs a
   corresponding named `InterpreterTest` assertion until the deferred Java corpus runner exists.
   Use these minimum vectors:

   - `wp7.macro-continue-crosses-call-boundary` and
     `wp7.macro-break-crosses-call-boundary`: use
     `{% macro f(i) %}{% if i == 1 %}{% continue %}{% endif %}{% endmacro %}` (and the
     `break` variant) inside `{% for i in [1,2,3] %}{{ i }}{{ f(i) }}{% endfor %}`. The expected
     results are respectively `"23"` and `""`; do not use identical all-control vectors that
     could map transported `continue` to `break` without detection.
   - `wp7.call-block-continue-crosses-caller-boundary` and
     `wp7.call-block-break-crosses-caller-boundary`: use the same conditional body through a
     macro that invokes `caller()`, with results `"23"` and `""`. These prove that `caller()` has
     the same control transport as an ordinary macro call.
   - `wp7.macro-break-preserves-prior-iterations`: use the exact macro
     `{% macro f(i) %}M{% if i == 2 %}{% break %}{% endif %}{% endmacro %}` with
     `{% for i in [1,2,3] %}{{ i }}{{ f(i) }}{% endfor %}`. It renders `"1M"`. This pins that
     completed prior iterations remain while the current partial iteration, including its `M`, is
     discarded.
   - `wp7.macro-break-loop-else` and `wp7.macro-continue-loop-else`: separate records, each
     yielding `"E"`, for control on every item of a loop with an else block.
   - `wp7.macro-break-stops-at-inner-loop` and
     `wp7.macro-continue-stops-at-inner-loop`: use a *second macro* in the inner loop body, not a
     direct control statement. Let `g(z)` control only when `z == 1`; let `f()` evaluate
     `{% for z in [1,2] %}{{ z }}{{ g(z) }}{% endfor %}Z`; and let the outer loop render
     `{{ i }}{{ f() }}` for two `A` values. The break result is `"AZAZ"`; the continue result is
     `"A2ZA2Z"`. Add corresponding call-block-body variants, with the same respective results.
     This proves both that callable transport is caught by the nearer inner body loop and that the
     inner loop preserves the distinction between break and continue.
   - `wp7.macro-break-crosses-filter-block`: a macro control signal inside a filter block still
     reaches the outer loop and renders `""`. This proves the change composes with the existing
     `evaluateFilterStatement` non-normal propagation.
   - `wp7.macro-break-crosses-set-capture`: the same macro control signal from a `{% set x %}`
     capture inside a loop renders `""`, covering the sibling `evaluateSet` propagation path.
     The capture must emit the item before the macro call and the loop must render the capture,
     so swallowing control produces `"[1][2]"` instead.
   - `wp7.call-block-callee-break-crosses-call-boundary`: a callee macro's own `break` from
     `{% call f() %}x{% endcall %}` inside a loop likewise renders `""`; this complements the
     caller-body and uncalled-body vectors. The loop must emit the item before the call, so
     swallowing control produces `"12"` instead.
   - `wp7.macro-break-in-for-predicate` and `wp7.macro-break-in-for-else`: define
     `{% macro f() %}{% break %}{% endmacro %}`. For the predicate record use
     `{% for o in [1,2] %}O{% for i in [1,2] if f() %}{{ i }}{% endfor %}{% endfor %}`; for the
     else record use
     `{% for o in [1,2] %}O{% for i in [] %}{% else %}{{ f() }}{% endfor %}{% endfor %}`. Both
     render `""`: the inner loop is not evaluating its body when the signal occurs, so it escapes
     to the outer body loop. These prevent an over-broad `evaluateFor` try/catch around the
     filtering pre-pass or entire method, which would incorrectly render the explicit outer text
     `"OO"`.
   - `wp7.macro-default-control-balances-depth`: use
     `{% macro g(i) %}{% if i == 1 %}{% continue %}{% endif %}{% endmacro %}` and
     `{% macro f(i,a=g(i)) %}{{ i }}{% endmacro %}` with the exact outer body
     `{% for i in [1,2] %}{{ f(i) }}{% endfor %}`. Node's expected text is `"2"`. Add one Java
     assertion with default options to trace that corpus expectation, plus a second assertion with
     `RenderOptions.maxMacroDepth(2)`. The latter is the tightest limit that admits the nested
     `f`/`g` call yet fails when either `finally` leaks the first item's macro-depth entry; a
     looser limit is not an equivalent depth-accounting regression.

   Keep the existing direct loop `break`, filter-block control, macro/call happy-path, and loop-else
   tests as no-change guards. Add the missing direct-loop `continue` render regressions as plain
   Java-only `InterpreterTest` assertions, not `v1.jsonl` records, matching the existing direct
   break precedent:
   `{% for i in [1,2,3] %}{% if i == 1 %}{% continue %}{% endif %}{{ i }}{% endfor %}` → `"23"`,
   and `{% for i in [1,2,3] %}{% continue %}{% else %}D{% endfor %}` → `"D"`. The latter is the
   direct-control counterpart of `wp7.macro-continue-loop-else`, proving both transport forms
   preserve the same loop-else outcome. Do not put unhandled macro/call control in the corpus: the
   Node runner must continue to fail unmatched raw errors rather than silently assigning a
   category. Add Java-only assertions for direct macro and call-block `break` *and* `continue`,
   checking the stable message and `SYNTAX` category. Add a no-change regression where a
   call-block callee never invokes `caller()`: `{% for i in [1,2] %}{{ i }}{% call range(2) %}`
   `{% break %}{% endcall %}{% endfor %}` renders `"1[]2[]"`; touching call-block transport must
   not begin evaluating an uncalled body.

2. **Introduce a private callable-boundary control signal.**

   In `Interpreter`, add a private, stackless runtime signal carrying only `Break` or `Continue`
   (for example, a nested `LoopControl` with an `ExecResult.Break`/`ExecResult.Continue` payload).
   It is an implementation transport, never a `TemplateRenderException`, never a `Value`, and
   never part of `Value.CallableValue`.

   Add one private adapter that converts a non-normal result returned by `evaluateBlock` at a
   callable boundary into this signal, and otherwise returns `Value.StringValue` exactly as today.
   The signal must retain the callable invocation's `SourceLocation`; if it reaches the top-level
   Java-only safety catch, report that location rather than the program start. Add location
   assertions for bare macro and call-block `break` and `continue` so this diagnostic contract
   remains pinned.
   Use it in exactly the two closures that execute template blocks behind `Callable.invoke`:

   - the macro callable installed by `evaluateMacro`; and
   - the `caller` callable created by `evaluateCallStatement`.

   Preserve the surrounding `try/finally` macro-depth accounting. Remove only the old known-gap
   branches that manufacture `TemplateRenderException` at these two boundaries; do not change
   normal macro/caller output conversion, keyword handling, locations, or calls to ordinary
   builtins/host functions.

3. **Teach `evaluateFor` to catch both transport forms at the loop boundary.**

   Wrap its `evaluateBlock(n.body(), scope, b)` call so it catches the private control signal and
   handles its payload in the same branch as a returned `ExecResult.Break`/`Continue`. The catch
   must surround the whole body evaluation, not merely a direct macro/call invocation: that is what
   lets control cross expressions, `{% if %}`, `{% filter %}`, nested macro calls, and call
   statements.

   Keep append-after-normal semantics: the local `StringBuilder` in `evaluateBlock` is abandoned
   by exception unwinding, so no partial body text is available or should be appended. Keep `none`
   false only after a normal body completes, so the loop else behavior remains pinned. The catch
   must cover the body-evaluation call only; it must not wrap the iterable/filtering pre-pass or
   default block. Do not catch the signal in `evaluateBlock`, `evaluateIf`,
   `evaluateFilterStatement`, `evaluateSet`, or generic callable invocation. That transparency is
   an implementation choice consistent with `ExecResult` propagation, while body-only catching is
   the observable upstream contract. The existing inner `evaluateFor` catches first by normal
   exception unwinding when it is evaluating its body.

   At the top-level `render` boundary, catch a remaining private control signal alongside the
   existing non-normal `ExecResult` check and translate it to the existing Java-only `SYNTAX`
   exception. This covers direct macro/call invocations with no enclosing loop without exposing an
   internal exception. Retain the existing `StackOverflowError` backstop unchanged.

4. **Update documentation, mappings, and regression ownership.**

   Update the nearby `Interpreter` comments to reference this Slice 4 plan rather than the old
   WP5 known-gap text, and delete wording claiming callable-boundary control is intentionally
   rejected. Keep `macroBareBreak_isKnownDivergenceFromUpstream` and
   `callBlockBareBreak_isKnownDivergenceFromUpstream`: their names remain accurate for the direct
   raw-upstream-error shapes. Update their comments and add the missing direct `continue` peers.

   Add an Unreleased `Fixed` entry to `CHANGELOG.md` describing macro/call-block loop-control
   propagation. `upstream/mapping.yml` already maps `runtime.ts` to `Interpreter` and
   `InterpreterTest`; leave its status unchanged unless the implementation introduces a new mapped
   source or test file. Document raw unhandled upstream control in this plan's Evidence and scope
   section and in the comments on `InterpreterTest`'s
   `macroBareBreak_isKnownDivergenceFromUpstream` and
   `callBlockBareBreak_isKnownDivergenceFromUpstream` assertions (including their new continue
   peers). Tie those comments to WP7 item 4 in `req/implementation-plan.md`: category-level
   comparison is allowed only after documenting why. Here the reason is concrete: the upstream
   raw `BreakControl`/`ContinueControl` message is blank; `run-node-oracle.mjs` rejects an
   unclassifiable message rather than assigning a category, and the versioned patterns deliberately
   contain no blank-message catch-all. This narrow documented safety outcome does not waive WP7
   item 1's normal-rendering parity requirement.

5. **Falsify the mechanism and verify with the pinned toolchain.**

   Temporarily restore the two callable-boundary `TemplateRenderException` branches; the macro and
   call-block outer-loop records must fail. Temporarily map transported `Continue` to `Break`, or
   catch around the whole item loop rather than one body iteration; the `"23"` continue records
   must fail. Temporarily catch around all of `evaluateFor` (including filtering/default work);
   the predicate/else records must fail by rendering `"OO"` rather than `""`. Temporarily omit
   the top-level signal conversion; the Java-only direct-call assertion must reveal the escaped
   internal signal. Revert every mutation.

   Do not use a mutation that catches the signal in `evaluateBlock` and returns an
   `ExecResult`: existing transparent result propagation plus the callable adapters can make that
   observationally equivalent. Likewise, partial-output append is not a meaningful mutation—the
   exception has already abandoned `evaluateBlock`'s local builder. Those are implementation
   preferences, not falsifiable contracts.

   Confirm JDK 21 and Node 26.7.0, then run:

   ```bash
   ./gradlew spotlessApply
   node --test tools/corpus/corpus.test.mjs
   node tools/corpus/run-node-oracle.mjs --corpus src/test/resources/corpus/v1.jsonl --patterns tools/corpus/error-patterns-0.5.9.json --lock upstream/upstream-lock.json
   ./gradlew test --tests se.alipsa.hfjinja.internal.runtime.InterpreterTest
   ./gradlew corpusCoverage upstreamVerify
   ./gradlew check
   git diff --check
   ```

## Acceptance criteria

- Macros and call-block `caller()` bodies propagate distinct `break` and `continue` signals to the
  nearest loop currently evaluating its body, with byte-exact reviewed Node output.
- The current loop iteration's partial output is discarded, completed prior iterations survive,
  loop else behavior matches Node, and a callable-originated signal is caught by a macro-owned
  inner loop before it can reach the outer loop.
- The transport is private to `Interpreter`; no public callable/value API, host-function contract,
  or macro-depth accounting changes.
- Unhandled macro/call control remains a named Java-only `SYNTAX` safety outcome and is not forced
  into `v1.jsonl` by a weak error classifier.
- Every new corpus record has a traceable Java regression, and the full pinned-toolchain checks
  pass.

## Deliberately deferred

- The remaining WP7 normal-runtime divergences: undefined-key `tojson` ordering, two-key
  undefined-backed `dictsort`, undefined-backed `lower`, function-value rendering, and the full
  executable upstream-vector inventory/converter work.
- Parser exact-message and end-of-input raw-crash parity, as described in Slice 3's deferred work.
- A Java-side `v1.jsonl` runner. Until it exists, the explicit Java assertions in this slice remain
  required alongside Node corpus verification.
- WP6 publication/reproducibility work and the final release checklist.
