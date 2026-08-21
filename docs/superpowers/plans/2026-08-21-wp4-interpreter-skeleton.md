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
`Interpreter` itself — see Tasks 2–9. The one exception, `JsFormat` (Task 4), deliberately does
**not** live in `internal.runtime` — it lives flat in `se.alipsa.hfjinja.internal`, `public`, same
posture as `Values`/`HostFunctions`, so Slice 2 can make the existing test-only
`AstSnapshot.number()`/`AstSnapshot.q()` delegate to it without a relocation first. Named `JsFormat`,
not `JsNumberFormat` — it hosts both the shared number-formatting core and the shared JSON
string-quoting core (`quote(String)`), so a number-only name would already be wrong on arrival. `Interpreter` is a Java 21
pattern-matching `switch` over the
`Statement`/`Expression` sealed hierarchy from WP3, evaluating against an `Environment` scope chain
and a per-render `RenderBudget`. Values flow through WP2's existing `internal.Value` sealed model
(restructured in Task 1 to add `TupleValue` and `CallableValue` variants and to nest its implementations, matching the
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
- **`utils.ts` stays `planned` this slice.** Task 7 implements `range()` and `strftime_now` as small
  package-private runtime helpers, not a separate Java utility package; add that rationale as a YAML
  comment so the mapping is not read as an unimplemented upstream behavior.
- **`index.ts` stays `planned` this slice, including after Task 9.** Its globals/context seeding is
  deliberately ported into `Interpreter.render` because Java's public rendering boundary is
  `Template`, not a separate index module; add the same explanatory YAML comment. Do not change
  `upstream/ast-allowlist.json`, `tools/corpus/run-node-oracle.mjs`, or `nodeCorpusVerify` in this
  slice.
- **The Java build is offline.** `./gradlew check` also invokes the pinned Node corpus/version tasks;
  Node is otherwise used only for explicit oracle/corpus verification, never as a production dependency.
- **No static mutable state in the interpreter.** A fresh `Environment` and `RenderBudget` per
  `render()` call; no lazy memoization, no cross-render caches.
- **Java 21 toolchain**, JUnit 5, two-space indent, no wildcard imports.
- **`{@code}` spans must contain balanced braces.** Run
  `javadoc -private -Xdoclint:all -Xmaxwarns 100000` before committing, not just `./gradlew javadoc`.
- **`upstream/upstream-lock.json` is JSON** — it cannot carry a rationale comment the way
  `mapping.yml` (genuine YAML) can.
- **New sealed-interface variants are never "currently unused."** Task 1 adds both `Value.TupleValue`
  and `Value.CallableValue`; every exhaustive `switch` over `Value` must gain an arm for both in that
  same commit. `Values.toHost` is the switch that exists then. Task 4 creates two more exhaustive
  switches, `renderText` and `renderJson`, and must include both variants from their first commit.
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

---

## Known Gaps This Slice Leaves Open

- Arithmetic, full equality/truthiness, filters/tests, macros, slices, and keyword/spread arguments
  remain assigned to their stated later slices/work packages. Task 6 nevertheless implements the
  `For` pre-filter mechanism and supports its literal/identifier condition; only general
  `SelectExpression` test-clause dispatch remains Slice 3.
- Slice-1 truthiness: empty strings/arrays/tuples/objects and zero numbers are false; null and
  undefined are false; `CallableValue` is always true.
- `JsFormat.quote` does not yet escape lone UTF-16 surrogates.
- Bare callable rendering uses the deterministic Java marker `<function>`, not upstream's
  engine-specific JavaScript function-source text. JSON conversion still uses the exact upstream
  `FunctionValue` tag in its error message.

### Task 1: Restructure `Value` for cross-package access, and add `TupleValue`/`CallableValue`

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
the 8 (soon 10) implementations inside `public sealed interface Value` makes them public with no
per-member annotation, in one file, matching that convention exactly.

**Adding `Value.TupleValue`.** Upstream's `TupleValue extends ArrayValue` (`runtime.ts:535-536`,
`override type = "TupleValue"`) is structurally identical to `ArrayValue` but a distinct runtime tag:
real JS subclassing means `instanceof ArrayValue` still matches it, while every string-tag check
against `"ArrayValue"` does not. Add `Value.TupleValue` as a structurally-identical sibling variant
(`record TupleValue(List<Value> values) implements Value {}`, same shape and defensive-copy behavior
as `ArrayValue`).

**Adding `Value.CallableValue`.** The four callable globals (`range`, `raise_exception`,
`strftime_now`, and per-scope `namespace`) are `FunctionValue`s upstream. Add a package-internal
callable record with a nested public functional `invoke(List<Value>, boolean hasKeywordArguments,
SourceLocation)` member; its
implementation captures any per-render `RenderOptions` it needs. This avoids exposing package-private
`Environment` through the public `Value` API while keeping a callable as a normal bound
value: `{% set r = range %}{{ r(3) }}` works, while `{% set range = 5 %}{{ range(1) }}` reports the
upstream non-callable error. `renderText` must give a bare callable a documented deterministic Java
representation; exact JavaScript function-source rendering is a Known Gap, but it must not silently
turn into an unknown identifier or `[0]`.

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
**`Value.CallableValue` never crosses the host boundary:** its `toHost` arm throws
`UndefinedHostValueException("callable value at " + path.describe())`, deliberately using the existing
host-argument failure route while accurately naming the rejected value.

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
  - Add `TupleValue` alongside `ArrayValue` and `CallableValue` alongside the scalar variants.
  - Drop `ObjectValue`'s `Collections.unmodifiableMap(...)` wrap, keeping the defensive
    `new LinkedHashMap<>(values)` copy — see the mutability note above; needed by Task 8.
  - Update the `permits` clause to the qualified nested names, matching `Statement.java`'s own
    `permits Statement.Program, Statement.If, ...` style:
    `permits Value.UndefinedValue, Value.NullValue, Value.BooleanValue, Value.IntegerValue,
    Value.FloatValue, Value.StringValue, Value.ArrayValue, Value.TupleValue, Value.ObjectValue,
    Value.CallableValue`.
  - Add the `Token.java`-style cross-package-access javadoc paragraph to `Value` itself.
  - Remove the `Values` class body from this file (moving to Step 2).

- [ ] **Step 2: Create `Values.java`** with the `Values` class (`public final class Values`, same
  cross-package-access javadoc treatment). Update every reference to a variant type
  (`UndefinedValue`, `BooleanValue`, etc.) to either an explicit `import
  se.alipsa.hfjinja.internal.Value.XxxValue;` per variant or a `Value.XxxValue` qualification.
  **Add `case TupleValue` and `case CallableValue` to the `toHost` switch**, per the design above — the switch will not
  compile without it. Write a dedicated test (`ValuesTest`, Step 5) exercising this new case and the
  one-way-conversion assertion, not just a happy-path pass-through. The callable arm throws
  `UndefinedHostValueException("callable value at " + path.describe())`, so `HostFunctions` follows
  its existing undefined-argument path and reports a `HOST_FUNCTION` failure consistently without
  misreporting the value as undefined.

- [ ] **Step 3: Widen `HostFunctions.java`** — `final class HostFunctions` → `public final class
  HostFunctions`, `static Value invoke(...)` → `public static Value invoke(...)`. Same javadoc
  treatment.

- [ ] **Step 4: Fix the two existing white-box tests.** `HostFunctionsTest.java` and `ValuesTest.java`
  reference the variant types by simple name; add the same per-variant imports Step 2 needed.

- [ ] **Step 5: Add the `Values.toHost(TupleValue)` test**, asserting: a `TupleValue` converts to an
  (unmodifiable) `List` with the same elements as the equivalent `ArrayValue` would; converting a
  callable to a host value is rejected through `UndefinedHostValueException` and therefore produces
  the normal host-function argument failure; a `List` argument
  passed through `fromHost` and back never reconstructs as `TupleValue` (round-trips as `ArrayValue`).
  Also add a direct `ObjectValue`-mutability test: constructing one and calling `.values().put(...)`
  succeeds (no `UnsupportedOperationException`), while `Values.toHost` on the same instance still
  returns a map that throws on `.put(...)` — pinning both halves of the Task 1 decision above in one
  place, not just describing it in prose.

- [ ] **Step 6: Run the full existing suite.** Run: `./gradlew check --offline` — Expected: PASS. This
  is a structural/visibility change plus one additive variant that is *immediately exercised* by
  Step 5's new test.

- [ ] **Step 7: Run `javadoc -private -Xdoclint:all -Xmaxwarns 100000`** and confirm zero new
  warnings.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/se/alipsa/hfjinja/internal/Value.java \
  src/main/java/se/alipsa/hfjinja/internal/Values.java \
  src/main/java/se/alipsa/hfjinja/internal/HostFunctions.java \
  src/test/java/se/alipsa/hfjinja/internal/HostFunctionsTest.java \
  src/test/java/se/alipsa/hfjinja/internal/ValuesTest.java
git commit -m "Restructure Value for runtime access, tuples, and callables"
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import se.alipsa.hfjinja.internal.Value.BooleanValue;
import se.alipsa.hfjinja.internal.Value;
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
    assertInstanceOf(Value.CallableValue.class, child.lookupVariable("namespace"));
  }
}
```

- [ ] **Step 6: Run to verify it fails.**
  Run: `./gradlew test --offline --tests 'se.alipsa.hfjinja.internal.runtime.EnvironmentTest'`

- [ ] **Step 7: Implement `Environment`**, porting `upstream/vendor/src/runtime.ts:563-698` with
  upstream's exact method names and behavior. `set` throws `IllegalStateException` on redeclaration
  (an interpreter-internal invariant; Task 9 decides what the *public* `render` call site does with
  it). `lookupVariable` never throws. Seed each scope with a real `CallableValue namespace`: zero
  arguments return an empty `ObjectValue`; one `ObjectValue` argument returns that same instance; every
  other arity/type produces upstream's namespace error. Do not defer this to call-expression wiring.

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

**Reserve all ten global names at option construction.** Add `"namespace"` to
`RenderOptions.BUILTIN_GLOBALS`, alongside the nine `setupGlobals` names, so
`RenderOptions.builder().hostFunction("namespace", fn).build()` rejects immediately just like
`range`. This is a public-API validation rule, not a render-time seeding error.

**Picking generous defaults.** G4 requires the baseline corpus to "pass under effectively unbounded
budgets" — pick defaults high enough that no plausible chat-template render trips them (e.g.
`maxSteps = 10_000_000`, `maxLoopIterations = 1_000_000`, `maxOutputLength = 10_000_000` chars).
Real exhaustion testing under tight budgets is WP6-scoped.

**What a "step" actually measures in this slice — one AST statement, not one expression node.**
`chargeStep` is charged once per statement `evaluateBlock` evaluates (Task 5); nothing charges inside
`evaluateExpression` itself. `{{ f(g(h(deeply.nested[chain]))) }}` is therefore **one** step, however
many `CallExpression`/`MemberExpression` nodes it recursively evaluates — `maxSteps` bounds statements
executed, not expression-tree work done. That may be the right simplification for a skeleton slice;
stating it here keeps a later reader from assuming
expression cost is bounded by `maxSteps` when it isn't — a template that is one enormous top-level
expression still costs one step no matter how deep it recurses.

- [ ] **Step 1: Write the failing tests** — `PublicApiTest` cases for defaults/rejection and for
  `hostFunction("namespace", fn)` rejecting at `build()` alongside `range`; one `RenderBudgetTest`
  per counter.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Add the three budget limits and reserve `namespace` in
  `BUILTIN_GLOBALS` in this same public-API change.
- [ ] **Step 4: Run the full suite.** Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "Add render-time budget counters and RenderOptions limits"`

---

### Task 4: `Value` → output text — JSON-style formatting, JS number formatting, the TupleValue quirk

**Files:**
- Create: `src/main/java/se/alipsa/hfjinja/internal/JsFormat.java` (`public`, flat `internal`
  package — not `internal.runtime`, see Architecture note above; hosts both the number-formatting
  core and the JSON string-quoting core, see below)
- Modify: `src/main/java/se/alipsa/hfjinja/internal/Value.java` (move its production number-formatting
  helpers to `JsFormat` and repoint `Values.numberValue`)
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
  "Reuse, don't reimplement" below. `NullValue` → `"null"`;
  **`UndefinedValue` → `"undefined"`** (`toJSON`'s `convertUndefinedToNull` parameter is passed
  `false` by `ArrayValue`/`ObjectValue.toString()`, so a nested undefined element renders as bare,
  unquoted `undefined` — this is genuinely reachable, e.g. `{{ [missing_var] }}`);
  `ArrayValue` recurse into `renderJson` again, never into `renderText`; `ObjectValue` emits each key
  with `JsFormat.quote(key)` followed by `": "` and recurses on its value; a `TupleValue`
  anywhere in the recursion — including at the very top — throws
  `TemplateRenderException("Cannot convert to JSON: TupleValue", ErrorCategory.TYPE, location)`
  (`runtime.ts:386-388`'s `default` throw, reached because the switch is on the string type tag and
  `"TupleValue"` matches no explicit case). **A bare `{{ (1, 2) }}` throws** — confirmed it parses
  fine as a top-level `TupleLiteral`, so this is purely a render-time failure, not a parse error —
  the same string-tag-vs-`instanceof` mechanism as Task 6's unpack-target quirk, a different call
  site.

**`CallableValue` is an explicit arm in both switches.** `renderText` returns the chosen deterministic
Java marker `"<function>"`; this is a stated divergence from upstream's engine-specific function-source
text. `renderJson` throws `TemplateRenderException("Cannot convert to JSON: FunctionValue",
ErrorCategory.TYPE, location)`, deliberately retaining upstream's runtime tag in the message. Add
direct tests for `{{ range }}` and `{{ [range] }}`, and list the marker divergence in Known Gaps.

**Threading `SourceLocation` — not optional.** Every other error this plan introduces
(`raise_exception`, every `charge*` call, the `IOException` wrapper) carries a real location; the
`TupleValue`-render failure must too, or it's the one position-less error in an otherwise
consistently-located API. `evaluateStatement`'s delegating `case Expression e ->` arm (Task 5) already
has the expression's own location in hand for `chargeOutput(length, location)` — thread the same one
into `renderText`/`renderJson`, at zero extra cost.

**Reuse, don't reimplement, the number-formatting *and* string-quoting cores — and put both where
Slice 2 can actually use them.** `AstSnapshot.number()` and `AstSnapshot.q()`
(`src/test/java/.../parser/AstSnapshot.java`) already correctly implement, respectively, the
JSON-style numeric formatter and JSON string-quoting (`\`, `"`, control chars, `\b\f\n\r\t`).
Extract the numeric core from production `Value.java`; port only the quoting core from `AstSnapshot`
into `JsFormat` —
**`public final class`, flat in `se.alipsa.hfjinja.internal`**, not package-private inside
`internal.runtime` — same cross-package-access posture and javadoc treatment already granted to
`Values`/`HostFunctions` (`Token.java:9-11` precedent). This is deliberate, not incidental: a
package-private class inside `internal.runtime` would be invisible to `AstSnapshot` (test code in the
different package `internal.parser`), permanently blocking the unification "What's Next" promises for
Slice 2 for *either* core unless that slice first relocates or widens the class itself. Putting both
in `internal` now, public, means Slice 2's job shrinks to *deleting* `AstSnapshot.number()`'s and
`AstSnapshot.q()`'s bodies in favor of a call each — no relocation needed for either. Three thin
members built from the two shared cores:
- `JsFormat.jsonString(double)`: NaN/±Infinity → `"null"`; these branches are defensive because
  non-finite values are rejected before they can be rendered in this slice.
- `JsFormat.plainString(double)`: NaN → `"NaN"`, ±Infinity → `"Infinity"`/`"-Infinity"`, otherwise the
  shared numeric core.
- `JsFormat.quote(String)`: ported from `AstSnapshot.q()`'s escaping rules unchanged (see the
  lone-surrogate gap below); called by `Interpreter.renderJson`'s `StringValue` case rather than
  reimplemented inline there — the identical port-not-duplicate rationale as the numeric cores, and
  the reason `renderJson`'s own description above says `JsFormat.quote`, not "renderJson's own
  escaping logic."

This slice does **not** yet make `AstSnapshot.number()`/`AstSnapshot.q()` delegate to `JsFormat`.
`JsFormatTest` validates the extracted production formatter and quote implementation directly; Slice 2
does the test-code delegation, now unblocked by the package choice above.


**Use the existing production formatter.** `Value.java` already has `shortestJsDecimal(double)` and
`formatJsDecimal(BigDecimal)`, in the same `internal` package. Extract those methods (and their small
private support) into `JsFormat`; do not copy `AstSnapshot.number()` or write a third formatter.
Their `HALF_EVEN` shortest-round-trip search implements ECMA's tie rule and handles normal and
subnormal values without a `Double.MIN_NORMAL` guard. Validate `5e-324`, `1e-323`, `2e-323`, and
`1e-322` as literal outputs. `Values.numberValue` and both host-result markers reject non-finite
doubles, so `JsFormat`'s NaN/Infinity branches are defensive and unreachable in this slice.


**Lone surrogates — a real, still-open gap, now correctly located and testable.** `AstSnapshot.q()`
escapes `\`, `"`, `\b\f\n\r\t`, and `c < 0x20`, passing everything else through raw. `JSON.stringify`
additionally escapes unpaired UTF-16 surrogates — confirmed against the pinned Node build:
`JSON.stringify("a\ud800b")` emits the six literal characters `\ud800` (backslash-u-d-8-0-0), not the
raw code unit. A host-supplied string containing a lone surrogate is reachable the same way as the
(now-fixed) subnormal case (`Values.fromHost` on an arbitrary `String`) and renders the raw, unescaped
surrogate instead. **Decision: out of scope for this slice, stated explicitly rather than left
implicit — and directly testable where it is stated.**
`JsFormat.quote`'s javadoc says plainly that it ports `AstSnapshot.q()`'s rules as-is and does not
escape unpaired surrogates; `JsFormatTest` pins the current (documented-wrong) output for a lone
surrogate directly against `JsFormat.quote`, which compiles and runs in-package; this is listed in
Known Gaps rather than being an unremarked divergence.

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
  `["a","b"]` renders `["a", "b"]` (quoted), `[2.0]` renders `[2]` (not `[2.0]`), an object renders
  `{"a": 1, "b": "x"}`, a tuple throws
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
**Decision, made here without oracle backing:** `Interpreter.render` is the implementation site that
converts a `Break` or `Continue` escaping the top-level program into `TemplateRenderException(...,
ErrorCategory.SYNTAX, program.location())`. Package-private evaluators deliberately return the
singleton unchanged, so their white-box tests assert that result; the public-render test in Task 9
asserts the categorized exception. No corpus record is possible because the oracle message is empty.

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
render that trips `maxOutputLength` may have discarded most of what it charged. Charging
`chargeOutput` in `evaluateBlock` for each statement's contribution — wrong even before the
`evaluateStatement`/`evaluateExpression` split, since `{% for i in [1,2,3] %}XXXX{% endfor %}` would
charge the body's 12 characters once in the loop's own inner block evaluation and again when the `For`
statement's aggregated 12-character result reaches the outer block — 24 charged for 12 rendered
characters, compounding with nesting depth. Wrong twice over after the split, since `evaluateBlock` no
longer even sees a `Value` to call `renderText` on. **Detects, does not prevent, an oversized single
value.** Because the charge happens *after* `renderText`/`renderJson` has already built the full
string, a single `{{ huge_object }}` still allocates that entire string in memory before
`maxOutputLength` can reject it — at the slice's generous 10,000,000-char default, up to ~20MB
transient for one statement. Stated here as an accepted limitation of this slice; incremental charging
inside `renderJson` itself (rather than after it
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
  block too, discarding what the outer block had already accumulated; a bare `{% break %}` evaluated
  directly returns `Break.INSTANCE`; `chargeOutput` is actually invoked, exactly once per delegating-arm
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
  when `node.loopVariable()` is a `TupleLiteral`, the **current item being unpacked** must be checked
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
  (error-bearing). The pattern table already includes Task 2's declaration-conflict pattern — **add
  `^Cannot unpack non-iterable type: (?<type>.+)$` → `ErrorCategory.TYPE` in this
  same step**, or `nodeCorpusVerify` fails on the unmatched message.
- [ ] **Step 6: Commit** — `git commit -m "Port For loops, including the noIteration and tuple-unpack quirks"`

---

### Task 7: Literals, `Identifier`, `MemberExpression`, restricted `CallExpression`

**Files:**
- Modify: `src/main/java/se/alipsa/hfjinja/internal/runtime/Interpreter.java`
- Modify: `src/main/java/se/alipsa/hfjinja/RenderOptions.java` (correct clock/zone javadoc for
  `strftime_now`'s required first-use inputs)
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
- `CallExpression`: evaluate the callee to a `Value` first (an `Identifier` restriction is wrong).
  Only `CallableValue` and adapters around registered host functions are callable; otherwise throw
  `TemplateRenderException("Cannot call something that is not a function: got " + type, TYPE,
  location)`. This preserves aliases and shadowing: `{% set r = range %}{{ r(3) }}` works, and a
  shadowed `range` fails as an integer rather than being name-dispatched. `RenderOptions.Builder.build()`
  rejects host-function names colliding with `BUILTIN_GLOBALS`.
  The callable shape carries `hasKeywordArguments`, so the host adapter can delegate unchanged to
  `HostFunctions.invoke(name, function, args, hasKeywordArguments, location)`. In this slice every
  call reaches it with `false`: `KeywordArgumentExpression` remains a WP5 placeholder, and WP5 turns
  the flag on and thereby exposes the existing host-keyword rejection behavior.
  - `range(start, stop?, step = 1)`: confirmed exact signature at `upstream/vendor/src/utils.ts:8-13`
    — when `stop` is omitted, `stop = start; start = 0` (`range(5)` means 0..5). Throws
    `Error("range() step must not be zero")` for `step === 0`. Result is a real `Value.ArrayValue` of
    `IntegerValue`s (matching upstream's `convertToRuntimeValues` wrapping, `runtime.ts:1877-1901`),
    renderable via Task 4's `renderText(Value, location)` unchanged — `{{ range(3) }}` → `[0, 1, 2]`. Port as a
    small package-private helper inside `internal.runtime`.
  - `raise_exception(message)`: throws `TemplateRenderException(message, ErrorCategory.EXPLICIT_RAISE, location)`.
  - `strftime_now(format)`: implement, rather than assume, the required first-use failure: if either
    clock or zone is absent, throw `TemplateRenderException` with `ErrorCategory.VALUE` at the call
    location. Correct `RenderOptions` javadoc to say these options are required by this global (they
    do not default to system values). With both present, port `%Y`, `%m`, `%d`, `%b`, `%B`, `%H`, `%M`,
    and `%%` from `utils.ts:73-99`; use fixed C/POSIX English month names, a deliberate spec
    divergence from upstream's locale-dependent `Intl.DateTimeFormat`.

- [ ] **Step 1: Write the failing tests** for each bullet above, including: negative array indexing,
  absent-key member access on an object, the string-vs-array out-of-range distinction, tuple member/
  index access behaving like array access, `range()`'s three arities and its zero-offset-when-stop-
  omitted behavior (and its rendered output via Task 4), `raise_exception` producing
  `EXPLICIT_RAISE`, `namespace()` rendering `{}`, aliases and shadowing of `range`, the exact
  non-callable error, missing clock/zone failing with `VALUE`, fixed-clock POSIX directive outputs,
  duplicate `ObjectLiteral` keys resolving to the last value, and member access on any other scalar
  returning `UndefinedValue.INSTANCE` (`{{ 5.foo }}` renders empty).
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
(`Parser.java:131-146`) parses it with the same general `parseExpressionSequence` used for any
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
  - `!(rhs instanceof Value.ArrayValue || rhs instanceof Value.TupleValue)` → `TemplateRenderException("Cannot unpack non-iterable type
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
  `TemplateRenderException("Invalid LHS inside assignment expression: " + JSON-equivalent AST
  snapshot of assignee, ErrorCategory.SYNTAX, node.location())` — a structural authoring mistake independent of runtime data, the same
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
  undefined afterward); `{% set 1 + 2 = 3 %}` throws `SYNTAX` with the JSON-equivalent assignee
  snapshot appended to the exact upstream message.
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
  The set arity pattern intentionally does not cover for-loop arity failures, whose upstream message
  lacks the `in set` suffix; add a separate pattern only with a for-loop error corpus record.
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

**Stub inventory.** Only two of `Template.java`'s four `render(...)` overloads
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

Seed `range`, `raise_exception`, and `strftime_now` as `CallableValue`s here, with closures over the
per-render options; `Environment` itself seeds its own `namespace` callable. Thus all ten global slots
are actual values before context insertion, and `CallExpression` has no special name-dispatch path.
Then seed every `options.hostFunctions()` entry as a `CallableValue` adapter to `HostFunctions.invoke`;
because this also uses `set`, a context key with the same name throws the documented
`Variable already declared`/`VALUE` error rather than silently shadowing the host function.

**The `Appendable` buffering design.** The Appendable overloads fully buffer, then make one terminal
write; they exist for API shape, not streaming. `Interpreter.render` calls
`evaluateBlock(program.body(), env, budget)` exactly once so top-level statements are charged at its
sole `chargeStep` site. It writes the resulting `Normal` output once; on a render failure or escaping
top-level `Break`/`Continue`, it writes nothing and converts the latter to Task 5's `SYNTAX` error.

- [ ] **Step 1: Write the failing tests** — `Template.parse("Hello {{ name }}!").render(Map.of("name",
  "world"))` → `"Hello world!"`; an `If`/`For` combination; a render that throws partway through a
  String-returning call propagates the exception with nothing else observable; the same failure
  through an `Appendable` overload leaves the caller's `StringBuilder` empty; `Template.parse("{{ range }}").render(Map.of("range",
  5))` throws `TemplateRenderException`/`VALUE`, not `IllegalStateException`; a registered host
  function is callable and a same-named context key fails with `VALUE`; bare top-level `break`
  and `continue` throw `SYNTAX`; the existing
  `templates.no-template` and `templates.for-loop` corpus fixtures as literal test cases here (not
  yet through an automated corpus runner — that's Slice 4).
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement `Interpreter.render(...)`.** Construct a fresh `Environment`/`RenderBudget`
  once per call. Seed the 10 built-in/namespace names, registered host-function adapters, and then
  `context`'s entries via `set`,
  catching `IllegalStateException` as described above. Call `evaluateBlock` once and convert an
  escaping `Break`/`Continue` as Task 5 specifies. Implement `Template.java`'s two real overloads as
  thin wrappers.
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
