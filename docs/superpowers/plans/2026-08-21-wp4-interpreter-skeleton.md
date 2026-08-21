# WP4 Slice 1 — Interpreter Skeleton: Environment, Control Flow, Render Budget

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn `Template.render(...)` from a stub (`throw unsupported()`) into a working renderer for
the non-expression-heavy, non-filter, non-macro subset of the language: `Program`, `SetStatement`,
`If`, `For`, `Break`, `Continue`, `Comment` (renders nothing), literals (including tuples),
`Identifier`, `MemberExpression` (dot/computed, no slices), and `CallExpression` restricted to the 9
builtin globals and host functions — including turning every `Value` this subset can produce into
actual output text. This is
the first of four WP4 slices; it establishes the `Environment`/scope model, the `Value`→text
rendering, and the render-time `RenderBudget` that every later slice builds on.

**Architecture:** A new `se.alipsa.hfjinja.internal.runtime` package — matching upstream `runtime.ts`
1:1, and matching the literal package path `build.gradle`'s `upstreamVerify` task already hardcodes
for it (`plannedPackages['runtime.ts'] == 'internal/runtime'`, see Global Constraints). The package
exposes exactly **one** public type, `Interpreter`, with one public entry point; everything else
(`Environment`, `RenderBudget`, `ExecResult`) stays package-private, called only by
`Interpreter` itself — see Task 2/8. The one exception, `JsFormat` (Task 4), deliberately does
**not** live in `internal.runtime` — it lives flat in `se.alipsa.hfjinja.internal`, `public`, same
posture as `Values`/`HostFunctions`, so Slice 2 can make the existing test-only
`AstSnapshot.number()`/`AstSnapshot.q()` delegate to it without a relocation first. Named `JsFormat`,
not `JsNumberFormat` — it hosts both the shared number-formatting core and the shared JSON
string-quoting core (`quote(String)`, moved here from `Interpreter.renderJson` in Task 4's own
revision, see Task 0), so a number-only name would already be wrong on arrival. `Interpreter` is a Java 21
pattern-matching `switch` over the
`Statement`/`Expression` sealed hierarchy from WP3, evaluating against an `Environment` scope chain
and a per-render `RenderBudget`. Values flow through WP2's existing `internal.Value` sealed model
(restructured in Task 1 to add a `TupleValue` variant and to nest its implementations, matching the
`Statement`/`Expression` convention); host calls go through WP2's existing
`internal.HostFunctions.invoke(...)` — both need a visibility change before anything in
`internal.runtime` can reference them at all (Task 1).

**Tech Stack:** Java 21 (sealed interfaces, records, pattern-matching switch), Gradle (offline), JUnit 5,
Node (oracle only, never a runtime dependency).

**Spec:** [`req/project-description.md`](../../../req/project-description.md) — WP4 steps 1, 2 (partial), 5
of [`req/implementation-plan.md`](../../../req/implementation-plan.md); working toward delivery gate **G4**
(closed by Slice 4, not this slice).

## Global Constraints

Copied from the spec and from the WP3 plan; every task's requirements implicitly include this section.

- **No production dependencies.**
- **Only `se.alipsa.hfjinja` is exported.** `module-info.java` must not gain an export.
  `internal.runtime` (new, one public type) and the widened `internal.Value`/`internal.Values`/
  `internal.HostFunctions`/`internal.JsFormat` (Tasks 1 and 4) stay internal by package naming
  and API convention, not by module exports — same posture already documented at `Token.java:9-11`
  for exactly this cross-package situation.
- **Port upstream one-to-one where it's the actual runtime behavior.** Keep upstream's function
  names, order, and structure. Port upstream bugs and quirks — Task 5's two `noIteration` cases,
  Task 6's tuple-unpack rejection, Task 4's TupleValue-render failure and nested-undefined text,
  Task 7's string-out-of-range case — do not "fix" them. This includes **keeping upstream's exact
  method names** on `Environment` (`set`, `setVariable`, `lookupVariable`).
- **Exception: break/continue use internal result variants, not exceptions.** Pre-approved deviation
  from upstream in *mechanism* only — the *effect* (discarding whatever output was accumulated
  between the break/continue and the nearest enclosing `for`) is ported exactly. See Task 5.
- **`upstream/mapping.yml`'s `runtime.ts` entry flips to `status: implemented` starting in Task 2,**
  not deferred to Slice 4. `build.gradle:350-356` fails the build the moment
  `src/main/java/se/alipsa/hfjinja/internal/runtime/` exists on disk while that entry still says
  `planned`, and Task 2 is the first task that creates that directory. The `implemented` branch
  only checks that every named file in `java:`/`tests:` actually exists — no notion of "fully done" —
  so each task appends its new files to those two lists in the same commit. **The `java:`/`tests:`
  values must stay single physical lines** — `build.gradle:303-306` parses them per-line, and the
  `implemented` branch's regex requires the inline-flow-list form. Growing the list across eight
  commits means editing that one line in place each time, never reformatting to a YAML block list.
  Add a short YAML comment on the entry noting this is Slice 1 of a multi-slice port and linking this
  doc.
- **`utils.ts` stays `planned` this slice.** Task 7 needs `range()`; keep it as a small
  package-private helper inside `internal.runtime` rather than standing up a separate
  `internal/util` package, which would trip the identical guard for `utils.ts`.
- **The Java build is offline.** Node runs only in the explicit oracle/corpus tasks.
- **No static mutable state in the interpreter.** A fresh `Environment` and `RenderBudget` per
  `render()` call; no lazy memoization, no cross-render caches.
- **Java 21 toolchain**, JUnit 5, two-space indent, no wildcard imports.
- **`{@code}` spans must contain balanced braces.** Run
  `javadoc -private -Xdoclint:all -Xmaxwarns 100000` before committing, not just `./gradlew javadoc`.
- **`upstream/upstream-lock.json` is JSON** — it cannot carry a rationale comment the way
  `mapping.yml` (genuine YAML) can.
- **A new sealed-interface variant is never "currently unused."** Adding `Value.TupleValue` (Task 1)
  immediately makes every exhaustive `switch` over `Value` non-exhaustive until it gets a `case
  TupleValue` arm — this is a compile error, not a latent gap, and the task that adds the variant
  must fix every such switch in the same commit. (One exists today: `Values.toHost`. Grep for
  `case ArrayValue` before assuming that's still the only one by the time this slice finishes — Task
  4 adds a second, in `Interpreter.renderText`.)
- **There are two dispatch methods, not one, because their return types differ — and both must be
  exhaustive over their own direct permitted subtypes from the moment each first exists.**
  `ExecResult evaluateStatement(Statement, Environment, RenderBudget)` handles `Statement`'s 10 direct
  leaves plus one delegating `case Expression e ->` arm; `Value evaluateExpression(Expression,
  Environment, RenderBudget)` handles `Expression`'s 18 leaves. (28 leaves total across both — counted
  directly from `Statement.java`'s and `Expression.java`'s own `permits`/`record` declarations, not
  upstream's `ast.ts` — but Java's pattern-switch exhaustiveness is computed over **direct** permitted
  subtypes only: `evaluateStatement`'s switch needs 11 conceptual arms, not 28, since one `case
  Expression e -> ...` fully discharges the `Expression` branch without enumerating its 18 leaves —
  verified directly against `javac`, not assumed from `Statement.java`'s doc comment. Fully flattening
  is legal, as `AstSnapshot.emit` already does, but never required.) The split exists because
  `Statement` dispatch must return `ExecResult` — `Break`/`Continue` are control flow, not values —
  while `Expression` dispatch must return a plain `Value`, feeding `MemberExpression`'s object,
  `CallExpression`'s arguments, `For`'s iterable, and `SetStatement`'s rhs; one method cannot return
  both, and `ExecResult.Normal(String)` carries rendered text, not a `Value`, so it isn't a usable
  common return type either. `evaluateStatement`'s delegating `case Expression e ->` arm is exactly an
  expression used in statement position (e.g. `{{ expr }}`) — it computes `evaluateExpression(e, ...)`,
  renders the result via `Interpreter.renderText` (Task 4), and wraps it as `ExecResult.Normal(...)`,
  which is also where `chargeOutput`/`chargeStep` naturally hook in and mirrors upstream's own
  single-`evaluate` handling of an expression statement. Every leaf either method doesn't implement yet
  gets an explicit arm throwing a categorized `TemplateRenderException(..., ErrorCategory.<fitting
  category>, location)` stating which later slice or work package implements it — never
  `UnsupportedOperationException`, which is uncategorized and would escape the public API the same way
  the raw `IllegalStateException` in Task 9's global/context-collision fix would have. Each later task
  **replaces** its own placeholder arm with real logic rather than adding a new one. See Task 5's own
  note for the exact per-method list and which slice or WP owns each placeholder.

## Task 0 — Review history for this plan

Twelve review passes happened before implementation started. Recorded here so the corrected task list
doesn't read as a first draft.

**First pass — seven bugs, all fixed:** inverted break/continue discard semantics; unaddressed
package-private visibility on `Value`/`Values`/`HostFunctions`; a package name that evaded
`build.gradle`'s `plannedPackages` guard; an unverified and wrong `contextShadowsGlobals` claim (10
colliding names, not 9); a wrong out-of-range-string-indexing claim; a backwards duplicate-object-key
claim; inverted `Environment` method names relative to upstream.

**Second pass — four more bugs, all fixed:** Task 1's own fix didn't compile (widening 9 top-level
types in one file repeats the violation it had just diagnosed for `Values` — fixed by nesting, per
`Statement.java`'s convention); the new `internal.runtime` types repeated the same visibility bug
Task 1 had just fixed (fixed by giving the package exactly one public type, `Interpreter`); collapsing
`TupleLiteral` into `ArrayValue` silently loses an observable divergence (a tuple is iterable but not
unpackable — fixed by adding a real `Value.TupleValue` variant); `RenderBudget.chargeOutput` was
specified and never called anywhere (fixed by wiring it into `evaluateBlock`'s output accumulation).

**Third pass — five more bugs, fixed in this revision:**

1. **Adding `Value.TupleValue` breaks `Values.toHost`'s exhaustive switch, and the previous revision's
   Task 1 Step 5 claimed the opposite** ("PASS, unchanged behavior... currently-unused variant").
   A sealed-interface addition is immediately load-bearing for every exhaustive switch over it — this
   is a compile error, not a dormant no-op. Fixed: Task 1 now adds `case TupleValue` to
   `Values.toHost`, converting it the same way as `ArrayValue` (to an unmodifiable `List<Object>`,
   via a shared helper so the ~15-line array-conversion body isn't duplicated), and states plainly
   that the conversion is one-way — nothing in `fromHost` ever produces a `TupleValue` (only a real
   Java `List` converts, and it always becomes `ArrayValue`), so a tuple that crosses to a host
   function and back returns as an `ArrayValue`, not the tuple it started as.
2. **`TupleValue` rendering is a fourth quirk that went unmentioned.** `ArrayValue.toString()` calls
   `toJSON`, which switches on the string type tag; `TupleValue`'s overridden tag `"TupleValue"`
   matches none of `toJSON`'s cases and falls to `default: throw new Error("Cannot convert to JSON:
   TupleValue")` (`runtime.ts:386-388`). `{{ (1, 2) }}` throws; `{{ [1, 2] }}` renders `[1, 2]`. Same
   mechanism as the unpack-target quirk, a different call site. Fixed in the new Task 4.
3. **`Value`→text rendering was entirely unspecified, and "What's Next" wrongly deferred all of it to
   Slice 2 — even though `evaluateBlock`'s very first test in the previous revision already depended
   on it.** `Java`'s `List.toString()` gives `[a, b]`, not the JSON-quoted `["a", "b"]` this language
   actually renders; `IntegerValue`/`FloatValue` differ in formatting for the same `double`; a
   container's own numbers, nested `null`/`undefined`, and a nested tuple all format differently from
   the top-level scalar case. This was reachable the moment `For`/`MemberExpression` could produce a
   non-string value (e.g. `loop.index`), i.e. within this slice, not the next one. Fixed: a new Task
   4 implements it, extracting the ECMA number-formatting core `AstSnapshot.number()`
   (`src/test/java/.../parser/AstSnapshot.java`) already has correct, rather than reimplementing it a
   second time and risking drift — "What's Next" now correctly describes Slice 2's remaining job as
   *unifying* the two copies (a cross-package test-code change), not extracting for the first time.
4. **Nothing converted `Environment.set`'s `IllegalStateException` into the categorized error Task
   2's own corpus record and classifier pattern assert.** As previously written,
   `Template.parse("{{ range }}").render(Map.of("range", 5))` would have thrown a raw
   `IllegalStateException` out of the public API — not even an `HfJinjaException` — while the corpus
   record (exercised only against the Node oracle this slice) would have kept passing, silently
   deferring the divergence to Slice 4. Fixed in the wiring task (now Task 8): global/context seeding
   catches `IllegalStateException` and rethrows `TemplateRenderException(message,
   ErrorCategory.VALUE, program.location())`.
5. **The tuple-unpack-rejection corpus record (added in the `For`-loop task) has no classifier
   pattern**, and by the time it's added the pattern table only has one prior entry — `corpus.mjs`'s
   `errorClassifier` throws on any unmatched message, the identical trap Task 2 already documents.
   Fixed: that task now adds `^Cannot unpack non-iterable type: (?<type>.+)$` → `ErrorCategory.TYPE`
   in the same step as the corpus record.

**Fourth pass — four more bugs, fixed in this revision:**

1. **Task 4 contradicted itself on whether `renderJson` delegates back to `render(Value)`, and the
   version an implementer would trust (the one explaining the switch arms) produces wrong output.**
   Following that paragraph literally gives `{{ [2.0] }}` → `[2.0]` instead of the correct `[2]`
   and `{{ ["a","b"] }}` → unquoted garbage instead of `["a", "b"]` (both verified against
   the pinned build) — because a nested `StringValue`/`FloatValue` would have been formatted by the
   *top-level* rules, not the JSON ones. Fixed: `render` and `renderJson` are now stated as strictly
   disjoint — `render` handles top-level statement results only and never recurses into element
   formatting; `renderJson` is fully self-contained and self-recursive and never calls `render`. This
   also makes `render`'s `NullValue`/`UndefinedValue` arms genuinely unreachable (not a "nested case"
   as previously, wrongly, claimed) — they now throw `AssertionError`, honestly labeled defensive.
2. **`renderFloat`'s `.0`-append heuristic is not equivalent to `toFixed(1)`, and the plan's one
   self-declared "not independently verified" gap is now closed rather than left open.**
   `Number::toString` emits the shortest round-tripping form; `toFixed` expands the exact binary
   value; they diverge for doubles in `2^53 ≤ |v| < 1e21` whose shortest form isn't exact (verified:
   `123456789012345680000` mismatches). Fixed: the integral branch now uses
   `new BigDecimal(value).setScale(1, RoundingMode.UNNECESSARY).toPlainString()` below the `1e21`
   threshold (needing no rounding-mode decision, since the branch only runs on an exact integer) and
   falls back to `plainString` above it, matching `toFixed`'s own documented fallback. Verified at
   `1e20`, `1e21`, `Number.MAX_VALUE`, and the mismatch case above.
3. **`JsNumberFormat`'s planned location (package-private inside `internal.runtime`) permanently
   blocked the Slice 2 unification this plan promises**, and the File Structure table's parenthetical
   ("extracted (not duplicated)") contradicted Task 4's own honest admission that Slice 1 ships two
   copies. `AstSnapshot` is test code in the different package `internal.parser`; a package-private
   class in `internal.runtime` is invisible to it, so Slice 2 couldn't "unify" without first moving or
   widening the class itself. Fixed: `JsNumberFormat` now lives flat in `se.alipsa.hfjinja.internal`,
   `public`, same posture as `Values`/`HostFunctions` — this is the one deliberate exception to
   `internal.runtime`'s "exactly one public type" rule, and is called out as such. Slice 2's job
   shrinks to deleting `AstSnapshot.number()`'s body in favor of a call; no relocation needed then.
4. **The `render(Value)` sketch threw with a `null` location**, the one position-less error in a plan
   that otherwise threads `SourceLocation` through every other error site (`raise_exception`, every
   `charge*` call, the `IOException` wrapper). Fixed: `render`/`renderJson` both take a
   `SourceLocation` parameter; `evaluateBlock` (Task 5) already has one in hand for
   `chargeOutput(length, location)`, so threading it costs nothing extra.

**Follow-ups folded in without separate numbering:** `ExecResult.Break`/`Continue` are enum
singletons, not per-occurrence allocations; break/continue with no enclosing `for` loop is resolved as
an explicit, oracle-unbacked judgment call (the raw thrown error has an empty message, which the
corpus/classifier mechanism cannot represent at all); the `For`-loop task's test list covers the two
other upstream error paths this slice's port contains; `Appendable` writes wrap `IOException` as
`ErrorCategory.OUTPUT`; `range()`'s exact signature is confirmed; the wiring task now says plainly
that context is seeded via `set` (upstream's own `index.ts:41-43` calls `env.set` for both globals and
context, which is *why* the collision throws — there was never a real fork to resolve); the File
Structure table's description of `InterpreterTest.java` is corrected to match Task 5's actual claim
that its early tests call package-private `evaluateXxx` methods directly, not the public entry point,
which doesn't exist until the final task.

**Fifth pass — one bug (two divergences), fixed in this revision:**

1. **Task 4 reused two test-code implementations (`AstSnapshot.number()`, `AstSnapshot.q()`) whose
   correctness waivers assumed a narrower input domain than rendering actually has**, and the plan's
   stated validation strategy — mirroring `AstSnapshot.number()`'s own test cases — was by
   construction blind to both, since the source's tests encode the narrower (lexer-only) domain.
   `renderJson`/`renderText` receive arbitrary host-supplied values via `Values.fromHost`, a strictly
   wider domain than "whatever the lexer can produce." Two concrete divergences, both verified against
   the pinned Node build: (a) **subnormals** — `AstSnapshot.number()`'s own javadoc already declares
   and waives `Double.toString`'s non-shortest form for subnormals (`Double.MIN_VALUE` → `"4.9E-324"`
   in Java vs. `"5e-324"` in JS), on the premise that "the lexer cannot emit a subnormal" — a premise
   that doesn't hold for rendering, since `ValuesTest.acceptsSubnormalNumbersUsingTheJsShortestForm`
   already proves a host-supplied `Double.MIN_VALUE` produces a real, renderable `FloatValue`; (b)
   **lone surrogates** — `AstSnapshot.q()` doesn't escape unpaired UTF-16 surrogates the way
   `JSON.stringify` does (confirmed: `JSON.stringify("a\ud800b")` emits the six literal characters
   `\ud800`), and a host-supplied string can contain one where lexer-produced source text effectively
   never does. Fixed: rather than chasing a fix disproportionate to an interpreter-skeleton slice (a
   genuinely-shortest subnormal formatter, or full surrogate-pair-aware JSON escaping), both waivers
   are now restated explicitly at render scope — `JsNumberFormat`'s javadoc states each divergence
   plainly, `JsNumberFormatTest` pins the current, documented-wrong Java output for the subnormal case
   so it can't silently drift, and the lone-surrogate gap is added to "Known Gaps" rather than being
   left for a reader to discover on their own.

**Fifth-pass suggestions addressed:** Task 4's package-private value→text method is renamed
`renderText` (from `render`) so it no longer collides in name, with nothing in common, with Task 8's
unrelated public `Interpreter.render(Program, ObjectValue, RenderOptions, Appendable)` — every task
referencing it (4 through 8) is updated; `chargeOutput`'s "detects rather than prevents" limitation
(a single oversized value is fully allocated before the budget check can reject it) is now stated
explicitly in Task 5, the same way the `Appendable` no-streaming-inside-loops case and the `1e21`
`toFixed` boundary are already stated rather than hidden; the fourth-pass changelog's garbled
`{{ [2.0] }}` → `[2]` sentence is corrected to `{{ [2.0] }}` → `[2.0]` (the actually-wrong output the
bug produced); the Global Constraints export bullet now lists `internal.JsNumberFormat` alongside
`internal.Value`/`internal.Values`/`internal.HostFunctions` as a fourth widened-but-unexported type.

**Sixth pass — one bug, fixed in this revision:**

1. **The fifth pass's own fix for the lone-surrogate divergence put the waiver on the wrong class, and
   the test it specified for that waiver would not have compiled.** `JsNumberFormat` (per the File
   Structure row and Task 4's "two thin callers") is number-only — `plainString(double)`/
   `jsonString(double)`. String JSON-quoting was, and per the fifth-pass text remained, specified as
   living inline in `Interpreter.renderJson`, package-private in the different package
   `internal.runtime`. The fifth-pass text nonetheless described "`JsNumberFormat`'s string-quoting
   javadoc" (a method that class doesn't have) and assigned the lone-surrogate assertion to
   `JsNumberFormatTest`, which lives in `internal` and cannot see a package-private method in
   `internal.runtime` — the identical cross-package visibility trap this plan has now caught multiple
   times elsewhere (Task 1's own first attempted fix, the original `internal.runtime` type-visibility
   design). Fixed: the string-quoting core (`quote(String)`, ported from `AstSnapshot.q()`) moves into
   the same shared class as the numeric cores, which is renamed `JsFormat` (no longer number-only, so
   no longer named as if it were) — `Interpreter.renderJson`'s `StringValue` case now calls
   `JsFormat.quote(...)` rather than reimplementing the rules inline. This also means Slice 2's
   promised unification now correctly covers `AstSnapshot.q()` as well as `AstSnapshot.number()` (both
   become delete-the-body-in-favor-of-a-call), and the lone-surrogate assertion, now `JsFormatTest`
   exercising `JsFormat.quote` directly, actually compiles.

**Sixth-pass suggestion addressed:** the subnormal waiver's stated justification ("out of proportion
for an interpreter-skeleton slice") didn't hold — a general (not subnormal-special-cased) fix is a
~6-line addition to the already-ported digit-formatting core (a bounded search over
`BigDecimal(v).round(new MathContext(p))` for the shortest round-tripping precision), verified against
the pinned build across every subnormal boundary tested with no regression on any previously-correct
case. Adopted rather than re-justifying a waiver on a cost estimate that was off by an order of
magnitude: the subnormal divergence is now fixed in `JsFormat`'s shared core (Task 4), with the
narrower, honestly-stated residual that `MathContext`'s `HALF_UP` tie-breaking is unproven — not
confirmed, not refuted — equivalent to ECMA's own tie-breaking rule at the exact digit-count boundary,
since no constructed tie case was available to test either way.

**Seventh-pass suggestions addressed, including a correctness finding surfaced while checking
them:** two suggestions — guard `shortestDigits` on `Double.MIN_NORMAL` so normal-range doubles
short-circuit straight to `Double.toString` instead of entering the search loop, and write the
subnormal test inputs in their JS print form (`1e-323`/`1e-322`) rather than Java's own print form for
the identical double (`9.9E-324`/`9.9E-323`), so the assertions read as self-evidently correct instead
of looking like typos. Both adopted. Checking the guard's justification directly (rather than trusting
the "0 divergences" sample size alone) surfaced something the sixth pass's own text got wrong: it had
asserted, unverified, that the unguarded loop "lands on the same answer [as `Double.toString`]
immediately" for every non-subnormal double. Measuring 300,000 random normal doubles shows that's
false — `HALF_UP` rounding of the exact binary value and `Double.toString`'s own algorithm can pick
different, both-valid, round-tripping decimals at an exact rounding-tie boundary (e.g.
`5.804742410468122E14`'s exact value `580474241046812.25`, equidistant between two 16-digit
candidates), at a rate of roughly 1 in 4,400 in this sample — a real latent regression the unguarded
version of the sixth pass's fix would have shipped for ordinary (non-subnormal) numbers, not a
subnormal-only concern as previously assumed. The guard fixes this as a side effect of adopting it for
performance: normal-range doubles now always resolve via `Double.toString` directly, never entering
the tie-prone search, so output for every previously-correct case is byte-for-byte unchanged by
construction rather than "verified unaffected" by a sample that happened not to hit the tie case. The
residual tie-breaking caveat is corrected accordingly: unproven equivalence is now correctly scoped to
the subnormal range the guard doesn't cover, not "every double" as the sixth pass's phrasing would have
implied.

**Eighth-pass suggestions addressed:** two suggestions — close the residual tie-breaking caveat by
proof instead of leaving it as "unproven" in shipped javadoc, and give `shortestDigits` the same inline
precondition comment `renderFloat` already has. Both adopted. For the first: a subnormal
`v = k · 2⁻¹⁰⁷⁴` (`1 ≤ k < 2⁵²`) has an exact decimal expansion that terminates at `1074 − t ≥ 1023`
fractional digits with a nonzero final digit (writing `k = 2ᵗ·k'`, `k'` odd, makes the terminating
numerator odd, hence never divisible by 10) — roughly 700+ significant digits given the exponent range,
independently confirmed by sampling the exact `BigDecimal` expansion of subnormal bit patterns
1..4,500,000 (736–758 significant digits, every case). A `HALF_UP`/ECMA tie at precision `p` needs the
exact value to carry exactly `p + 1` significant digits; `p ≤ 17` in the search, so a tie is
structurally unreachable there — proof, not sample size, closes the case, and an independent 5,000-case
`HALF_UP`-vs-`HALF_DOWN` comparison found zero divergences, consistent with the reported 600,000-sample
check. The Seventh pass's "unproven — not confirmed, not refuted" hedge is replaced with this closed
argument; `JsFormat`'s javadoc states the residual as settled, not open. For the second: verified
`shortestDigits(Double.NaN)` throws `NumberFormatException: Infinite or NaN` from `new BigDecimal(NaN)`
— the `Double.MIN_NORMAL` guard doesn't catch it, since any comparison with `NaN` is `false` — so the
snippet now carries the same kind of precondition comment `renderFloat`'s already does, since the real
call path (`plainString`/`jsonString` mapping non-finite values before the core runs) isn't visible from
the snippet in isolation.

**Ninth pass — two bugs, both fixed, one requiring a new task:**

1. **`SetStatement` was in the Goal line and genuinely M2-scoped, but no task implemented it.** It
   appeared exactly once in the whole document (the Goal sentence); `evaluateSet`
   (`runtime.ts:1559-1595`) was never cited, and it was absent from "Known Gaps" and "Deliberately not
   touched" — meaning it wasn't a stated deferral, just a missing task. Verified: `ast-allowlist.json`
   tags it M2 (in scope), `Statement.SetStatement`'s shape matches `evaluateSet`'s three assignee forms
   (`Identifier`, `TupleLiteral`, `MemberExpression`) plus a block-capture form exactly, and the
   `MemberExpression` form is genuinely blocking — `ObjectValue`'s compact constructor wraps its map in
   `Collections.unmodifiableMap` (`Value.java:52-57`), so `object.value.set(key, rhs)` cannot be ported
   as an in-place mutation without a decision. Fixed: a new **Task 8** ports all four forms, including
   the block-capture form's interaction with `ExecResult` (a `{% break %}`/`{% continue %}` inside `{%
   set x %}...{% endset %}` propagates immediately, discarding the assignment, per Task 5's own
   discard rule). The `ObjectValue` fork is resolved, not left open: made internally mutable (dropping
   the `unmodifiableMap` wrap, keeping the defensive copy) in **Task 1**, verified safe because the
   host-function immutability guarantee the record-level wrap was defending already lives one level up
   at `Values.toHost`'s own independent recursive copy (`Value.java:118-137`) — a host function was
   never handed the interpreter's internal map in the first place. The alternative (rebind a fresh
   `ObjectValue` on every `{% set obj.key = ... %}`) was rejected explicitly: it would silently break
   upstream's real JS reference/aliasing semantics, which this project's own "port bugs and quirks, do
   not fix them" rule exists to preserve. Old Task 8 (the wiring task) renumbers to Task 9; every
   current-design cross-reference to it is updated, historical Task 0 entries referring to "Task 8" as
   it stood at the time they were written are left alone.
2. **The plan applied "a new sealed-interface variant is never currently unused" to `Value` only —
   `Statement`/`Expression` have 28 leaves (10 + 18, counted directly from `permits`/`record`
   declarations, not upstream) and only 14 were named across Tasks 5–7.** Unaddressed: `SetStatement`
   (bug 1, now Task 8), `Comment`, `Macro`/`FilterStatement`/`CallStatement`, and 9 expression types.
   `Comment` is not merely a compile arm — `Parser.java:57-59` really emits `Statement.Comment`, and
   upstream's `case "Comment": return new NullValue()` (`runtime.ts:1866-1867`), filtered out by
   `evaluateBlock`'s own `NullValue`/`UndefinedValue` skip, means `{# ... #}` renders nothing — real,
   in-scope M1 behavior the plan never stated. Fixed: a new Global Constraints rule requires
   `Interpreter.evaluate`'s switch to be exhaustive the moment Task 5 first writes it, with a
   categorized `TemplateRenderException` placeholder arm (never `UnsupportedOperationException`) for
   every leaf not yet implemented, replaced task-by-task rather than added to. Task 5 now implements
   `Comment` for real (`ExecResult.Normal("")`) alongside `Program`/`If`/`Break`/`Continue`, and lists
   all 23 placeholder arms it must add with which task/slice/WP replaces each. The suggested
   `UnsupportedOperationException` category didn't survive verification as literally proposed —
   grepping the current plan text found no existing mention of it near `SelectExpression`, so that
   specific attribution wasn't reproducible from the document as written; the underlying category
   concern was real regardless: `ErrorCategory` has 10 constants (confirmed by reading
   `ErrorCategory.java` directly), none named for "valid syntax this slice doesn't implement yet,"
   since upstream has no equivalent situation to defer to. Decision, stated rather than left open:
   `ErrorCategory.UNDEFINED_OR_ACCESS`, reasoning by analogy from its own doc ("an access target does
   not support the attempted operation") generalized from a value's supported operations to this
   build's supported AST node types, rather than inventing an eleventh public constant for a
   slice-sequencing artifact. `Ternary` — also missing from "Known Gaps" despite being named in "What's
   Next" — is added there too.

**Tenth pass — one bug in the Ninth pass's own fix, one suggestion, both addressed:**

1. **The Ninth pass's own exhaustive-switch rule mis-stated Java's actual requirement, and described a
   single method that cannot typecheck.** Verified directly against `javac`, not assumed: exhaustiveness
   for a pattern switch over a sealed type is computed over its *direct* permitted subtypes only. A
   switch over `Statement` needs 11 conceptual arms (10 direct records plus `Expression`), and a single
   `case Expression e -> ...` fully discharges the `Expression` branch without enumerating any of its 18
   leaves — confirmed with a minimal reproduction compiled against this project's own JDK, not inferred
   from `AstSnapshot.emit`'s existing (fully-flattened, but never required to be) style. "Java requires
   it to cover all 28" was simply wrong. Separately, and more substantively: one method cannot serve
   both dispatch roles regardless of exhaustiveness, because `Statement` dispatch must return
   `ExecResult` (`Break`/`Continue` are control flow) while `Expression` dispatch must return a plain
   `Value` (feeding `MemberExpression`'s object, `CallExpression`'s arguments, `For`'s iterable,
   `SetStatement`'s rhs) — no shared return type exists, and `ExecResult.Normal(String)` carries
   rendered text, not a `Value`, so it isn't a fallback common type either. This was already a live bug,
   not a hypothetical: Task 8's own block-capture line assigned `rhs` from either `evaluate(node.value(),
   env)` (implicitly a `Value`) or `evaluateBlock(node.body(), env)` (an `ExecResult`) to the same
   variable, and grepping all prior text found `evaluate`'s signature and return type stated nowhere in
   the document — the one load-bearing dispatch API in the slice had no declared shape. Fixed by
   restating, not redesigning: `ExecResult evaluateStatement(Statement, Environment, RenderBudget)` (10
   real/placeholder arms plus one delegating `case Expression e -> ExecResult.Normal(renderText(
   evaluateExpression(e, ...), e.location()))`) and `Value evaluateExpression(Expression, Environment,
   RenderBudget)` (18 arms). Every placeholder-count number from the Ninth pass survives unchanged — 23
   at Task 5's Step 3 (5 in `evaluateStatement`, 18 in `evaluateExpression`), 12 at slice end (3 + 9) —
   only the framing changes from "one switch over 28" to "two switches over 11 and 18." Every loose
   `evaluate(...)` reference elsewhere in the plan (Task 8's rhs line, the assignee-dispatch comparison,
   Known Gaps) is renamed to the specific method that actually applies; references to upstream's own JS
   `evaluate()` are left alone, since that's a different, correctly-named thing.
2. **Suggestion adopted:** `ObjectValue`'s record-derived `hashCode()` is content-based and now changes
   under mutation (Task 1). Verified nothing today is exposed to this: `Values.toHost`'s `converted`/
   `sourceValues` are `IdentityHashMap`s (immune by construction), and `Environment`'s map holds `Value`
   only as a value, never a key (`grep`-confirmed empty for any `Value`-keyed `Set`/`Map` in `src/main`/
   `src/test`). Still a real latent trap for whoever first puts a `Value` in a hash-based container as a
   key. Added one javadoc sentence to `ObjectValue` (Task 1): mutable by design for `{% set obj.key =
   ... %}`, therefore never safe as a hash-container key.

**Eleventh pass — two bugs, both in the Tenth pass's own fix, both fixed:**

1. **The delegating arm the Tenth pass introduced calls `renderText` unconditionally, so `{{ none }}`
   and `{{ missing_var }}` — an undefined context variable, the single most common case in real
   templates — would crash with `AssertionError`.** Task 4's justification for making `renderText`'s
   `NullValue`/`UndefinedValue` arms throw rests on exactly one premise: upstream's `evaluateBlock`
   filters those two types out *before* calling `.toString()` (`runtime.ts:1457-1459`), so `renderText`
   never actually sees them. After the Tenth pass's split, `evaluateBlock` receives `ExecResult`, not
   `Value` — the conversion to text already happened in the delegating arm — so upstream's filter has
   nowhere left to run, and nothing re-homed it. Verified against the pinned oracle: `{{ none }}` and
   `{{ missing_var }}` both render `""`. Fixed by moving the skip into the delegating arm itself, exactly
   where the `Value` is still in hand: `NullValue`/`UndefinedValue` become `""` directly, everything else
   goes through `renderText`. This restores Task 4's unreachability premise verbatim rather than
   changing it; nested `Null`/`Undefined` inside a container are untouched, still rendered as
   `"null"`/`"undefined"` by `renderJson`.
2. **Two contradictory `chargeOutput` call sites existed after the split, and the stale one
   double-counts nested output.** The pre-split "`chargeOutput` wiring" paragraph, never updated by the
   Tenth pass, said to charge in `evaluateBlock` "using `Interpreter.renderText(Value, location)` ... to
   produce that contribution's text" — impossible after the split, since `evaluateBlock` no longer sees
   a `Value` to render. Worse, the design was already wrong before the split: charging per statement in
   `evaluateBlock` double-counts whenever output aggregates upward through nesting — a `For`
   statement's `ExecResult.Normal` output already contains everything its body's statements charged in
   the nested block evaluation, so `{% for i in [1,2,3] %}XXXX{% endfor %}` would charge the body's 12
   characters once during the loop's own inner evaluation and again when the `For`'s aggregated
   12-character result reaches the outer block — 24 charged for 12 rendered characters, compounding with
   nesting depth, silently shifting where `maxOutputLength` actually fires by an amount that depends on
   template structure rather than real output size. Fixed: `chargeOutput` moves to be the delegating
   arm's own responsibility, and only there — `Parser.java:61,70-72` makes raw template text an
   `Expression.StringLiteral` directly in a body list, so every output-producing leaf, at any nesting
   depth, passes through that one arm exactly once. `chargeStep` is explicitly **not** moved — it stays
   in `evaluateBlock`, once per statement, which was already correct: steps don't aggregate the way
   output does, so no double-count existed there. The two counters now charge at different, deliberately
   decoupled points instead of the single "same point" the pre-split paragraph asserted.

**Twelfth pass — one bug in this round's own consolidation, two suggestions, all addressed:**

1. **The `SelectExpression` loop pre-filter is unbounded — all three counters are blind to it, and this
   round's consolidation didn't change that.** Upstream evaluates the filter test once per **raw**
   candidate, in a throwaway per-candidate `Environment`, before any survival decision
   (`runtime.ts:1624-1669`, scope allocation at `:1627`) — verified by reading the actual pre-filter loop
   directly, not inferred. After this round's consolidation: `chargeStep` only fires in `evaluateBlock`,
   once per statement — the filter test isn't a statement, so the whole `For` counts as one step
   regardless of candidate count; `chargeLoopIteration` explicitly charges "once per surviving item
   processed, not once per raw candidate" (Task 6, unchanged by this round); `chargeOutput` only fires
   for text, and the filter produces none. `{% for x in range(10000000) if none %}{% endfor %}` is
   reachable this slice (`range()` is Task 7; an `Identifier` filter test is the simplest case Known Gaps
   already scopes as supported) and performs 10,000,000 expression evaluations and 10,000,000
   `Environment` allocations while charging exactly 1 step and 0 loop iterations. Fixed with the
   one-line change that matches the counter's own name: `chargeLoopIteration` now charges once per
   **candidate considered** in the pre-filter, not once per survivor; `loop.length`/`loop.revindex*`
   still compute against the post-filter count, unaffected, since that's upstream's own `items.length`
   and a separate concern from what the render budget charges.
2. **Suggestion adopted:** state what `maxSteps` now measures. No task charges inside
   `evaluateExpression` itself, so `{{ f(g(h(deeply.nested[chain]))) }}` is one step regardless of how
   many expression nodes it recursively evaluates — `maxSteps` bounds statements executed, not
   expression-tree work done. Added one paragraph to Task 3 stating this plainly, since its
   `maxSteps = 10_000_000` default was reasoned about back when a step was charged more finely.
3. **Suggestion adopted:** the `chargeOutput` paragraph's "only ever aggregate already-charged text
   upward" claim, read as "charged ⟺ emitted," has two real exceptions this slice creates: a
   `{% continue %}` after `{{ big }}` charges the discarded text's length before the discard, and `{%
   set x %}...{% endset %}` charges the captured text once at capture and again on every later `{{ x
   }}`. Both are defensible as work performed rather than output produced, matching this slice's other
   "detect, don't prevent" limitations — but the paragraph read as though the two were identical. Added
   one clause: `chargeOutput` bounds text produced, not text that reaches the caller.

## File Structure

**Created:**

| File | Responsibility |
| --- | --- |
| `src/main/java/se/alipsa/hfjinja/internal/Values.java` | Split out of `Value.java` (Task 1) — the host-boundary conversion utility (`fromHost`/`toHost`/`fromHostFunctionReturn`), now `public` so `internal.runtime` can call it. Gains a `case TupleValue` arm (Task 1). |
| `src/main/java/se/alipsa/hfjinja/internal/JsFormat.java` | `public final class`, same cross-package-access javadoc treatment as `Values`/`HostFunctions` (`Token.java:9-11` precedent) — lives flat in `internal`, not in `internal.runtime`, specifically so Slice 2 can make `AstSnapshot.number()`/`AstSnapshot.q()` (test code in the different package `internal.parser`) delegate to it later without moving anything. Named `JsFormat`, not a number-only name, because it hosts two shared cores: the ECMA `Number::toString` digit-formatting core (ported from `AstSnapshot.number()`'s existing implementation, with a subnormal-range correctness fix — see Task 4) plus the two thin callers `plainString`/`jsonString` that differ only in NaN/±Infinity handling; and the JSON string-quoting core (`quote(String)`, ported from `AstSnapshot.q()`, called by `Interpreter.renderJson` rather than duplicated there). **Not yet delegated to by `AstSnapshot`**; that unification is Slice 2's job. |
| `src/main/java/se/alipsa/hfjinja/internal/runtime/Interpreter.java` | The package's **sole public type**. `public final class Interpreter` with one public static entry point that constructs a root `Environment`/`RenderBudget` internally, evaluates `Statement.Program`, and writes to the caller's `Appendable`, wrapping any `IOException` as `ErrorCategory.OUTPUT`. Also holds the package-private `evaluateXxx`/`renderText(Value, SourceLocation)`/`renderJson(Value, SourceLocation)` methods built up task by task. |
| `src/main/java/se/alipsa/hfjinja/internal/runtime/Environment.java` | Package-private. Scope chain: parent + `Map<String, Value>`; `set`/`setVariable`/`lookupVariable`, named and behaving exactly like upstream. |
| `src/main/java/se/alipsa/hfjinja/internal/runtime/ExecResult.java` | Package-private sealed result type used by block evaluation instead of upstream's `BreakControl`/`ContinueControl` exceptions. Only `Normal` carries output; `Break`/`Continue` are enum singletons. |
| `src/main/java/se/alipsa/hfjinja/internal/runtime/RenderBudget.java` | Package-private. Mutable per-render counters (steps, loop iterations, output length) with `charge*` methods that throw `TemplateRenderException(..., ErrorCategory.RESOURCE_LIMIT, location)` on exhaustion. |
| `src/test/java/se/alipsa/hfjinja/internal/runtime/EnvironmentTest.java` | Declare/set/lookup/shadowing behavior. |
| `src/test/java/se/alipsa/hfjinja/internal/JsFormatTest.java` | Digit-formatting, NaN/Infinity-divergence, and string-quoting behavior in isolation — lives alongside `ValuesTest.java`, matching `JsFormat`'s own package, and specifically able to test `quote(String)` directly (unlike testing it through `renderJson`, which would need cross-package access `InterpreterTest` doesn't have to a package-private method). |
| `src/test/java/se/alipsa/hfjinja/internal/runtime/InterpreterTest.java` | Rendering, control-flow, and expression-evaluation behavior. **Tasks 4–8's tests call the package-private `evaluateXxx`/`renderText` methods directly** (same file is in-package, so this needs no special access); only Task 9's tests also exercise the public `Interpreter.render(...)` entry point, which doesn't exist before then. |
| `src/test/java/se/alipsa/hfjinja/internal/runtime/RenderBudgetTest.java` | Counter/exhaustion behavior in isolation. |

**Modified:**

| File | Change |
| --- | --- |
| `src/main/java/se/alipsa/hfjinja/internal/Value.java` | Widen `sealed interface Value` to `public sealed interface Value`; nest all 8 existing implementations as members (no per-member `public` modifier needed); update the `permits` clause; **add a ninth variant, `Value.TupleValue`** (same shape as `ArrayValue`, distinct type). Remove the now-relocated `Values` class. |
| `src/main/java/se/alipsa/hfjinja/internal/HostFunctions.java` | Widen the class and `invoke(...)` from package-private to `public`. |
| `src/test/java/se/alipsa/hfjinja/internal/HostFunctionsTest.java`, `.../ValuesTest.java` | Add imports for the now-nested `Value.XxxValue` types. |
| `src/main/java/se/alipsa/hfjinja/Template.java` | Replace the two `throw unsupported()` bodies with a call to `Interpreter.render(...)`; convert context via `Values.fromHost(context)`; delete the dead private `unsupported()` helper. |
| `src/main/java/se/alipsa/hfjinja/RenderOptions.java` | Add `maxSteps`/`maxLoopIterations`/`maxOutputLength` fields, accessors, and builder methods, mirroring `TemplateOptions`'s three-limit pattern. Thread the new defaults through the existing `DEFAULT` field. |
| `src/test/java/se/alipsa/hfjinja/PublicApiTest.java` | Add default-value and non-positive-rejection assertions for the three new `RenderOptions` limits. |
| `upstream/mapping.yml` | `runtime.ts` → `status: implemented`, `java:`/`tests:` grown incrementally task-by-task on one physical line each. |
| `upstream/upstream-lock.json` | Delete the unverified `contextShadowsGlobals` field. |
| `tools/corpus/error-patterns-0.5.9.json` | Add patterns for "Variable already declared" (Task 2), "Cannot unpack non-iterable type" (Task 6), "Cannot convert to JSON" (Task 4), and the five `SetStatement` messages — "Cannot unpack non-iterable type in set", "Too few/many items to unpack in set", "Cannot unpack to non-identifier in set", "Cannot assign to member of non-object", "Cannot assign to member with non-identifier property" (Task 8). |
| `src/test/resources/corpus/v1.jsonl` | Add self-authored records (`self.` id prefix — see Task 2): the shadowing case, the TupleValue-render rejection, the two `For`-loop `noIteration` quirks, the tuple-unpack rejection, and Task 8's five `SetStatement` error cases plus the `{% set obj.key %}` aliasing-mutation case. |

**Deliberately not touched this slice:** `upstream/mapping.yml`'s `utils.ts`/`index.ts` entries (stay
`planned`), `upstream/ast-allowlist.json` (a static node→milestone map — nothing is ever "removed"
from it), and `tools/corpus/run-node-oracle.mjs` / `nodeCorpusVerify` (no Java-side corpus runner
exists until Slice 4).

## Known Gaps This Slice Leaves Open (by design — later slices close them)

- **No arithmetic.** `BinaryExpression`/`UnaryExpression` full semantics are Slice 2.
- **Only the truthiness this slice's own control flow needs.** `BooleanValue`, `StringValue` (empty
  is falsy), `IntegerValue`/`FloatValue` (zero is falsy), `ArrayValue`/`TupleValue`/`ObjectValue`
  (empty is falsy), `NullValue`/`UndefinedValue` (always falsy).
- **No filters or tests.** `FilterExpression`/`TestExpression`/`SelectExpression`'s `test` clause
  dispatch, and `Ternary`, are Slice 3.
- **No macros, `caller()`, `{% filter %}` blocks, slices, or keyword/spread arguments** — all M3 per
  `upstream/ast-allowlist.json`, all WP5-scoped.
- **Every `Statement`/`Expression` leaf not listed above as "this slice" gets a placeholder arm**
  (`TemplateRenderException`/`UNDEFINED_OR_ACCESS`, naming the construct and the slice/WP that
  implements it) rather than being silently missing from `evaluateStatement`'s or `evaluateExpression`'s
  switch — see the Global Constraints exhaustive-switch rule and Task 5's placeholder table for the
  exact 12 that remain placeholders at the end of this slice.
- **`MemberExpression`'s "builtin bound method" fallback** (e.g. `"x".upper()`, `dict.get()`) is
  Slice 3, alongside filters. `Value`→text rendering (Task 4) does *not* need this — it renders
  values, not the callable members attached to them.
- **`tojson`, `sort_keys`, `indent`, and every other `toJSON` option** are out of scope. Task 4's
  `renderJson` implements exactly the one fixed configuration `ArrayValue`/`ObjectValue.toString()`
  itself uses (`{}` options, `depth=0`, `convertUndefinedToNull=false`) — not the general-purpose
  `tojson` filter, which is Slice 3's job and takes real options.
- **Lone (unpaired) UTF-16 surrogates in a host-supplied string are not escaped by `JsFormat.quote`
  (called from `Interpreter.renderJson`),** unlike `JSON.stringify`. Ported as-is from
  `AstSnapshot.q()`'s rules, whose own narrower domain (lexer-producible source text) never exercised
  this case. A known, stated gap for this slice, not a silent one — see Task 4's
  number-formatting/string-quoting section.

---

### Task 1: Restructure `Value` for cross-package access, and add `TupleValue`

**Files:**
- Modify: `src/main/java/se/alipsa/hfjinja/internal/Value.java`
- Create: `src/main/java/se/alipsa/hfjinja/internal/Values.java`
- Modify: `src/main/java/se/alipsa/hfjinja/internal/HostFunctions.java`
- Modify: `src/test/java/se/alipsa/hfjinja/internal/HostFunctionsTest.java`,
  `src/test/java/se/alipsa/hfjinja/internal/ValuesTest.java`

**Why nesting, not eight new public files.** `Value.java` currently holds nine top-level types: the
sealed interface `Value` itself, plus its 8 implementations, all package-private. Java allows at most
one `public` top-level type per file, matching the filename — the same problem this task already has
to solve for the separate `Values` utility class (Step 2). `Statement.java`/`Expression.java` already
nest their record variants as *members* of the sealed interface rather than as file siblings; nesting
the 8 (soon 9) implementations inside `public sealed interface Value` makes them public with no
per-member annotation, in one file, matching that convention exactly.

**Adding `Value.TupleValue`.** Upstream's `TupleValue extends ArrayValue` (`runtime.ts:535-536`,
`override type = "TupleValue"`) is structurally identical to `ArrayValue` but a distinct runtime tag:
real JS subclassing means `instanceof ArrayValue` still matches it, while every string-tag check
against `"ArrayValue"` does not. Add `Value.TupleValue` as a structurally-identical sibling variant
(`record TupleValue(List<Value> values) implements Value {}`, same shape and defensive-copy behavior
as `ArrayValue`).

**Adding a variant means fixing every exhaustive switch over `Value` in the same commit — there is
exactly one today.** `Values.toHost` (moving to `Values.java` in Step 2) is a `switch (value)` with no
`default`; it will not compile once `TupleValue` exists without a matching `case`. Add one, and decide
what it means at the host boundary now rather than leaving it to be discovered later: **a
`Value.TupleValue` converts exactly like `Value.ArrayValue` — an unmodifiable `List<Object>`.** Factor
the ~15-line array-conversion body (cycle-safe `converted` lookup, per-element recursion with
`HostPath` bookkeeping, `sourceValues` registration) into a small private helper taking the element
`List<Value>` and the original `Value` for identity-map keys, and call it from both `case ArrayValue`
and `case TupleValue`. **State the asymmetry explicitly, in code comment and test:** this conversion
is one-way. Nothing in `fromHost` (the *other* direction — converting a plain Java `List` into a
`Value`) ever produces a `TupleValue`; a `List` always becomes `ArrayValue`. A tuple that crosses to a
host function argument and is echoed back by that function therefore returns as an `ArrayValue`, not
the `TupleValue` it started as — a real, if narrow, round-trip lossiness, not a bug to fix here.

**Making `ObjectValue` internally mutable — required by Task 8's `{% set obj.key = ... %}`, and safe.**
`ObjectValue`'s compact constructor currently wraps its map in `Collections.unmodifiableMap(new
LinkedHashMap<>(values))` (`Value.java:52-57`). Upstream's `evaluateSet` mutates a `MemberExpression`
assignee in place — `object.value.set(key, rhs)` (`runtime.ts:1579-1582`) — and that mutation must be
visible through every alias of the same object (e.g. `{% set y = x %}{% set y.a = 2 %}{{ x.a }}` must
render `2`, matching upstream's real JS reference semantics), so rebinding a fresh `ObjectValue` into
the environment on every `{% set obj.key = ... %}` is not an option — it would silently break aliasing,
the one thing this project's own "port upstream bugs and quirks, do not fix them" rule exists to
prevent. Drop the `unmodifiableMap` wrap; keep the defensive `new LinkedHashMap<>(values)` copy so an
external reference passed into the constructor can't alias the record's internal map. **This does not
weaken the host-function safety the record-level immutability was defending** — that guarantee already
lives one level up, at the actual host boundary: `Values.toHost`'s own `case ObjectValue` arm builds an
independent, freshly recursively-converted `LinkedHashMap`, wrapped in its own `Collections
.unmodifiableMap(values)` (`Value.java:118-137`, specifically line 133), before anything reaches a
`HostFunction`. A host function was never handed the interpreter's own `ObjectValue.values()` map in
the first place, so making that internal map mutable changes nothing a host function can observe.
Verified no existing test relies on `ObjectValue.values()` throwing on mutation (`grep -rn ObjectValue
src/main src/test` — `ValuesTest.java:117` only asserts the null-rejection, `HostFunctionsTest.java`'s
`ObjectValue` usages are record-equality checks, unaffected by mutability either way since `Map.equals`
compares contents, not wrapper type).

**One javadoc sentence to pin a latent trap, since a mutable record still has content-derived
`equals`/`hashCode`.** Nothing today puts a `Value` in a content-hash-based `Set`/`Map`: `Values.toHost`'s
`converted`/`sourceValues` are `IdentityHashMap`s (identity hash codes never change under mutation, so
they're unaffected regardless), and `Environment`'s `Map<String, Value>` holds `Value` only as a *value*,
never a key (verified — `grep -rn "HashSet<Value\|HashMap<Value\|Map<Value\|Set<Value"` across
`src/main`/`src/test` returns nothing). But a mutable `ObjectValue` whose `hashCode()` is still
content-derived is exactly the shape of bug that survives fine today and corrupts silently the first
time someone puts one in a `HashSet<Value>` or uses one as a `HashMap` key after a `{% set obj.key =
... %}` mutation changes its hash bucket out from under the container. Add one sentence to
`ObjectValue`'s javadoc: mutable by design, for `{% set obj.key = ... %}` (Task 8); never safe as a
hash-based container key as a result. Costs nothing now and pins the constraint where the next person
touching this type will actually read it, rather than leaving it to be rediscovered as a bug report.

- [ ] **Step 1: Restructure `Value.java`.**
  - Change `sealed interface Value` to `public sealed interface Value`.
  - Move each of the 8 existing implementations inside the interface body as nested members.
  - Add the ninth variant, `TupleValue`, alongside `ArrayValue`.
  - Drop `ObjectValue`'s `Collections.unmodifiableMap(...)` wrap, keeping the defensive
    `new LinkedHashMap<>(values)` copy — see the mutability note above; needed by Task 8.
  - Update the `permits` clause to the qualified nested names, matching `Statement.java`'s own
    `permits Statement.Program, Statement.If, ...` style:
    `permits Value.UndefinedValue, Value.NullValue, Value.BooleanValue, Value.IntegerValue,
    Value.FloatValue, Value.StringValue, Value.ArrayValue, Value.TupleValue, Value.ObjectValue`.
  - Add the `Token.java`-style cross-package-access javadoc paragraph to `Value` itself.
  - Remove the `Values` class body from this file (moving to Step 2).

- [ ] **Step 2: Create `Values.java`** with the `Values` class (`public final class Values`, same
  cross-package-access javadoc treatment). Update every reference to a variant type
  (`UndefinedValue`, `BooleanValue`, etc.) to either an explicit `import
  se.alipsa.hfjinja.internal.Value.XxxValue;` per variant or a `Value.XxxValue` qualification.
  **Add `case TupleValue` to the `toHost` switch**, per the design above — the switch will not
  compile without it. Write a dedicated test (`ValuesTest`, Step 5) exercising this new case and the
  one-way-conversion assertion, not just a happy-path pass-through.

- [ ] **Step 3: Widen `HostFunctions.java`** — `final class HostFunctions` → `public final class
  HostFunctions`, `static Value invoke(...)` → `public static Value invoke(...)`. Same javadoc
  treatment.

- [ ] **Step 4: Fix the two existing white-box tests.** `HostFunctionsTest.java` and `ValuesTest.java`
  reference the variant types by simple name; add the same per-variant imports Step 2 needed.

- [ ] **Step 5: Add the `Values.toHost(TupleValue)` test**, asserting: a `TupleValue` converts to an
  (unmodifiable) `List` with the same elements as the equivalent `ArrayValue` would; a `List` argument
  passed through `fromHost` and back never reconstructs as `TupleValue` (round-trips as `ArrayValue`).
  Also add a direct `ObjectValue`-mutability test: constructing one and calling `.values().put(...)`
  succeeds (no `UnsupportedOperationException`), while `Values.toHost` on the same instance still
  returns a map that throws on `.put(...)` — pinning both halves of the Task 1 decision above in one
  place, not just describing it in prose.

- [ ] **Step 6: Run the full existing suite.** Run: `./gradlew check --offline` — Expected: PASS. This
  is a structural/visibility change plus one additive variant that is *immediately exercised* by
  Step 5's new test, not "unchanged behavior with nothing observable yet" (correcting the previous
  revision's wrong framing of this step).

- [ ] **Step 7: Run `javadoc -private -Xdoclint:all -Xmaxwarns 100000`** and confirm zero new
  warnings.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/se/alipsa/hfjinja/internal/Value.java \
  src/main/java/se/alipsa/hfjinja/internal/Values.java \
  src/main/java/se/alipsa/hfjinja/internal/HostFunctions.java \
  src/test/java/se/alipsa/hfjinja/internal/HostFunctionsTest.java \
  src/test/java/se/alipsa/hfjinja/internal/ValuesTest.java
git commit -m "Restructure Value for cross-package access and add TupleValue"
```

---

### Task 2: Resolve the shadowing question and build `Environment`

**Files:**
- Create: `src/main/java/se/alipsa/hfjinja/internal/runtime/Environment.java`
- Test: `src/test/java/se/alipsa/hfjinja/internal/runtime/EnvironmentTest.java`
- Modify: `upstream/mapping.yml`, `upstream/upstream-lock.json`, `tools/corpus/error-patterns-0.5.9.json`,
  `src/test/resources/corpus/v1.jsonl`

**Interfaces:**
- Produces: package-private `Environment` with `set(String, Value)` (declare-or-throw, matching
  upstream's `set` → `declareVariable`), `setVariable(String, Value)` (unconditional overwrite,
  matching upstream exactly), `lookupVariable(String)` (walks to root; returns
  `Value.UndefinedValue.INSTANCE` if never found, never throwing to the caller), and a `parent` link.
  Only `Interpreter` (Task 9) ever constructs one.

- [ ] **Step 1: Run the oracle to settle the shadowing question.** Render a template whose context
  contains a key equal to one of the **10** names `Environment` declares before any context is added
  — the 9 `setupGlobals` names plus `namespace` (seeded directly into every `Environment`'s
  `variables` map at construction, `runtime.ts:567-580`). Confirmed once already for `range`, `true`,
  `None`, `raise_exception`, `namespace`: all throw `SyntaxError: Variable already declared: <name>`.
  Confirm the remaining names before relying on it as a blanket rule.

- [ ] **Step 2: Add the corpus record.** Prefix self-authored records `self.` so they can never
  collide with `convert-upstream-tests.mjs`'s `--check` mode. Give `source` a value describing that
  it's self-authored — e.g. `"self-authored; verified against @huggingface/jinja 0.5.9 via
  upstream/vendor/dist/index.js, see docs/superpowers/plans/2026-08-21-wp4-interpreter-skeleton.md"`.
  Encode `expected.errorCategory` (Step 3). Run
  `node tools/corpus/run-node-oracle.mjs --corpus src/test/resources/corpus/v1.jsonl --patterns tools/corpus/error-patterns-0.5.9.json --lock upstream/upstream-lock.json`
  and confirm it passes before writing any Java.

- [ ] **Step 3: Add the mandatory error-classifier pattern** for `^Variable already declared:
  (?<name>.+)$` → `ErrorCategory.VALUE` — the closest existing fit for "declaration conflict."

- [ ] **Step 4: Delete `contextShadowsGlobals` from `upstream/upstream-lock.json`.** Unverified,
  uninspected metadata — the corpus record from Step 2 is the real source of truth now.

- [ ] **Step 5: Write the failing `EnvironmentTest`**

```java
package se.alipsa.hfjinja.internal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import se.alipsa.hfjinja.internal.Value.BooleanValue;
import se.alipsa.hfjinja.internal.Value.UndefinedValue;
import org.junit.jupiter.api.Test;

class EnvironmentTest {
  @Test
  void setRejectsRedeclarationInTheSameScope() {
    var env = new Environment(null);
    env.set("x", new BooleanValue(true));
    assertThrows(IllegalStateException.class, () -> env.set("x", new BooleanValue(false)));
  }

  @Test
  void setVariableOverwritesInTheCurrentScopeRegardlessOfDeclaration() {
    var env = new Environment(null);
    env.set("x", new BooleanValue(true));
    env.setVariable("x", new BooleanValue(false));
    assertEquals(new BooleanValue(false), env.lookupVariable("x"));
  }

  @Test
  void lookupVariableWalksToTheParentScope() {
    var parent = new Environment(null);
    parent.set("x", new BooleanValue(true));
    var child = new Environment(parent);
    assertEquals(new BooleanValue(true), child.lookupVariable("x"));
  }

  @Test
  void lookupVariableOfAnUnknownNameReturnsUndefinedRatherThanThrowing() {
    var env = new Environment(null);
    assertSame(UndefinedValue.INSTANCE, env.lookupVariable("nope"));
  }

  @Test
  void everyScopeDeclaresItsOwnNamespaceBuiltin() {
    var child = new Environment(new Environment(null));
    // Assert only that something is bound under "namespace" in a fresh scope — exact
    // CallExpression wiring for it lands with Task 7.
  }
}
```

- [ ] **Step 6: Run to verify it fails.**
  Run: `./gradlew test --offline --tests 'se.alipsa.hfjinja.internal.runtime.EnvironmentTest'`

- [ ] **Step 7: Implement `Environment`**, porting `upstream/vendor/src/runtime.ts:563-698` with
  upstream's exact method names and behavior. `set` throws `IllegalStateException` on redeclaration
  (an interpreter-internal invariant; Task 9 decides what the *public* `render` call site does with
  it). `lookupVariable` never throws. Seed `namespace` in the constructor.

- [ ] **Step 8: Run the tests.** Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/se/alipsa/hfjinja/internal/runtime/Environment.java \
  src/test/java/se/alipsa/hfjinja/internal/runtime/EnvironmentTest.java \
  upstream/mapping.yml upstream/upstream-lock.json \
  tools/corpus/error-patterns-0.5.9.json src/test/resources/corpus/v1.jsonl
git commit -m "Add the interpreter Environment and settle context/global shadowing"
```

---

### Task 3: `RenderBudget` and `RenderOptions` limits

**Files:**
- Create: `src/main/java/se/alipsa/hfjinja/internal/runtime/RenderBudget.java`
- Test: `src/test/java/se/alipsa/hfjinja/internal/runtime/RenderBudgetTest.java`
- Modify: `src/main/java/se/alipsa/hfjinja/RenderOptions.java`
- Modify: `src/test/java/se/alipsa/hfjinja/PublicApiTest.java`
- Modify: `upstream/mapping.yml`

**Interfaces:**
- Produces: `RenderOptions.maxSteps()`/`maxLoopIterations()`/`maxOutputLength()`, mirroring
  `TemplateOptions`'s three-limit shape exactly, threaded through the private constructor **and**
  the existing `DEFAULT` field. Package-private `RenderBudget` (one per render):
  `chargeStep(SourceLocation)`, `chargeLoopIteration(SourceLocation)`, `chargeOutput(int length,
  SourceLocation)`. **All three must actually be called** — `chargeOutput` is wired into `Interpreter`
  in Task 4/5 or it ships unenforced.

**`RenderOptions.DEFAULT` must be updated in the same change** — a missed default silently becomes
`0`, which every `charge*` call then immediately rejects.

**Picking generous defaults.** G4 requires the baseline corpus to "pass under effectively unbounded
budgets" — pick defaults high enough that no plausible chat-template render trips them (e.g.
`maxSteps = 10_000_000`, `maxLoopIterations = 1_000_000`, `maxOutputLength = 10_000_000` chars).
Real exhaustion testing under tight budgets is WP6-scoped.

**What a "step" actually measures in this slice — one AST statement, not one expression node.**
`chargeStep` is charged once per statement `evaluateBlock` evaluates (Task 5); nothing charges inside
`evaluateExpression` itself. `{{ f(g(h(deeply.nested[chain]))) }}` is therefore **one** step, however
many `CallExpression`/`MemberExpression` nodes it recursively evaluates — `maxSteps` bounds statements
executed, not expression-tree work done. That may be the right simplification for a skeleton slice, but
it's a real change in what the counter measures from when its `10_000_000` default was picked (an
earlier revision charged a step per expression too); stating it here keeps a later reader from assuming
expression cost is bounded by `maxSteps` when it isn't — a template that is one enormous top-level
expression still costs one step no matter how deep it recurses.

- [ ] **Step 1: Write the failing tests** — one `PublicApiTest` case for defaults/rejection, one
  `RenderBudgetTest` per counter.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the full suite.** Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "Add render-time budget counters and RenderOptions limits"`

---

### Task 4: `Value` → output text — JSON-style formatting, JS number formatting, the TupleValue quirk

**Files:**
- Create: `src/main/java/se/alipsa/hfjinja/internal/JsFormat.java` (`public`, flat `internal`
  package — not `internal.runtime`, see Architecture note above; hosts both the number-formatting
  core and the JSON string-quoting core, see below)
- Create/modify: `src/main/java/se/alipsa/hfjinja/internal/runtime/Interpreter.java` (adds the
  package-private `renderText(Value, SourceLocation)`/`renderJson(Value, SourceLocation)` methods every
  later task depends on)
- Test: `src/test/java/se/alipsa/hfjinja/internal/JsFormatTest.java`, `.../runtime/InterpreterTest.java`
- Modify: `upstream/mapping.yml`, `tools/corpus/error-patterns-0.5.9.json`,
  `src/test/resources/corpus/v1.jsonl`

**Why this can't wait for Slice 2.** `evaluateBlock` (Task 5) calls `.toString()`-equivalent on every
non-Null/Undefined statement result (`runtime.ts:1457-1459`); the moment `For`/`MemberExpression`
exist in this slice, non-string `Value`s (e.g. `loop.index`) are reachable and must render correctly.

**Two distinct formattings, kept strictly disjoint — one calls the other, never both directions.**
`renderText(Value, SourceLocation)` handles **top-level statement-result rendering only**;
`renderJson(Value, SourceLocation)` is **fully self-recursive** and never calls back into `renderText`.
(Named `renderText`, not `render` — Task 9's public entry point is `Interpreter.render(Program, ...)`;
reusing the name for this unrelated package-private value→text helper would leave two overloads
called `render` with nothing in common, which the plan's own "keep upstream's function names"
constraint doesn't actually require here since upstream itself calls this pair `toString`/`toJSON`,
not `render` — `renderText`/`renderJson` reads as the matched pair they are.) Conflating them is a
real bug, not a style choice: nested elements are JSON-quoted/JSON-formatted (`{{ ["a","b"] }}` →
`["a", "b"]`, quoted; `{{ [2.0] }}` → `[2]`, no trailing `.0` — both verified against the pinned
build), while a *top-level* `StringValue`/`FloatValue` renders unquoted/with a forced decimal
(`{{ "a" }}` → `a`; `{{ 2.0 }}` → `2.0`). If `renderText` ever routed an array's elements back through
itself instead of through `renderJson`'s own element-formatting rules, `{{ messages }}` (an array of
strings, the single most common real template case) would render unquoted garbage.

- **`renderText(Value, SourceLocation)`** (top-level only): `BooleanValue`/`IntegerValue`/`StringValue`
  use the base `RuntimeValue.toString()` = `String(this.value)` — raw, unquoted. `FloatValue`
  overrides: `value % 1 === 0 ? value.toFixed(1) : value.toString()` (`runtime.ts:98-100`) — see
  `renderFloat` below for the exact, verified integral-branch rule. `ArrayValue`/`ObjectValue`/
  `TupleValue` override `toString()` to call `toJSON(this, {}, 0, false)` (`runtime.ts:492-494`,
  `:526-529`) — i.e. `renderText`'s `ArrayValue`/`ObjectValue` arms simply delegate to `renderJson`,
  and `renderText` itself never recurses into element formatting. **`renderText`'s
  `NullValue`/`UndefinedValue` arms are genuinely unreachable**, not a "nested case" as a previous
  revision of this plan wrongly claimed: upstream's `evaluateBlock` skips Null/Undefined statement
  results before calling `.toString()`/`renderText`'s equivalent at all (`runtime.ts:1457-1459`), and a
  Null/Undefined *nested inside* a container is formatted by `renderJson`'s own logic, never by being
  routed through `renderText`. **In the Java port, the same skip must be re-homed to
  `evaluateStatement`'s delegating `case Expression e ->` arm (Task 5)** — the one place that still has
  a `Value` in hand before rendering it, since Java's own `evaluateBlock` operates on `ExecResult`, not
  `Value`, and never sees one; `renderText` staying unreachable for `NullValue`/`UndefinedValue` depends
  on that arm actually performing the skip, not on `renderText` skipping internally. Make both arms
  `throw new AssertionError("unreachable: " + value)` — defensive, not load-bearing, and honest about
  why.
- **`renderJson(Value, SourceLocation)`** (`toJSON`, `runtime.ts:318-388` — used both for a
  container's own top-level `toString()` and recursively for every element inside it, fully
  self-contained): `IntegerValue`/`FloatValue`/`BooleanValue` → `JsFormat.jsonString`/
  `String.valueOf` (NaN/±Infinity → the JSON string `"null"`, genuinely different from `renderText`'s
  plain top-level case); `StringValue` → `JsFormat.quote(String)` — **not** an inline reimplementation
  of `AstSnapshot.q()`'s escaping rules inside `renderJson` itself. `quote` is *ported* from
  `AstSnapshot.q()` (still not calling test code from main code) but lives in `JsFormat` alongside
  `jsonString`/`plainString`, for the same reason those two do: a package-private copy inside
  `internal.runtime` would be invisible to `AstSnapshot` (test code in `internal.parser`), permanently
  blocking Slice 2's promised unification for *this* method too, not just the numeric one — see
  "Reuse, don't reimplement" below, and Task 0's sixth-pass note on why the previous revision's
  placement (`JsNumberFormat`, string-quoting, when `JsNumberFormat` had none) didn't compile as
  specified. `NullValue` → `"null"`;
  **`UndefinedValue` → `"undefined"`** (`toJSON`'s `convertUndefinedToNull` parameter is passed
  `false` by `ArrayValue`/`ObjectValue.toString()`, so a nested undefined element renders as bare,
  unquoted `undefined` — this is genuinely reachable, e.g. `{{ [missing_var] }}`);
  `ArrayValue`/`ObjectValue` recurse into `renderJson` again, never into `renderText`; a `TupleValue`
  anywhere in the recursion — including at the very top — throws
  `TemplateRenderException("Cannot convert to JSON: TupleValue", ErrorCategory.TYPE, location)`
  (`runtime.ts:386-388`'s `default` throw, reached because the switch is on the string type tag and
  `"TupleValue"` matches no explicit case). **A bare `{{ (1, 2) }}` throws** — confirmed it parses
  fine as a top-level `TupleLiteral`, so this is purely a render-time failure, not a parse error —
  the same string-tag-vs-`instanceof` mechanism as Task 6's unpack-target quirk, a different call
  site.

**Threading `SourceLocation` — not optional.** Every other error this plan introduces
(`raise_exception`, every `charge*` call, the `IOException` wrapper) carries a real location; the
`TupleValue`-render failure must too, or it's the one position-less error in an otherwise
consistently-located API. `evaluateStatement`'s delegating `case Expression e ->` arm (Task 5) already
has the expression's own location in hand for `chargeOutput(length, location)` — thread the same one
into `renderText`/`renderJson`, at zero extra cost.

**Reuse, don't reimplement, the number-formatting *and* string-quoting cores — and put both where
Slice 2 can actually use them.** `AstSnapshot.number()` and `AstSnapshot.q()`
(`src/test/java/.../parser/AstSnapshot.java`) already correctly implement, respectively, the
JSON-style numeric formatter (ECMA `Number::toString` digit algorithm plus NaN/Infinity→`"null"`) and
JSON string-quoting (`\`, `"`, control chars, `\b\f\n\r\t`). Port **both** cores into `JsFormat` —
**`public final class`, flat in `se.alipsa.hfjinja.internal`**, not package-private inside
`internal.runtime` — same cross-package-access posture and javadoc treatment already granted to
`Values`/`HostFunctions` (`Token.java:9-11` precedent). This is deliberate, not incidental: a
package-private class inside `internal.runtime` would be invisible to `AstSnapshot` (test code in the
different package `internal.parser`), permanently blocking the unification "What's Next" promises for
Slice 2 for *either* core unless that slice first relocates or widens the class itself. Putting both
in `internal` now, public, means Slice 2's job shrinks to *deleting* `AstSnapshot.number()`'s and
`AstSnapshot.q()`'s bodies in favor of a call each — no relocation needed for either. Three thin
members built from the two shared cores:
- `JsFormat.jsonString(double)`: NaN/±Infinity → `"null"` — `AstSnapshot.number()`'s existing
  behavior, unchanged.
- `JsFormat.plainString(double)`: NaN → `"NaN"`, ±Infinity → `"Infinity"`/`"-Infinity"`, otherwise the
  shared numeric core (now with the subnormal fix below — `AstSnapshot.number()` itself still carries
  the old, unfixed behavior until Slice 2 unifies it).
- `JsFormat.quote(String)`: ported from `AstSnapshot.q()`'s escaping rules unchanged (see the
  lone-surrogate gap below); called by `Interpreter.renderJson`'s `StringValue` case rather than
  reimplemented inline there — the identical port-not-duplicate rationale as the numeric cores, and
  the reason `renderJson`'s own description above says `JsFormat.quote`, not "renderJson's own
  escaping logic."

This slice does **not** yet make `AstSnapshot.number()`/`AstSnapshot.q()` delegate to `JsFormat` —
Slice 1 ships with independent implementations of the same two algorithms for one slice, proven
equivalent (modulo the subnormal fix, see below) by `JsFormatTest` mirroring `AstSnapshot.number()`'s
and `AstSnapshot.q()`'s own test cases, not by sharing code yet. Slice 2 does the actual
deletion-in-favor-of-delegation for both, now unblocked by the package choice above.

**Subnormals — fixed, not waived; the general shortest-decimal search is six lines, not a Ryū/Schubfach
port.** `AstSnapshot.number()`'s own javadoc declares and waives a real divergence: `Double.toString`
is not guaranteed shortest for subnormal doubles (`Double.MIN_VALUE` prints as `"4.9E-324"` in Java vs
`"5e-324"` in JS — verified against the pinned Node build), on the premise that "the lexer cannot emit
a subnormal." That premise does not hold for rendering:
`ValuesTest.acceptsSubnormalNumbersUsingTheJsShortestForm` (`ValuesTest.java:48-51`) exists precisely
to prove `Values.fromHost(Double.MIN_VALUE)` produces a real `FloatValue`, so `{{ x }}` with
`x = Double.MIN_VALUE` in the host context is reachable and takes the non-integral branch straight
into `JsFormat.plainString`. A cost estimate of "needs a real shortest-round-trip algorithm" would
justify waiving this for an interpreter-skeleton slice — but that estimate is wrong: a general (not
subnormal-special-cased) fix is a small addition to the ported digit-formatting core, verified against
the pinned build across every boundary that matters:

```java
private static String shortestDigits(double v) {
  // callers must have already handled non-finite and zero — new BigDecimal(NaN/Infinity) throws
  if (Math.abs(v) >= Double.MIN_NORMAL) return Double.toString(v);
  for (int p = 1; p <= 17; p++) {
    String c = new BigDecimal(v).round(new MathContext(p)).toString();
    if (Double.parseDouble(c) == v) return c;
  }
  return Double.toString(v);
}
```

Swap the one line in the ported core that currently reads `new BigDecimal(Double.toString(value))`
for `new BigDecimal(shortestDigits(value))` — every other line of the digit-formatting algorithm is
unchanged. Verified: `Double.MIN_VALUE` (`4.9E-324`) → `5E-324` (matches JS `5e-324`); `9.9E-324` →
`1E-323` (matches `1e-323`); `2.0E-323` → `2E-323` (matches, and was never actually divergent — see
below); `9.9E-323` → `1E-322` (matches).

**The `Double.MIN_NORMAL` guard is not just a performance optimization — it is load-bearing for
correctness, and its absence would have been a real, if rare, regression.** The digit-formatting
core's own javadoc previously asserted, without having actually checked it, that "`Double.toString`'s
own shortest digits already round-trip for all non-subnormal doubles, so `p` lands on the same answer
immediately" — i.e., that the unguarded loop is a safe no-op for every normal double. Checking it
directly (300,000 random normal doubles) finds that claim is false in general: `MathContext`'s
`HALF_UP` rounding and `Double.toString`'s own shortest-round-trip algorithm can pick *different*,
both individually-valid, round-tripping decimal strings at an exact rounding-tie boundary — e.g.
`5.804742410468122E14`'s exact binary value is `580474241046812.25`, precisely equidistant between the
16-significant-digit candidates `...812.2` (what `Double.toString` picks) and `...812.3` (what
`HALF_UP` rounding of the exact value picks, and what the unguarded loop would therefore have
returned). That is a real, silently-wrong output the unguarded version of this fix would have
introduced for ordinary numbers — not just subnormals, and not hypothetical: about 1 in 4,400 random
normal doubles hit it in this sample. The guard eliminates this entirely for normal-range values by
never running the tie-prone search there: any `v` with `Math.abs(v) >= Double.MIN_NORMAL` returns
`Double.toString(v)` directly, byte-for-byte identical to the current, un-fixed behavior, with zero
risk of picking a different tie candidate. It also removes real, avoidable cost: without the guard,
every rendered normal-range number — including `loop.index` on every loop iteration — pays up to 17
`new BigDecimal`/`MathContext`/`Double.parseDouble` round-trips to confirm what `Double.toString`
already knew. **The residual caveat closes by proof, not by scoping it down and hedging:** the tie
case that makes `HALF_UP` vs. ECMA's own tie-breaking rule matter cannot arise for subnormals at all,
by construction. A subnormal is `v = k · 2⁻¹⁰⁷⁴` with `1 ≤ k < 2⁵²`. Writing `k = 2ᵗ·k'` with `k'` odd
and `t ≤ 51` gives `v = k'·5^(1074−t) / 10^(1074−t)`, whose numerator `k'·5^(1074−t)` is odd — never
divisible by 10 — so the exact decimal expansion terminates at exactly `1074 − t ≥ 1023` fractional
digits with a nonzero final digit. Since `|v| < 2⁻¹⁰²² ≈ 2.2e−308`, the leading significant digit sits
near the 308th fractional place, so the exact value carries roughly 700+ significant digits in total
(independently confirmed: sampling the exact `BigDecimal` expansion of subnormal bit patterns
1..4,500,000 found 736–758 significant digits in every case, never fewer). A `HALF_UP`/ECMA tie at
precision `p` requires the exact value to have precisely `p + 1` significant digits; with `p ≤ 17`
in `shortestDigits`'s search, `p + 1 ≤ 18`, three orders of magnitude short of ~700+. The tie case is
therefore structurally unreachable in the subnormal range, independent of sample size. (Corroborated,
not just proven: an independent 5,000-sample comparison of `HALF_UP` against `HALF_DOWN` at each
winning precision found zero divergences, consistent with the impossibility argument and with a
separate 600,000-sample check reported during review.) That is precisely the property the normal-range
counterexample above lacks — `580474241046812.25` terminates after exactly 17 significant digits, so a
tie at `p = 16` is reachable there — which is why the guard is load-bearing above `Double.MIN_NORMAL`
and provably unnecessary below it. `JsFormat`'s javadoc states this as a closed case, not an open
caveat; `JsFormatTest` pins the now-correct subnormal values above as literal assertions.

**Lone surrogates — a real, still-open gap, now correctly located and testable.** `AstSnapshot.q()`
escapes `\`, `"`, `\b\f\n\r\t`, and `c < 0x20`, passing everything else through raw. `JSON.stringify`
additionally escapes unpaired UTF-16 surrogates — confirmed against the pinned Node build:
`JSON.stringify("a\ud800b")` emits the six literal characters `\ud800` (backslash-u-d-8-0-0), not the
raw code unit. A host-supplied string containing a lone surrogate is reachable the same way as the
(now-fixed) subnormal case (`Values.fromHost` on an arbitrary `String`) and renders the raw, unescaped
surrogate instead. **Decision: out of scope for this slice, stated explicitly rather than left
implicit — and, unlike the previous revision's placement, actually testable where it's stated.**
`JsFormat.quote`'s javadoc says plainly that it ports `AstSnapshot.q()`'s rules as-is and does not
escape unpaired surrogates; `JsFormatTest` pins the current (documented-wrong) output for a lone
surrogate directly against `JsFormat.quote`, which — unlike the previous revision's assignment to a
method that didn't exist on this class — actually compiles and runs in-package; this is also added to
"Known Gaps" below rather than being an unremarked gap a reader has to discover.

**`renderFloat`'s integral branch — the exact rule, verified, not a heuristic.** A string-inspection
heuristic ("format via `plainString`, append `.0` unless the result already contains `.`/`e`") is
**wrong**: `Number::toString` emits the shortest round-tripping decimal form, while `toFixed(1)`
expands the *exact* binary value — these diverge for any double in `2^53 ≤ |v| < 1e21` whose shortest
form isn't exact (verified: `123456789012345680000` → `plainString` gives
`"123456789012345680000"`, append `.0` gives `...680000.0`, but the true `toFixed(1)` is
`...683968.0` — a real mismatch, reachable from a `FloatLiteral` or a host function returning
`HostFunction.FloatResult`). The correct rule, and it needs no heuristic:

```java
private static String renderFloat(double value) {
  // value % 1 == 0 is already established by the caller — this is the exact-integer branch.
  if (Math.abs(value) < 1e21) {
    return new BigDecimal(value).setScale(1, RoundingMode.UNNECESSARY).toPlainString();
  }
  return JsFormat.plainString(value); // matches toFixed's own fallback to toString past 1e21
}
```

`new BigDecimal(value)` (the `double` constructor, not `BigDecimal.valueOf`) captures the exact
binary value; `setScale(1, UNNECESSARY)` needs no rounding mode decision because the branch is only
entered when `value % 1 == 0` (an exact integer already). Verified against the pinned build at every
boundary: `1e20` → `...0.0` (append works); `1e21` → falls to the `plainString` branch, matching
`toFixed`'s own documented fallback exactly; `Number.MAX_VALUE` → same fallback; the
`123456789012345680000` mismatch case above now formats correctly via the exact `BigDecimal` path.

- [ ] **Step 1: Write the failing tests** — `JsFormatTest` covering the digit-formatting boundary
  cases `AstSnapshot.number()` already covers for both `jsonString`/`plainString`, their NaN/Infinity
  divergence, and the fixed subnormal cases as literal (now-correct) assertions:
  `plainString(Double.MIN_VALUE)` → `"5e-324"`, `plainString(1e-323)` → `"1e-323"`,
  `plainString(2.0E-323)` → `"2e-323"`, `plainString(1e-322)` → `"1e-322"` — written in the JS output's
  own form (`1e-323`/`1e-322`, not Java's `9.9E-324`/`9.9E-323` print form for the identical double,
  confirmed the same value both ways) so each assertion reads as self-evidently correct rather than
  looking like a typo in the one test file whose whole job is pinning a subtle formatting difference;
  `quote(String)` cases
  mirroring `AstSnapshot.q()`'s own test cases, plus the one pinned known-divergence case: `quote` of
  a string containing a lone surrogate emitting the raw code unit unescaped (documented-wrong vs.
  `JSON.stringify`'s `\ud800`-style escape); `InterpreterTest` cases for `renderText`: `"1"`, `"2.0"`,
  `"hello"` (unquoted), `"true"`; for `renderJson`/`renderText`'s array-and-object delegation:
  `["a","b"]` renders `["a", "b"]` (quoted), `[2.0]` renders `[2]` (not `[2.0]` — the disjointness
  assertion from finding 1), an object renders `{"a": 1, "b": "x"}`, a tuple throws
  `TemplateRenderException`/`TYPE` with the passed `SourceLocation`, a nested array containing
  `Value.UndefinedValue.INSTANCE` renders `[undefined, 5]` (bare, unquoted); `renderFloat`'s three
  verified boundary cases (`1e20`, `1e21`, the `123456789012345680000` mismatch case) as literal
  assertions, not just documentation prose.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** `JsFormat`, `Interpreter.renderText`/`renderFloat`/`renderJson`.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Add the TupleValue-render corpus record and its classifier pattern**
  (`^Cannot convert to JSON: (?<type>.+)$` → `ErrorCategory.TYPE`), `self.`-prefixed, verified
  against the oracle for `{{ (1, 2) }}`.
- [ ] **Step 6: Commit** — `git commit -m "Add Value-to-text rendering, JS number formatting, and the TupleValue render quirk"`

---

### Task 5: `ExecResult` and `evaluateBlock`/`evaluateIf` — with upstream's actual discard semantics

**Files:**
- Modify: `src/main/java/se/alipsa/hfjinja/internal/runtime/Interpreter.java`
- Create: `src/main/java/se/alipsa/hfjinja/internal/runtime/ExecResult.java`
- Test: `src/test/java/se/alipsa/hfjinja/internal/runtime/InterpreterTest.java`
- Modify: `upstream/mapping.yml`

**What upstream actually does, confirmed against the oracle.** `evaluate()`'s switch throws
`BreakControl`/`ContinueControl` directly for `Break`/`Continue` statements (`runtime.ts:1819-1821`);
nothing between there and `evaluateFor`'s try/catch catches it, so the exception unwinds through every
intervening block-evaluation frame, discarding each one's own locally-accumulated `result`. Confirmed:

```
{% for i in [1,2,3] %}A{% break %}B{% else %}DEFAULT{% endfor %}     => "DEFAULT"
{% for i in [1,2,3] %}A{% continue %}B{% else %}DEFAULT{% endfor %}  => "DEFAULT"
{% for i in [1,2,3] %}X{% if i == 2 %}{% break %}{% endif %}{% else %}DEFAULT{% endfor %} => "X"
```

**Break/continue with no enclosing `for` loop — a stated judgment call, not oracle-settled.** A bare
`{% break %}` throws the raw `BreakControl` class with an empty message. The corpus mechanism cannot
represent this at all (`errorClassifier` throws on any unmatched string, including an empty one).
**Decision, made here without oracle backing:** `TemplateRenderException(..., ErrorCategory.SYNTAX,
location)` — a structural authoring mistake independent of runtime data. Plain JUnit test, no corpus
record possible; the test comment should say this has no oracle evidence behind it.

**Interfaces:**

```java
sealed interface ExecResult permits ExecResult.Normal, ExecResult.Break, ExecResult.Continue {
  record Normal(String output) implements ExecResult {}
  enum Break implements ExecResult { INSTANCE }
  enum Continue implements ExecResult { INSTANCE }
}
```

`Break`/`Continue` are enum singletons (matching `UndefinedValue`/`NullValue`'s idiom in `Value.java`)
rather than allocated per occurrence on the loop hot path.

`evaluateBlock` accumulates into a local buffer statement-by-statement; the moment any statement's own
evaluation yields `ExecResult.Break`/`Continue.INSTANCE`, immediately return that same singleton and
**discard the local buffer accumulated so far** — do not merge it into the propagated result.
`evaluateIf` returns whatever `ExecResult` its chosen branch produced, unchanged.

**`chargeOutput` and `chargeStep` charge at different places, on purpose — they don't move together.**
`chargeStep` stays in `evaluateBlock`, once per statement evaluated: one AST statement executed is one
step, and a nested statement genuinely is an additional step, so charging it at every nesting level is
correct, not a double-count. `chargeOutput` is different: output *aggregates upward* through nested
`ExecResult.Normal` values (a `For` statement's own output already contains everything its body's
statements produced), so charging it anywhere but the one place text is first created would count the
same characters once per level of nesting they pass through. That one place is `evaluateStatement`'s
delegating `case Expression e ->` arm (see below) — `Parser.java:61,70-72` makes raw template text an
`Expression.StringLiteral` directly in a body list, so *every* output-producing leaf, at any nesting
depth, is an `Expression` evaluated exactly once by that one arm; `evaluateBlock`, `evaluateIf`, and
`evaluateFor` only ever aggregate already-charged text upward, never produce new characters of their
own, so they charge `chargeStep` but never `chargeOutput`. **`chargeOutput` bounds text produced, not
text that reaches the caller — "charged" and "emitted" are not the same thing, and this slice creates
two places where they diverge.** A `{% continue %}` after `{{ big }}` in the same loop body charges
`big`'s length (the delegating arm ran before the `Continue` singleton caused `evaluateBlock` to discard
the buffer) even though that text never appears in the final render — `{% for i in range(1000) %}
{{ big }}{% continue %}{% endfor %}` charges `1000 × len(big)` against `maxOutputLength` while emitting
nothing. `{% set x %}...{% endset %}` charges the captured text once at capture time (the block-capture
form's own internal `evaluateBlock` runs the same delegating arm on everything inside it) and again on
every later `{{ x }}` that renders it — the same characters charged twice, or more. Both are defensible
as *work performed* rather than *output produced*, matching this slice's blanket "detect, don't prevent"
posture elsewhere, but they mean `chargeOutput` is not a precise measure of final output size — a
render that trips `maxOutputLength` may have discarded most of what it charged. (An earlier revision of
this task charged
`chargeOutput` in `evaluateBlock` for each statement's contribution — wrong even before the
`evaluateStatement`/`evaluateExpression` split, since `{% for i in [1,2,3] %}XXXX{% endfor %}` would
charge the body's 12 characters once in the loop's own inner block evaluation and again when the `For`
statement's aggregated 12-character result reaches the outer block — 24 charged for 12 rendered
characters, compounding with nesting depth. Wrong twice over after the split, since `evaluateBlock` no
longer even sees a `Value` to call `renderText` on.) **Detects, does not prevent, an oversized single
value.** Because the charge happens *after* `renderText`/`renderJson` has already built the full
string, a single `{{ huge_object }}` still allocates that entire string in memory before
`maxOutputLength` can reject it — at the slice's generous 10,000,000-char default, up to ~20MB
transient for one statement. Stated here as an accepted limitation of this slice, the same way the
`Appendable` no-streaming-inside-loops case (Task 9) and `toFixed`'s `1e21` boundary (Task 4) are
stated rather than hidden; incremental charging inside `renderJson` itself (rather than after it
returns) would close this but is not required for Slice 1's own goals.

**This task writes both dispatch methods for the first time — each must be exhaustive over its own
direct permitted subtypes immediately, per the Global Constraints rule of the same name, not just the
5 leaves this task implements for real.**

```java
ExecResult evaluateStatement(Statement node, Environment env, RenderBudget budget) {
  return switch (node) {
    case Statement.Program p -> ...
    case Statement.If i -> ...
    case Statement.Break b -> ...
    case Statement.Continue c -> ...
    case Statement.Comment c -> ...           // real arm, this task
    case Statement.For f -> ...                // placeholder, this task; real logic Task 6
    case Statement.SetStatement s -> ...        // placeholder, this task; real logic Task 8
    case Statement.Macro m -> ...               // placeholder, this task; real logic WP5
    case Statement.FilterStatement f -> ...     // placeholder, this task; real logic WP5
    case Statement.CallStatement c -> ...        // placeholder, this task; real logic WP5
    case Expression e -> {                       // delegating arm — discharges Expression's whole
      var v = evaluateExpression(e, env, budget); // branch of evaluateStatement's own switch
      var text = v instanceof Value.NullValue || v instanceof Value.UndefinedValue
          ? ""                                    // upstream's evaluateBlock skip, re-homed here
          : renderText(v, e.location());
      budget.chargeOutput(text.length(), e.location());
      yield ExecResult.Normal(text);
    }
  };
}

Value evaluateExpression(Expression node, Environment env, RenderBudget budget) {
  return switch (node) {
    // 9 real arms, Task 7: MemberExpression, CallExpression, Identifier, IntegerLiteral,
    // FloatLiteral, StringLiteral, ArrayLiteral, TupleLiteral, ObjectLiteral
    // 9 placeholders, this task: BinaryExpression, UnaryExpression (Slice 2); FilterExpression,
    // SelectExpression, TestExpression, Ternary (Slice 3); SliceExpression,
    // KeywordArgumentExpression, SpreadExpression (WP5)
  };
}
```

**Exhaustiveness is computed over *direct* permitted subtypes, not the full flattened 28 — verified
against `javac`, not assumed.** `evaluateStatement`'s switch needs 11 conceptual arms: `Statement`'s
10 direct records plus one `case Expression e -> ...` that fully discharges the `Expression` branch
without enumerating any of its 18 leaves (confirmed empirically: a switch over an outer sealed type
with one delegating arm for a nested sealed subtype, and no enumeration of that subtype's own leaves,
compiles clean under `javac` with no warning). Fully flattening — writing all 28 leaves into one
switch, the way `AstSnapshot.emit` already does — is legal, but this task does **not** do that: the
return-type split below makes flattening impossible anyway, since `evaluateStatement` returns
`ExecResult` and `evaluateExpression` returns `Value`, and one switch cannot produce both.

**Why two methods, not one — the return types are incompatible, not merely differently named.**
`Statement` dispatch must return `ExecResult`: `Break`/`Continue` are control flow that has to
propagate through `evaluateStatement` (see the `chargeOutput`/discard notes above), not a value.
`Expression` dispatch must return a plain `Value`: the result feeds `MemberExpression`'s object,
`CallExpression`'s arguments, `For`'s iterable, and `SetStatement`'s rhs (Task 8) — none of which can
consume an `ExecResult`. `ExecResult.Normal(String)` carries already-rendered text, not a `Value`, so
it isn't a usable common return type either — there is no type both call sites can share, so this is
two methods by necessity, not by naming preference. `evaluateStatement`'s delegating `case Expression
e ->` arm is exactly an expression appearing in statement position (e.g. `{{ expr }}` or a raw
text/`StringLiteral` node directly in a body list) — it calls `evaluateExpression`, and **must
re-implement upstream's `NullValue`/`UndefinedValue` skip here, not skip it**: upstream's `evaluateBlock`
filters those two types out *before* calling `.toString()` (`runtime.ts:1457-1459`), which is the only
reason Task 4's `renderText` is allowed to treat its own `NullValue`/`UndefinedValue` arms as
unreachable and throw `AssertionError` there. After the split, `evaluateBlock` never sees a `Value` —
this delegating arm is the only place left that still has one — so it is now the one place responsible
for reproducing that skip: `NullValue`/`UndefinedValue` become `""` directly, anything else goes
through `Interpreter.renderText` (Task 4) using the expression's own location. Skipping this step would
make `{{ none }}` and `{{ missing_var }}` — the single most common case in real templates, an
undefined context variable — crash with an uncategorized `AssertionError` instead of rendering `""`
(verified against the pinned oracle: both render empty). This is also the **one and only**
`chargeOutput` call site (see the `chargeOutput`/`chargeStep` note above); `chargeStep` charges
separately, in `evaluateBlock`, once per statement regardless of type. The whole arm mirrors upstream's
own single `evaluate()` handling an expression statement: convert to text (skipping
`Null`/`Undefined`), accumulate.

**Comment is a real arm, not a placeholder.** Upstream's `case "Comment": return new NullValue()`
(`runtime.ts:1866-1867`), which `evaluateBlock` then skips because it filters out
`NullValue`/`UndefinedValue` results before appending (`runtime.ts:1449-1461`), means `{# ... #}`
renders nothing; port this as `ExecResult.Normal("")` directly in `evaluateStatement` — there is no
reason to defer something this small to its own task.

**Placeholder accounting, split by method:**

| Method | Placeholder leaves | Category | Replaced by |
| --- | --- | --- | --- |
| `evaluateStatement` | `For` | — | Task 6 |
| `evaluateStatement` | `SetStatement` | — | Task 8 |
| `evaluateStatement` | `Macro`, `FilterStatement`, `CallStatement` | `ErrorCategory.UNDEFINED_OR_ACCESS` | WP5 |
| `evaluateExpression` | `MemberExpression`, `CallExpression`, `Identifier`, `IntegerLiteral`, `FloatLiteral`, `StringLiteral`, `ArrayLiteral`, `TupleLiteral`, `ObjectLiteral` | — | Task 7 |
| `evaluateExpression` | `BinaryExpression`, `UnaryExpression` | `ErrorCategory.UNDEFINED_OR_ACCESS` | Slice 2 |
| `evaluateExpression` | `FilterExpression`, `SelectExpression`, `TestExpression`, `Ternary` | `ErrorCategory.UNDEFINED_OR_ACCESS` | Slice 3 |
| `evaluateExpression` | `SliceExpression`, `KeywordArgumentExpression`, `SpreadExpression` | `ErrorCategory.UNDEFINED_OR_ACCESS` | WP5 |

At this task's Step 3: `evaluateStatement` gets 5 placeholders (`For`, `SetStatement`, `Macro`,
`FilterStatement`, `CallStatement`); `evaluateExpression` gets all 18 (Task 7 hasn't run yet) — 23
total, matching the original count. By the end of this slice: Task 6 replaces 1 (`For`), Task 7
replaces 9 (the `evaluateExpression` literal/access/call group), Task 8 replaces 1 (`SetStatement`) —
leaving 3 in `evaluateStatement` (`Macro`/`FilterStatement`/`CallStatement`, all WP5) and 9 in
`evaluateExpression` (2 Slice 2 + 4 Slice 3 + 3 WP5), 12 total.

**Category for the placeholder arms — a real decision, not oracle-settled, because upstream has no
equivalent situation.** `ErrorCategory` has exactly 10 constants (`SYNTAX`, `UNDEFINED_OR_ACCESS`,
`TYPE`, `ARITY`, `VALUE`, `EXPLICIT_RAISE`, `HOST_FUNCTION`, `HOST_CONVERSION`, `RESOURCE_LIMIT`,
`OUTPUT` — confirmed by reading `ErrorCategory.java` directly, not assumed); none names "valid syntax
this slice doesn't implement yet," because upstream — the oracle this whole plan otherwise defers to —
implements the full language and never has a partially-built slice to begin with. This is a genuine
Java-port-sequencing artifact with no upstream precedent, not a case of checking the wrong source.
**Decision:** `ErrorCategory.UNDEFINED_OR_ACCESS`. From a template author's observable perspective,
`{{ 1 + 2 }}` failing because this build doesn't implement `BinaryExpression` yet looks identical to
any other "this renderer does not support what you asked for" failure — the same bucket
`UNDEFINED_OR_ACCESS`'s own doc already covers ("an access target does not support the attempted
operation"), generalized from a value's supported operations to this build's supported AST node types,
rather than inventing an eleventh public constant for a temporary, slice-sequencing concern. Each
placeholder's message should name the construct and the slice/WP that implements it (e.g. `"Arithmetic
is not yet supported (Slice 2): BinaryExpression"`), not just "unsupported," so a template author
hitting this mid-development gets an actionable message, not a mystery.

- [ ] **Step 1: Write the failing tests** covering: `Program`/literal text renders to the identity
  string; `If` picks `body`/`alternate` correctly (the parser already flattens `elif` into nested
  `If`); a `{% break %}`/`{% continue %}` statement alone returns the matching singleton with no
  output; `text-before{% break %}text-after` returns `ExecResult.Break.INSTANCE` with no output; a
  nested `If` inside a block whose branch resolves to `Break` propagates `Break` out of the *outer*
  block too, discarding what the outer block had already accumulated; a bare `{% break %}` with no
  enclosing `for` throws `SYNTAX`; `chargeOutput` is actually invoked, exactly once per delegating-arm
  evaluation (a tiny `RenderBudget` with `maxOutputLength = 1` rejecting a two-character render), and
  is **not** double-charged when that output is nested inside another block (a `RenderBudget` with
  `maxOutputLength` set to exactly the length of `{% for i in [1,2] %}XX{% endfor %}`'s real 4-character
  output must **not** reject it — pins the fix against the old evaluateBlock-recharges-nested-output
  bug); `{{ none }}` and `{{ missing_var }}` (an undefined context variable) both render `""`, not an
  `AssertionError` (verified against the pinned oracle); `{# a comment #}` alone renders `""`, and
  `before{# c #}after` renders `"beforeafter"`; calling `evaluateExpression` on one placeholder leaf
  (e.g. a bare `BinaryExpression`, constructed directly since the parser can produce one long before
  Task 5 can evaluate it) throws a categorized `TemplateRenderException`, not an uncategorized
  exception; calling `evaluateStatement` on a bare `Expression` (e.g. a `StringLiteral` used directly
  as a body entry) renders it as text via the delegating arm, without needing its own `case`.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** `ExecResult`, `evaluateStatement`, `evaluateExpression`, `evaluateBlock`,
  `evaluateIf` (all package-private), the real `Comment` arm, and all 23 placeholder arms per the
  table above. Charge `chargeStep` in `evaluateBlock`, once per statement. Charge `chargeOutput` **only**
  in `evaluateStatement`'s delegating `case Expression e ->` arm, after the `Null`/`Undefined` skip —
  not in `evaluateBlock`, which would double-count nested output. Implement the truthiness subset
  from "Known Gaps."
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "Add ExecResult-based block evaluation matching upstream's break/continue discard"`

---

### Task 6: `For` loops

**Files:**
- Modify: `src/main/java/se/alipsa/hfjinja/internal/runtime/Interpreter.java`
- Test: `src/test/java/se/alipsa/hfjinja/internal/runtime/InterpreterTest.java`
- Modify: `upstream/mapping.yml`, `tools/corpus/error-patterns-0.5.9.json`,
  `src/test/resources/corpus/v1.jsonl`

Port `upstream/vendor/src/runtime.ts:1603-1719` (`evaluateFor`).

**Fidelity notes — port these exactly, including the three quirks:**

- **Loop-body scope is shared across iterations**, not fresh per iteration.
- **`SelectExpression` filtering happens as a pre-pass over ALL candidates before any iteration's body
  runs**; `loop.length`/`loop.revindex*` are computed against the **post-filter** count.
- **Quirk 1 — a `{% continue %}`'d iteration does not count as "an iteration occurred."** On
  `ExecResult.Continue.INSTANCE`, skip appending, move to the next `i`, and **do not** set
  `noIteration = false`.
- **Quirk 2 — the same applies to `{% break %}` on the very first iteration.** On
  `ExecResult.Break.INSTANCE`, stop the loop and **do not** set `noIteration = false` either.
- **Quirk 3 — a tuple loop-iterable is iterable, but a tuple is never a valid unpack *target*.** The
  candidate set accepts both `Value.ArrayValue` and `Value.TupleValue` (matching upstream's
  `instanceof ArrayValue` passing for a JS `TupleValue` subclass instance, `runtime.ts:1616`). But
  when `node.loopvar` is a `TupleLiteral`, the **current item being unpacked** must be checked
  specifically against `Value.ArrayValue` — a `Value.TupleValue` item is rejected with the same
  `Cannot unpack non-iterable type: TupleValue`-equivalent error upstream produces (`runtime.ts:1636`,
  a string-tag check that does not match a `TupleValue` instance). Confirmed:
  `{% for a,b in [(1,2),(3,4)] %}{{a}}{{b}}{% endfor %}` throws; `{% for x in (1,2) %}{{x}}{% endfor %}`
  renders `12`.
- Tuple-unpacking loop vars (`for k, v in ...`) with an explicit arity-mismatch error.
- Object iterables iterate their **keys**, each exposed as a `StringValue`.

**Loop-iteration budget — the pre-filter pass itself must be charged, not just survivors.** Upstream
evaluates the `SelectExpression` test once per **raw** candidate, in a throwaway `new
Environment(scope)` per candidate, before any survival decision (`runtime.ts:1624-1669`, the per-
candidate scope allocation at `:1627`) — filtering happens entirely before `items`/
`scopeUpdateFunctions` are populated. None of the three counters otherwise charges this pass:
`chargeStep` only fires in `evaluateBlock`, once per statement, and the filter test is not a statement
— the whole `For` counts as one step regardless of candidate count; `chargeOutput` only fires in
`evaluateStatement`'s delegating arm, and the filter test produces no output. That leaves
`{% for x in range(10000000) if none %}{% endfor %}` — reachable this slice, since `range()` is Task 7
and an `Identifier`/literal filter test is the simplest case "Known Gaps" already scopes as
supported — performing 10,000,000 expression evaluations and 10,000,000 `Environment` allocations
while charging exactly 1 step and 0 loop iterations. **Fix, matching the counter's own name:** charge
`budget.chargeLoopIteration(location)` once per **candidate considered in the pre-filter pass**, not
once per survivor. `loop.length`/`loop.revindex*` are unaffected — those still compute against the
**post-filter** count (upstream's own `items.length`), a separate concern from what gets charged
against the render budget.

- [ ] **Step 1: Write the failing tests** — happy-path iteration with `loop.index`/`loop.first`/
  `loop.last` (rendered via Task 4's `renderText(Value, location)`); tuple unpacking of an array item; `for...else`
  on an empty iterable; the two `noIteration` quirk tests (assert the exact oracle-confirmed outputs);
  object iteration over keys; arity-mismatch error; iterating a non-array/non-object value ("Expected
  iterable or object type in for loop", `runtime.ts:1617`); an invalid loop variable form ("Invalid
  loop variable(s)", `runtime.ts:1655`); Quirk 3's tuple-item-unpack rejection; a `RenderBudget` with
  `maxLoopIterations` set below the raw candidate count rejects `{% for x in [1,2,3] if false %}
  {% endfor %}` even though every candidate is filtered out and zero iterations "occur" — pinning that
  the pre-filter pass itself is charged, not just survivors.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** `evaluateFor`.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Add corpus records and the tuple-unpack classifier pattern.** `self.`-prefixed records
  for the two `noIteration` quirks (text-bearing, no classifier needed) and Quirk 3's rejection
  (error-bearing). By the time Quirk 3's record exists, the pattern table has exactly the one entry
  from Task 2 — **add `^Cannot unpack non-iterable type: (?<type>.+)$` → `ErrorCategory.TYPE` in this
  same step**, or `nodeCorpusVerify` fails on the unmatched message.
- [ ] **Step 6: Commit** — `git commit -m "Port For loops, including the noIteration and tuple-unpack quirks"`

---

### Task 7: Literals, `Identifier`, `MemberExpression`, restricted `CallExpression`

**Files:**
- Modify: `src/main/java/se/alipsa/hfjinja/internal/runtime/Interpreter.java`
- Test: `src/test/java/se/alipsa/hfjinja/internal/runtime/InterpreterTest.java`
- Modify: `upstream/mapping.yml`

**Notes:**

- Literal → `Value`: `IntegerLiteral`/`FloatLiteral` → `IntegerValue`/`FloatValue` preserving the
  `double`; `StringLiteral` → `StringValue`; `ArrayLiteral` → `ArrayValue`; **`TupleLiteral` →
  `Value.TupleValue`** (`runtime.ts:1830-1833` — do not collapse both into `ArrayValue`). `ObjectLiteral`
  → `ObjectValue`: last key wins on duplicates.
- `Identifier` → `environment.lookupVariable(node.value())`.
- `MemberExpression` (minus `SliceExpression`, WP5): array/string/**tuple** access requires an
  `IntegerValue` index including negative indices — treat `Value.TupleValue` identically to
  `Value.ArrayValue` for indexing, since real subclassing makes no distinction here, only for the
  unpack-target check in Task 6. Absent-key/out-of-range access is not uniform: object absent-key →
  `UndefinedValue.INSTANCE`; array/tuple out-of-range → genuine `UndefinedValue.INSTANCE`; **string**
  out-of-range → `StringValue("undefined")` (upstream wraps the JS `undefined` in `new
  StringValue(...)` before the generic check runs) — `"abc"[10]` renders the literal text `undefined`,
  `[1,2][10]` renders empty text.
- `CallExpression`: the callee must be an `Identifier` (else `ErrorCategory.TYPE`).
  `RenderOptions.Builder.build()` already rejects a host function name colliding with
  `BUILTIN_GLOBALS`. `range`/`raise_exception`/`strftime_now` get inline Java implementations; the
  other 6 of the 9 globals are plain values, never call targets; a host function name dispatches
  through `internal.HostFunctions.invoke(...)`; anything else is `ErrorCategory.UNDEFINED_OR_ACCESS`.
  - `range(start, stop?, step = 1)`: confirmed exact signature at `upstream/vendor/src/utils.ts:8-13`
    — when `stop` is omitted, `stop = start; start = 0` (`range(5)` means 0..5). Throws
    `Error("range() step must not be zero")` for `step === 0`. Result is a real `Value.ArrayValue` of
    `IntegerValue`s (matching upstream's `convertToRuntimeValues` wrapping, `runtime.ts:1877-1901`),
    renderable via Task 4's `renderText(Value, location)` unchanged — `{{ range(3) }}` → `[0, 1, 2]`. Port as a
    small package-private helper inside `internal.runtime`.
  - `raise_exception(message)`: throws `TemplateRenderException(message, ErrorCategory.EXPLICIT_RAISE, location)`.
  - `strftime_now(format)`: confirm `RenderOptions`' existing "fail on first use when clock/zone is
    absent" behavior rather than inventing a new failure mode.

- [ ] **Step 1: Write the failing tests** for each bullet above, including: negative array indexing,
  absent-key member access on an object, the string-vs-array out-of-range distinction, tuple member/
  index access behaving like array access, `range()`'s three arities and its zero-offset-when-stop-
  omitted behavior (and its rendered output via Task 4), `raise_exception` producing
  `EXPLICIT_RAISE`, calling an unresolvable name producing `UNDEFINED_OR_ACCESS`, calling a
  non-identifier callee producing `TYPE`, duplicate `ObjectLiteral` keys resolving to the last value.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the full suite.** Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "Port literals, identifiers, member access, and builtin/host calls"`

---

### Task 8: `SetStatement` — three assignee forms plus block-capture

**Files:**
- Modify: `src/main/java/se/alipsa/hfjinja/internal/runtime/Interpreter.java` (replaces Task 5's
  `SetStatement` placeholder arm)
- Test: `src/test/java/se/alipsa/hfjinja/internal/runtime/InterpreterTest.java`
- Modify: `upstream/mapping.yml`, `tools/corpus/error-patterns-0.5.9.json`,
  `src/test/resources/corpus/v1.jsonl`

Port `upstream/vendor/src/runtime.ts:1559-1595` (`evaluateSet`). This is genuinely this slice's work,
not an oversight to defer: `ast-allowlist.json` tags `SetStatement` **M2**, WP3 already ships
`Statement.SetStatement(Expression assignee, Expression value, List<Statement> body, SourceLocation
location)` (`Statement.java:124-133`), and the Goal line already names it — it was simply missing a
task until now.

**The block-capture form needs a real answer for what an `ExecResult` means as an rhs.**
`rhs = node.value() != null ? evaluateExpression(node.value(), env, budget) : evaluateBlock(node.body(), env)`
— the `Expression` branch returns a `Value` directly (nothing new here); the block-capture branch calls
`evaluateBlock` (Task 5), which returns `ExecResult`, not a `Value`. Two cases:
- `ExecResult.Normal(output)` → the captured block completed normally; wrap it as
  `new Value.StringValue(output)`, matching upstream's own `evaluateBlock` returning a `StringValue`
  unconditionally. Proceed to the assignee dispatch below with this as `rhs`.
- `ExecResult.Break`/`Continue.INSTANCE` → a `{% break %}`/`{% continue %}` inside
  `{% set x %}...{% endset %}` inside an enclosing `for` loop. Upstream calls `evaluateBlock` with no
  try/catch around it, so the raw `BreakControl`/`ContinueControl` exception simply unwinds straight
  out of `evaluateSet` — the assignment never happens, and (per Task 5's own established rule) the
  captured block's own partial output is discarded, same as any other nested block. Port this as:
  `evaluateSet` immediately returns that same `ExecResult` singleton, performing no assignment at all,
  before reaching the assignee dispatch. This is the identical "discard and propagate" mechanism Task
  5 already established for `evaluateBlock`/`evaluateIf` — not a new rule, just a new place it applies.

**Assignee dispatch — `if`/`else if` on `instanceof`, not a `switch`.** `SetStatement.assignee()` is
statically typed `Expression` (`Statement.java:124-125`), and `Parser.parseSetStatement`
(`Parser.java:126-141`) parses it with the same general `parseExpressionSequence` used for any
expression — `{% set 1 + 2 = 3 %}` parses without error; only `evaluateSet`'s own runtime type-check
rejects it. Because only 3 of `Expression`'s 18 leaves are ever meaningful here, use an `instanceof`
pattern-matching `if`/`else if` chain terminated by a real `else`, not an exhaustive `switch` — the
Global Constraints exhaustive-switch rule targets `evaluateStatement`'s and `evaluateExpression`'s own
top-level dispatch, not every helper that happens to take an `Expression`; forcing 15 pointless arms
here would be needless churn, and upstream's own structure (an `if`/`else if`/`else` chain, not a
`switch (assignee.type)`) agrees.

- **`Identifier`** → `environment.setVariable(name, rhs)` (`setVariable`, not `set` — the plan's
  existing naming decision, pre-answered, see Task 2).
- **`TupleLiteral`** → three distinct upstream errors, each anchored at `node.location()` (matching
  Task 6's sibling convention of anchoring the *statement's* location, not a sub-expression's):
  - `!(rhs instanceof Value.ArrayValue)` → `TemplateRenderException("Cannot unpack non-iterable type
    in set: " + rhs's type name, ErrorCategory.TYPE, node.location())`. **Distinct message from Task
    6's own unpack error** (`Cannot unpack non-iterable type: X`, no `" in set"` suffix,
    `runtime.ts:1636`) — needs its own regex pattern, not a reuse of Task 6's.
  - Length mismatch → `TemplateRenderException("Too " + (tuple longer ? "few" : "many") + " items to
    unpack in set", ErrorCategory.VALUE, node.location())` — `VALUE` fits ("a value was outside the
    range or shape an operation requires"); this isn't a call, so `ARITY` doesn't apply despite the
    "wrong count" flavor.
  - A tuple element that isn't `Identifier` → `TemplateRenderException("Cannot unpack to non-identifier
    in set: " + elem's type name, ErrorCategory.TYPE, node.location())`.
  - On success: for each `(identifier, value)` pair, `environment.setVariable(identifier.value(),
    value)` — same method as the plain-`Identifier` form.
- **`MemberExpression`** → requires Task 1's `ObjectValue` mutability decision (`values()` now a real
  mutable `LinkedHashMap`, not wrapped in `Collections.unmodifiableMap`):
  - Evaluate `member.object()`; `!(instanceof Value.ObjectValue)` →
    `TemplateRenderException("Cannot assign to member of non-object", ErrorCategory.TYPE,
    node.location())`.
  - `member.property()` not `Expression.Identifier` →
    `TemplateRenderException("Cannot assign to member with non-identifier property",
    ErrorCategory.TYPE, node.location())`.
  - On success: `objectValue.values().put(identifier.value(), rhs)` — an in-place mutation, visible
    through every alias of the same `ObjectValue`, matching upstream's own reference semantics exactly
    (verify with a test asserting `{% set y = x %}{% set y.a = 2 %}{{ x.a }}` renders `"2"`, not
    empty — the one behavior this whole design exists to preserve).
- **Anything else** (the `else` terminating the chain) →
  `TemplateRenderException("Invalid LHS inside assignment expression", ErrorCategory.SYNTAX,
  node.location())` — a structural authoring mistake independent of runtime data, the same
  category and reasoning Task 5 already used for a bare `{% break %}` with no enclosing `for`.

**Return value.** `evaluateSet` always returns `ExecResult.Normal("")` on the assignment paths — `{%
set %}` never itself produces output (matching upstream's `evaluateSet` returning `NullValue`, which
`evaluateBlock`'s own accumulation then filters out, same mechanism as `Comment`, Task 5).

- [ ] **Step 1: Write the failing tests** covering: `{% set x = 1 %}{{ x }}` → `"1"`; `{% set (a, b) =
  (1, 2) %}{{ a }}{{ b }}` → `"12"`; the three tuple-unpack errors (non-iterable rhs, length mismatch
  both directions, non-identifier element) with their exact `ErrorCategory`s; `{% set obj.key = 1 %}`
  mutating a pre-existing `ObjectValue`, including the aliasing case (`{% set y = x %}{% set y.a = 2
  %}{{ x.a }}` → `"2"`); the two `MemberExpression` errors (non-object target, non-identifier
  property); `{% set x %}captured{% endset %}{{ x }}` → `"captured"`; a `{% break %}` inside `{% set x
  %}...{% endset %}` inside an enclosing `for` propagates `Break` with no assignment (`x` stays
  undefined afterward); `{% set 1 + 2 = 3 %}` throws `SYNTAX`.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement**, replacing Task 5's `SetStatement` placeholder arm with the real dispatch
  above.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Add corpus records and classifier patterns**, `self.`-prefixed, verified against the
  oracle: the tuple-unpack-in-set errors (`^Cannot unpack non-iterable type in set: (?<type>.+)$` →
  `TYPE`, `^Too (few|many) items to unpack in set$` → `VALUE`, `^Cannot unpack to non-identifier in
  set: (?<type>.+)$` → `TYPE`), and the two `MemberExpression` errors (`^Cannot assign to member of
  non-object$` and `^Cannot assign to member with non-identifier property$`, both → `TYPE`). The
  aliasing-mutation case is text-bearing, not error-bearing — no classifier pattern needed for it.
- [ ] **Step 6: Commit** — `git commit -m "Port SetStatement: identifier, tuple-unpack, member-mutation, and block-capture forms"`

---

### Task 9: `Interpreter`'s public entry point, and wiring `Template.render(...)`

**Files:**
- Modify: `src/main/java/se/alipsa/hfjinja/internal/runtime/Interpreter.java` (adds the public entry
  point on top of Tasks 4–7's package-private evaluation methods)
- Modify: `src/main/java/se/alipsa/hfjinja/Template.java`
- Test: `src/test/java/se/alipsa/hfjinja/TemplateTest.java` (create if it doesn't already exist)
- Modify: `upstream/mapping.yml`

**The public/private boundary.** `internal.runtime` exposes exactly one public type:

```java
public final class Interpreter {
  private Interpreter() {}

  public static void render(
      Statement.Program program, Value.ObjectValue context, RenderOptions options, Appendable output) {
    ...
  }
}
```

`Environment`, `RenderBudget`, and `ExecResult` are constructed and consumed entirely inside this
method and its private helpers — `Template.java` never touches them. `Template.java`'s
responsibility is: convert the caller's `Map<String, ?>` via `Values.fromHost(context)` and cast to
`Value.ObjectValue` (a `Map` input always converts to `ObjectValue`, confirmed at `Value.java:301-320`
— rejects non-`String` keys with `HOST_CONVERSION`, never produces another variant), then call
`Interpreter.render(...)`.

**Correcting the stub inventory.** Only two of `Template.java`'s four `render(...)` overloads
currently throw; the other two are pure delegations. Delete the dead private `unsupported()` helper.

**Wrapping `IOException`.** None of `Template.java`'s `render(...)` overloads declare `throws
IOException`, but `Appendable.append(CharSequence)` is checked. `Interpreter.render`'s internal writes
must catch `IOException` and rethrow as `TemplateRenderException(..., ErrorCategory.OUTPUT,
location)`.

**Seeding globals and context — settled, not a fork.** Upstream's `Template.render` calls `env.set`
for both the 9 globals and every context entry (`index.ts:37,41-43`), which is *why* a collision
throws — there's nothing to arbitrate. Seed both via `Environment.set` (declare-or-throw). **Catch the
resulting `IllegalStateException` at this one seeding call site and rethrow
`TemplateRenderException(message, ErrorCategory.VALUE, program.location())`** — matching Task 2's
corpus record and classifier pattern exactly. Without this, `Template.parse("{{ range }}").render(Map
.of("range", 5))` throws a raw `IllegalStateException` out of the public API instead of a categorized
`HfJinjaException`. `program.location()` here is nominal, not a real error site — a global/context
name collision has no source position of its own, so this reports the template's very first
character (typically `(0,1,1)`) rather than anything the collision actually happened "at." Worth a
one-line code comment at the catch site so a reader doesn't go looking for why the reported column
points at the start of the template.

**The `Appendable` buffering design.** Break/continue's normal-path discard (Task 5) means any block
nested inside a currently-executing `for`-loop iteration must fully buffer its output in memory — it
cannot be safely written to the real output stream until the whole iteration resolves to `Normal`.
This slice implements one `evaluateBlock` that always fully buffers (matching upstream's own
single-string accumulation). `Interpreter.render`'s `Appendable` path flushes **per top-level
`Program` statement**, appending each one's resolved output as soon as it resolves, rather than
buffering the whole program as one block. A template whose entire content is one giant top-level `for`
loop gets no memory benefit from this — a stated, honest limitation, not a hidden bug. The
String-returning overloads collect that same per-statement output into a local `StringBuilder` and
return it whole.

- [ ] **Step 1: Write the failing tests** — `Template.parse("Hello {{ name }}!").render(Map.of("name",
  "world"))` → `"Hello world!"`; an `If`/`For` combination; a render that throws partway through a
  String-returning call propagates the exception with nothing else observable; the same failure
  through an `Appendable` overload leaves whatever prior top-level statements had already resolved,
  observable via the caller's `StringBuilder`; `Template.parse("{{ range }}").render(Map.of("range",
  5))` throws `TemplateRenderException`/`VALUE`, not `IllegalStateException`; the existing
  `templates.no-template` and `templates.for-loop` corpus fixtures as literal test cases here (not
  yet through an automated corpus runner — that's Slice 4).
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement `Interpreter.render(...)`.** Construct a fresh `Environment`/`RenderBudget`
  once per call. Seed the 10 built-in/namespace names and then `context`'s entries via `set`,
  catching `IllegalStateException` as described above. Implement `Template.java`'s two real overloads
  as thin wrappers.
- [ ] **Step 4: Run the full suite, including `./gradlew check`.** Expected: PASS.
- [ ] **Step 5: Run `javadoc -private -Xdoclint:all -Xmaxwarns 100000`** and confirm no new warnings.
- [ ] **Step 6: Commit** — `git commit -m "Wire Template.render through the new interpreter"`

## What's Next

Slices 2–4 get their own plan docs, written when their turn starts:

- **Slice 2** — `BinaryExpression`/`UnaryExpression` full JS-double arithmetic, the rest of
  truthiness/equality/`in`/`not in` beyond this slice's subset; **unifying** both
  `AstSnapshot.number()` and `AstSnapshot.q()` with the `JsFormat` cores Task 4 already extracted (a
  cross-package test-code change, deleting each method's body in favor of a call — not a first-time
  extraction; that already happened in Slice 1).
- **Slice 3** — Filter/test dispatch registry, `SelectExpression`'s `test` clause, `Ternary`,
  general-purpose `tojson` (with real options — `indent`, `sort_keys`, `ensure_ascii`, unlike Task 4's
  single fixed configuration), and the `MemberExpression` builtin-method fallback.
- **Slice 4** — A Java-side corpus differential test (checked-in-golden pattern, not a live Node
  subprocess at JUnit time), corpus expansion via `tools/corpus/convert-upstream-tests.mjs`, and G4
  gate closure.
