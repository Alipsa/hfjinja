# WP5 Slice 3 — Macros, Call Blocks, and Filter Blocks

## Goal

Implement `Statement.Macro`, `Statement.CallStatement`, and `Statement.FilterStatement`, matching
pinned @huggingface/jinja 0.5.9 semantics. This retires the last three "M3" AST nodes in
`upstream/ast-allowlist.json` (`Macro`, `FilterStatement`, `CallStatement`) and the corresponding
`unsupported(...)` placeholders in `Interpreter.evaluateStatement`. It does not take on
`{% do %}`, parameterized `test` definitions, or closing any pre-existing filter-dispatch
divergence documented by slice 2.

## Current state and scope

The parser already fully supports all three statements — `parseMacroStatement`,
`parseCallStatement`, and `parseFilterStatement` in `Parser.java` — so this slice is entirely an
`Interpreter.java` (plus one small `Value.java`/`Environment.java` visibility change) exercise.
Today, `evaluateStatement` routes all three to `unsupported(...)`, which throws
`UNDEFINED_OR_ACCESS`.

Upstream centralizes all three in `runtime.ts`:

- `evaluateMacro` binds `node.name` to a `FunctionValue` in the *defining* environment. The
  function body is not evaluated until called; declaring a macro produces `NullValue` (no visible
  output).
- `evaluateCallStatement` builds a `caller` `FunctionValue` capturing `node.body`, evaluates
  `node.call.args` with the same `evaluateArguments` helper calls already use (ported in slice 2),
  binds `caller` into a child of the call-site environment, and invokes the callee.
- `evaluateFilterStatement` renders `node.body` to a string, then applies `node.filter` to it using
  the same `applyFilter` logic `{{ value | filter }}` uses.

The pivotal architectural fact, confirmed by running the pinned oracle (`upstream/vendor/dist/index.js`,
Node v26.7.0) rather than assumed from reading `runtime.ts`: **every `FunctionValue` invocation
receives the *call-site* environment as its second argument**, not the environment captured at
definition time:

```ts
// evaluateCallExpression: return (fn as FunctionValue).value(args, environment);
// evaluateMacro:          new FunctionValue((args, scope) => { const macroScope = new Environment(scope); ... })
```

So a macro's body executes in a scope whose *parent is wherever it was called from*, not where it
was defined. Builtins (`range`, `raise_exception`, `strftime_now`, host functions) already ignore
this second argument; only macros (and `caller`, itself a `FunctionValue`) read it. Today's
`Value.CallableValue.Callable` interface has no such parameter:

```java
public interface Callable {
  Value invoke(List<Value> arguments, boolean hasKeywordArguments, SourceLocation location);
}
```

Widening it to add the call-site environment is the one interface-level change this slice makes;
see Step 2. `Value` (package `se.alipsa.hfjinja.internal`) cannot reference `Environment` (package
`se.alipsa.hfjinja.internal.runtime`, currently package-private) across that package boundary
without `Environment` becoming a `public` type. That is safe: `module-info.java` exports only
`se.alipsa.hfjinja`, so nothing outside the module can see `internal.runtime` regardless of Java
visibility — this is not a public-API change.

Representing macros as another `Value.CallableValue` (upstream's own `FunctionValue` unification),
rather than inventing a new `Value.MacroValue` sealed-interface member, is deliberate: it requires
touching zero existing exhaustive `switch` sites over `Value` (`truthy`, `type`, `JsOperations`'
type-name table, `tojson`, etc. — all already handle `Value.CallableValue`), whereas a new sealed
member would force a matching case into every one of them for a distinction upstream itself does
not make.

In scope: macro declaration and invocation (positional/keyword/default argument binding,
call-time — not definition-time — default evaluation), `{% call %}...{% endcall %}` blocks
including `caller()` and `callerArgs`, `{% filter %}...{% endfilter %}` blocks reusing the existing
filter dispatch, a new `RenderBudget` macro-call-depth counter (justified below), and corpus/Java
regression coverage. Out of scope: fixing the pre-existing "Unknown filter" message-format
divergence discovered while researching this slice (see the note at the end of this section),
`{% do %}`, and any AST node not in the "M3" set.

**Discovered, out-of-scope divergence.** While confirming filter-block behavior against the
oracle, `{{ 'hi' | totallynotafilter }}` was found to produce `Unknown StringValue filter:
totallynotafilter` upstream (operand-type-qualified), while Java's `filter()` default case throws
plain `"Unknown filter: " + name`. This is a WP4-slice-3 (filters) divergence, unrelated to
macros/call/filter-block statements, and touches every filter call site if fixed. Do not fix it as
part of this slice; flag it as a candidate follow-up instead.

## Known gaps this slice leaves open

- **Bare `break`/`continue` inside a macro body or a `{% call %}` block body does not bleed into
  the caller's enclosing loop.** Verified against the oracle: `{% macro f() %}{% break %}{% endmacro
  %}{% for i in [1,2,3] %}{{ i }}{{ f() }}{% endfor %}` renders `""` upstream — the bare `break`
  inside the macro throws a raw `BreakControl` that unwinds *through* the macro call, the
  containing expression, and the containing statement, and is only caught by the `for` loop at the
  **call site**, discarding the entire in-progress iteration (including the `i` already appended).
  This works upstream because control flow is implemented with real JS exceptions that cross
  function-call boundaries transparently. Our port represents control flow with the
  `ExecResult.Break`/`Continue` sentinel *return values* threaded through `evaluateBlock`, which
  cannot cross a `Value.CallableValue.Callable.invoke(...)` boundary (it returns a bare `Value`).
  Faithfully reproducing the bleed-through would mean redesigning control flow as thrown exceptions
  throughout the interpreter — far outside this slice. Instead: when a macro's or call-block's body
  evaluates to a non-`Normal` `ExecResult`, throw the same `"break or continue outside a for loop"`
  `SYNTAX` error the top-level `render()` check already throws, at the macro/call invocation's own
  location. `{% filter %}...{% endfilter %}` bodies are *not* affected — confirmed via the oracle
  (`{% for i in [1,2,3] %}{{ i }}{% filter upper %}{% break %}{% endfilter %}{% endfor %}` → `""`,
  identical bleed-through) — because `FilterStatement` never crosses a `Callable` boundary in this
  port: it stays inside the `ExecResult`-returning statement flow and can propagate a non-`Normal`
  result outward exactly as `evaluateSet`'s existing block-capture already does. Practically, a real
  chat template is exceedingly unlikely to contain a bare, syntactically-unguarded `break`/`continue`
  inside a macro or call-block body — this is upstream's own fragile, undocumented corner case (the
  uncaught `BreakControl` upstream has an *empty* error message, itself a latent defect), not a
  feature worth chasing byte-for-byte. Comment each new invocation site with a reference to this
  bullet, and add `InterpreterTest` cases named `macroBareBreak_isKnownDivergenceFromUpstream` and
  `callBlockBareBreak_isKnownDivergenceFromUpstream` pinning Java's `SYNTAX` error for these two
  shapes. No corpus record for either — the outputs disagree with upstream by construction.
- **A recursive macro can exhaust the JVM stack; upstream's own recursion depth limit is an
  unspecified, stack-size-dependent artifact, not a real feature.** Empirically bisecting the
  pinned oracle on this repo's toolchain (Node v26.7.0, default stack, run at plan-writing time):
  `{% macro f(n) %}{% if n <= 0 %}done{% else %}{{ f(n-1) }}{% endif %}{% endmacro %}{{ f(1000)
  }}` succeeds at `n=999` and fails at `n=1000` with `Cannot call something that is not a
  function: got UndefinedValue` — a nonsensical error for a macro that is manifestly in scope.
  Raising Node's `--stack-size` moves the exact failure threshold higher (`n=4000` then
  succeeds), which proves this is V8 stack exhaustion corrupting an in-flight environment lookup,
  not a deliberate depth guard (`upstream/vendor/src/runtime.ts` has no such guard at all). **The
  specific depth and error shape are toolchain-dependent, not portable facts**: re-running the same
  template against system Node v20.20.1 (not the pinned v26.7.0) instead fails at `n=999` with a
  raw `RangeError: Maximum call stack size exceeded`, never reaching the "Cannot call something"
  message at all. Both runs agree on the only conclusion this slice relies on — recursion depth is
  bounded by an unguarded native stack, and the failure upstream produces when it's exceeded is not
  a stable, meaningful error — so implementers should re-verify against whichever oracle they
  actually run rather than trust the specific numbers above as reproducible everywhere. This slice
  does not attempt to reproduce that specific wrong-answer artifact. Instead, since a bare Java
  `StackOverflowError` escaping `render()` would violate this project's own documented contract
  ("All documented failures derive from `HfJinjaException`"), this slice adds a genuine
  `RenderBudget` macro-call-depth counter (see Step 4) that raises a clean `RESOURCE_LIMIT` error
  well before the JVM's native stack would overflow. This is a deliberate, safer improvement over
  upstream's own behavior for this input class, not a compatibility gap in the usual sense — but it
  is called out here because it means deeply recursive macros diverge from upstream (a
  `RESOURCE_LIMIT` `TemplateRenderException` in Java vs. upstream's toolchain-dependent wrong-answer
  error at a toolchain-dependent depth) rather than matching it. No corpus record: the two sides
  fail for unrelated reasons at unrelated, non-reproducible depths, so there is no shared oracle
  behavior to pin.
- ~~A host function invoked via `{% call %}` silently receives an extra trailing empty-map
  argument.~~ **Resolved during review, before merge.** This slice originally recorded a decision
  not to special-case stripping the bag before the `HostFunctions` bridge, reasoning that doing so
  would reintroduce the callee-kind-specific asymmetry Step 5 worked to avoid. An external review
  pushed back: `HostFunction` is hfjinja's own public API with zero oracle constraint (host
  functions are a Java-only concept upstream doesn't have at all), so leaking an interpreter-
  internal calling-convention byproduct into that surface is an API-quality regression, not a
  parity trade-off — and stripping the bag *only* inside `HostFunctions.invoke` cannot affect
  `range`/`namespace`/macro parity, since none of those callees are reached through
  `HostFunctions.invoke`. Verified empirically (`{% call record(1) %}` and `{% call record() %}`
  against a registered host function) before and after the change, then fixed:
  `HostFunctions.invoke` now strips a trailing empty `KeywordArgumentsValue` before argument
  conversion, so a host function invoked via `{% call %}` sees exactly the arguments the template
  wrote — `{% call myHostFn(1) %}x{% endcall %}` now delivers `[1]`, not `[1, {}]` — while
  `range`/`namespace`/macro callees (which never reach `HostFunctions.invoke`) are unaffected. The
  `InterpreterTest` regression named in Step 5 was updated to pin this fixed behavior instead of
  the leak.
- **`{% filter safe %}...{% endfilter %}` throws `Unknown filter: safe` instead of rendering the
  body unchanged.** Verified against the oracle: `{% filter safe %}x{% endfilter %}` renders `"x"`
  upstream (`safe` is a no-op filter there, since hfjinja does no HTML auto-escaping to begin
  with). This is the exact same pre-existing gap as the "Discovered, out-of-scope divergence" note
  above — `applyFilter`'s dispatch `switch` in `Interpreter.java` has no `safe` case at all, so
  `{{ x | safe }}` already threw this before this slice. It is flagged here rather than silently
  left alone because this slice is what makes it reachable through a second syntax
  (`{% filter %}` blocks), not because macros/call/filter-block statements caused it. Fixing the
  shared filter dispatch table is out of scope for this slice for the same reason as the message-
  format divergence above — do not fix it here; flag it as a candidate follow-up alongside that
  one.

## Work plan

1. Characterize the pinned runtime before changing Java.

   Run the pinned Node runtime (`upstream/vendor/dist/index.js`) and record exact output/error
   shape for the cases below. Treat this table as a hypothesis to verify, not a foregone
   conclusion — if a row disagrees with what running the oracle actually produces, the oracle wins;
   amend this table and any dependent step before proceeding.

   | Case | Verified result |
   | --- | --- |
   | `{% macro f(a,b=1) %}{{ a }}-{{ b }}{% endmacro %}{{ f(5) }}` | `5-1` |
   | `{% macro f(a) %}{{ a }}{% endmacro %}{{ f() }}` | `Error: Missing positional argument: a` |
   | `{% macro f(a) %}{{ a }}{% endmacro %}{{ f(1,2,3) }}` | `1` (extra positionals silently ignored) |
   | `{% macro f(a=1) %}{{ a }}{% endmacro %}{{ f(z=9) }}` | `1` (unknown keyword silently ignored) |
   | `{% macro f(a=1) %}{{ a }}{% endmacro %}{{ f(5, a=9) }}` | `5` (positional wins over keyword) |
   | `{% set x='outer' %}{% macro g(name=x) %}Hi {{ name }}{% endmacro %}{% set x='shadowed' %}{{ g() }}` | `Hi shadowed` — default evaluated at call time in macroScope, not at definition time |
   | `{% macro f(a,b=a) %}{{ a }}-{{ b }}{% endmacro %}{{ f(1) }}` | `1-1` — later default sees earlier bound param |
   | `{% macro f(*x) %}{{ x }}{% endmacro %}{{ f(1,2) }}` | `Error: Unknown argument type: SpreadExpression` |
   | `{% macro f() %}[{{ caller() }}]{% endmacro %}{% call f() %}body{% endcall %}` | `[body]` |
   | `{% macro f() %}{% set x=5 %}[{{ caller() }}]{% endmacro %}{% call f() %}x={{ x }}{% endcall %}` | `[x=5]` — call-block body sees macro-local state set before `caller()` runs |
   | `{% macro f() %}[{{ caller(1,2) }}]{% endmacro %}{% call(a,b) f() %}{{ a }}-{{ b }}{% endcall %}` | `[1-2]` |
   | `{% macro f() %}{{ caller(1) }}{% endmacro %}{% call(a.b) f() %}{{ a }}{% endcall %}` | `Error: Caller parameter must be an identifier, got MemberExpression` |
   | `{{ caller() }}` (no enclosing `{% call %}`) | `Error: Cannot call something that is not a function: got UndefinedValue` — `caller` is just an unbound identifier outside a call block, no special-casing needed |
   | `{% call range(3) %}x{% endcall %}` | `"[]"` — a non-macro callee still gets the unconditional empty `KeywordArgumentsValue` bag; see Step 5 |
   | `{% call namespace() %}x{% endcall %}` | `Error: Cannot convert to JSON: KeywordArgumentsValue` — same unconditional bag, different receiver |
   | `{% filter upper %}hi{% endfilter %}` | `HI` |
   | `{% filter join('-') %}{% for i in [1,2] %}{{ i }}{% endfor %}{% endfilter %}` | `1-2` — body renders to the string `"12"`, then `join` splits it by codepoint exactly as the existing string-operand branch of `filterJoin` already does; no filter-side changes needed |
   | `{{ 'hi' | f }}` where `{% macro f(x) %}...{% endmacro %}` is defined | `Error: Unknown StringValue filter: f` — filters are resolved from a fixed built-in table, never from the variable/macro namespace; do not wire macro lookup into filter dispatch |

   Append three corpus records to `src/test/resources/corpus/v1.jsonl` — a representative macro
   round-trip, a representative call-block round-trip, and a representative filter-block round-trip
   — with `source` set to `self-authored; verified against @huggingface/jinja 0.5.9`:

   - `self.macro-default-arguments`: `{% macro greet(name, greeting='Hello') %}{{ greeting }},
     {{ name }}!{% endmacro %}{{ greet('World') }}` → `"Hello, World!"`.
   - `self.call-block-caller`: `{% macro wrap() %}<{{ caller() }}>{% endmacro %}{% call wrap()
     %}inner{% endcall %}` → `"<inner>"`.
   - `self.filter-block`: `{% filter upper %}{{ 'hi' }}{% endfilter %}` → `"HI"`.

   Obtain each expected text by importing `upstream/vendor/dist/index.js` directly and pasting the
   result in, then run:

   ~~~bash
   ./gradlew nodeCorpusVerify --offline
   ~~~

2. Widen the `Callable` invocation contract.

   Make `Environment` (`internal/runtime/Environment.java`) `public` — its constructor stays
   package-private, so nothing outside `internal.runtime` can construct one; only the *type* needs
   to be visible so `Value.Callable`'s method signature can name it. Note in a comment that
   `module-info.java` exports only `se.alipsa.hfjinja`, so this is not a public-API change.

   Add a fourth parameter to `Value.CallableValue.Callable`:

   ~~~java
   Value invoke(List<Value> arguments, boolean hasKeywordArguments, SourceLocation location, Environment environment);
   ~~~

   Update every existing implementation to accept and ignore it: `range`, `raise_exception`,
   `strftime_now`, the host-function bridge lambda in `seed(...)`, and `namespace` in
   `Environment.java`. Update `Interpreter.call`'s single invocation site to pass the call-site
   environment (`e`) through. This step alone should not change any existing test's behavior —
   confirm with:

   ~~~bash
   ./gradlew test --offline --tests 'se.alipsa.hfjinja.internal.runtime.InterpreterTest'
   ~~~

3. Implement `Statement.Macro`.

   In `evaluateStatement`, replace `case Statement.Macro m -> unsupported(...)` with a call to a
   new `evaluateMacro(Statement.Macro node, Environment env, RenderBudget budget)` that:

   - Binds `env.setVariable(node.name().value(), new Value.CallableValue(...))` — `setVariable`,
     not `set`, so a template may redeclare a macro name without the `IllegalStateException` `set`
     would throw (matching `{% set %}`'s own use of `setVariable` and upstream's unconditional
     `environment.setVariable`).
   - Returns `new ExecResult.Normal("")` — a macro declaration produces no output, matching
     upstream skipping `NullValue` results in its own block-concatenation loop.
   - The bound `Callable.invoke(arguments, hasKeywordArguments, location, scope)` implementation:
     1. Builds `macroScope = new Environment(scope)` — `scope` is the *call-site* environment
        received via Step 2's widened interface, not `env` (the macro's own defining environment)
        captured in this closure. This is the single most important line in this slice; get it
        wrong and every corpus row involving call-time default evaluation or call-block scoping
        (Step 1's table) will disagree with the oracle.
     2. Copies `arguments`, and if the last element is a `Value.KeywordArgumentsValue`, pops it
        into a local `kwargs` (mirroring upstream's `args.pop()`).
     3. Iterates `node.args()` by index (not `arguments` — extra passed positionals beyond
        `node.args().size()` are silently ignored, per Step 1's table):
        - `Expression.Identifier`: if no positional value at that index, throw `ARITY`
          (`"Missing positional argument: " + name`) at `location`; otherwise
          `macroScope.setVariable(name, passed)`.
        - `Expression.KeywordArgumentExpression`: resolve value as positional-by-index, else
          `kwargs.get(key)`, else `evaluateExpression(kwarg.value(), macroScope, budget)` — in
          that precedence order, and using `macroScope` (already populated with earlier params) so
          later defaults can reference earlier parameters, per Step 1's table.
        - Anything else (only reachable via a spread or other expression in the parameter-list
          grammar `parseArgs()` shares with call-argument lists): throw `SYNTAX`
          (`"Unknown argument type: " + simple class name"`) at that argument's location —
          consistent with slice 2's `SYNTAX` categorization of `evaluateArguments`'
          positional-after-keyword check, another eval-time grammatical-misuse check.
     4. Evaluates `evaluateBlock(node.body(), macroScope, budget)` inside the macro-depth-counted
        region from Step 4. If the result is not `ExecResult.Normal`, throw the `SYNTAX`
        `"break or continue outside a for loop"` error from the Known Gaps section above at
        `location`; otherwise return `new Value.StringValue(normal.output())`.

4. Add a `RenderBudget` macro-call-depth counter.

   Unlike `steps`/`iterations`/`output` (monotonic totals), macro depth must go up on entry and
   back down on exit, so a template that calls ten independent (non-recursive, non-nested) macros
   in sequence is never charged for depth 10. Add a `private int macroDepth;` field and paired
   `enterMacro(SourceLocation)`/`exitMacro()` methods to `RenderBudget`:

   ~~~java
   void enterMacro(SourceLocation location) {
     if (macroDepth >= options.maxMacroDepth()) fail("Maximum macro call depth exceeded", location);
     macroDepth++;
   }

   void exitMacro() {
     macroDepth--;
   }
   ~~~

   (This checks before incrementing, not the other way around — see the revision note below.)

   Wrap the macro body evaluation from Step 3.4 in `try { budget.enterMacro(location); ... }
   finally { budget.exitMacro(); }` so depth still unwinds correctly when the body throws (an
   `ARITY`/`TYPE`/etc. error partway through a deep recursive call must not leave `macroDepth`
   permanently elevated for the rest of that render). Also call `enterMacro`/`exitMacro` around the
   `caller()` invocation built in Step 5, since call-block bodies recurse through the same
   `Value.CallableValue.Callable` boundary.

   Keep `enterMacro(location)` *inside* the `try`, not called before it in the more commonly seen
   `enter(); try { ... } finally { exit(); }` acquire/release shape — this remains necessary even
   with the check-before-increment form above: when the body itself throws some unrelated error
   (`ARITY`/`TYPE`/etc.) partway through, `macroDepth` *was* successfully incremented on entry and
   still needs the `finally`'s `exitMacro()` to unwind it. Moving `enterMacro` outside the `try`
   would skip that `finally` on that path, leaking one level of depth (moot in practice today,
   since nothing in `src/main` catches `TemplateRenderException` mid-render, so an uncaught
   `RenderBudget` is simply discarded with the rest of that render) — but non-obvious enough, and
   cheap enough to protect against a future change to that assumption, that it is worth a one-line
   source comment at the call site referencing this paragraph rather than relying on this plan
   being read again before someone "simplifies" the placement.

   **Revision note (post-review):** this slice originally shipped `enterMacro` as
   increment-then-check (`if (++macroDepth > limit) fail(...)`), which on the throwing path itself
   left `macroDepth` incremented and relied entirely on the caller's `try`/`finally` placement
   above to unwind it — a correctness-critical invariant enforced only by a source comment.
   External review suggested the check-before-increment form shown above instead: on the
   limit-exceeded path, `macroDepth` is never touched at all, so *that specific* leak becomes
   structurally impossible rather than comment-enforced (the `try`/`finally` requirement above is
   still real and still needed, for the unrelated-error-partway-through case). Adopted as shown.
   The review that raised this also found this slice had shipped with **no test constraining the
   depth limit from below**: the only pre-existing depth test (`f(5000)` failing) passes unchanged
   for *any* `maxMacroDepth` under 5000, including a regression collapsing it to `2` — confirmed by
   mutating `RenderBudget`'s comparison operator and the shipped default and observing the full
   suite stay green either way. Fixed by adding two `InterpreterTest` cases: one asserting the
   boundary itself (`maxMacroDepth(10)` accepts a 10-deep chain, `maxMacroDepth(9)` rejects the
   same chain), and one asserting the shipped default (500) tolerates at least 100 levels of
   ordinary nested recursion without being configured explicitly.

   Add `maxMacroDepth` to `RenderOptions`/`RenderOptions.Builder` alongside the existing
   `maxSteps`/`maxLoopIterations`/`maxOutputLength` (same `positive(...)` validation, same
   `RESOURCE_LIMIT` category via `RenderBudget.fail`). **Unlike the `Environment` visibility change
   in Step 2, this one *is* public API**: `RenderOptions` lives in the exported `se.alipsa.hfjinja`
   package — `module-info.java` has a single `exports se.alipsa.hfjinja;` directive and nothing
   else, so `internal`/`internal.runtime` are unexported by omission, not by any exclusion list —
   so `maxMacroDepth()` and `Builder.maxMacroDepth(int)` are new surface a consumer can call, not an
   internal implementation detail. Treat it accordingly: give both methods the same javadoc style
   as the three existing limit accessors/builders, add a **seventh** field and constructor
   parameter to the private `RenderOptions` constructor (`RenderOptions.java:44-57` — the class
   already has six fields: `clock`, `zoneId`, `hostFunctions`, `maxSteps`, `maxLoopIterations`,
   `maxOutputLength`, at lines 37-42), thread it through `build()`'s return
   (`RenderOptions.java:190-191`), and update the `DEFAULT` constant (`RenderOptions.java:34-35`)
   to include it. The constructor's trailing four parameters are now all adjacent, unlabeled
   `int`s (`maxSteps, maxLoopIterations, maxOutputLength, maxMacroDepth`) — at `DEFAULT`, `build()`,
   and the constructor itself, keep that argument order aligned with the field declaration order;
   nothing but position distinguishes them, and a transposition compiles silently.

   Also extend `PublicApiTest.renderOptionsRejectNonpositiveResourceLimits`
   (`PublicApiTest.java:102-113`) with the same `0`/`-1` `assertThrows(IllegalArgumentException...,
   () -> RenderOptions.builder().maxMacroDepth(...))` pair the three existing limits already have
   there. This is not optional cleanup: `./gradlew check` runs `PublicApiTest`, but a *missing*
   assertion never fails a build, so nothing in Step 7's verification would notice if this pair
   were skipped — the only way this gets caught is by remembering to add it, which is why it is
   named explicitly here rather than left implicit in "same `positive(...)` validation" above.
   Separately, the README's "Errors and limits" section already lists `macro-depth` among the
   dimensions `TemplateOptions`/`RenderOptions` configure (`README.md:130`, written well before
   this slice) — no README change is needed; stating that here saves a future reader from
   re-deriving it.

   Pick the default empirically rather than
   guessing: a trivial (non-interpreter) recursive Java method overflows the default JVM stack at
   roughly 44,700 frames on this toolchain, and this interpreter's own call chain per macro level
   (`evaluateBlock` → `evaluateStatement`/`evaluateExpression` → `call` → the bound lambda →
   `evaluateBlock` again, plus argument evaluation) is substantially deeper per level than that
   probe — so measure the *actual* safe depth once this step compiles (a temporary,
   deeper-and-deeper `{% macro %}` recursion test run under the default JVM stack, without
   `-Xss`), then set the shipped default to a conservative fraction of the observed crash point.
   **Measured, not guessed:** with `maxMacroDepth` set high enough to disable the guard, this
   interpreter's own recursive-macro call chain overflows the default JVM stack between `n=850`
   and `n=940` (flaky in that band across runs, confirming genuine stack-usage variance, not a
   fixed cutoff) — an order of magnitude below the 44,700-frame trivial-recursion probe, as
   expected given how many real frames one Jinja-level macro call costs. The starting proposal of
   `2,000` this paragraph originally floated is provably unsafe: the guard would never fire before
   the native stack does. The shipped default is **500** — comfortably under half of the observed
   crash zone, verified by rerunning the same recursive-macro template with the real
   `RenderBudget.enterMacro` guard active at `n=500, 501, 900, 5000, 50000`: every one fails
   cleanly with `RESOURCE_LIMIT`/`"Maximum macro call depth exceeded"`, never a
   `StackOverflowError`.

5. Implement `Statement.CallStatement`.

   Add `evaluateCallStatement(Statement.CallStatement node, Environment env, RenderBudget budget)`,
   structured like `call()` (Step 2's widened path) but with the extra caller-binding step, mirroring
   upstream's own duplication of `evaluateCallExpression` rather than trying to unify them:

   - Build the `caller` callable once: a `Value.CallableValue` whose `invoke(callerArgs, hasKw,
     callLocation, callerScope)` builds `callBlockEnv = new Environment(callerScope)` (`callerScope`
     is whatever environment is active wherever `caller()` gets called from *inside the macro
     body* — this is what makes Step 1's `call-block-sees-macro-scope` case work with no special
     code), binds `node.callerArgs()` positionally into `callBlockEnv` (each must be an
     `Expression.Identifier`; anything else throws `SYNTAX`
     `"Caller parameter must be an identifier, got " + class simple name`, matching upstream's
     message shape), defaults missing positions to `Value.UndefinedValue.INSTANCE`, then evaluates
     `evaluateBlock(node.body(), callBlockEnv, budget)` under the same
     `enterMacro`/`exitMacro`-wrapped, non-`Normal`-rejecting treatment as Step 3.4.
   - Evaluate `node.call().args()` via the existing `evaluateArguments` helper (Step 2 of slice 2).
     **Unlike `call()`, push the resulting `Value.KeywordArgumentsValue` onto the argument list
     unconditionally, even when `evaluated.keywords()` is empty.** This mirrors a real asymmetry in
     upstream, not an oversight to normalize away: `evaluateCallExpression` only pushes the bag
     when `kwargs.size > 0` (`runtime.ts:1475`), but `evaluateCallStatement` pushes it
     unconditionally (`runtime.ts:1782`). Verified against the oracle, because it is invisible for
     macro callees (`evaluateMacro` pops the trailing bag regardless of whether it's empty, so
     every row in Step 1's table passes either way) and only shows up for non-macro callees:
     `{% call range(3) %}x{% endcall %}` → `"[]"` upstream, not `"[0, 1, 2]"` — the always-present
     empty bag lands in `range`'s `stop` position, so `argument(a, 1)` returns the bag instead of
     `UndefinedValue`, `JsOperations.toNumber` on a `KeywordArgumentsValue` is `NaN`, and the
     `current < NaN` loop guard is immediately false. `{% call namespace() %}x{% endcall %}` →
     `Error: Cannot convert to JSON: KeywordArgumentsValue` upstream — `namespace`'s existing Java
     guard already accepts a lone `KeywordArgumentsValue` argument and returns it verbatim, and
     `renderText`'s existing `KeywordArgumentsValue -> renderJson(...)` case already fails the same
     way for that value shape, so no changes to `range`, `namespace`, or `renderText` are needed;
     conditionally pushing (i.e. "structured like `call()`") reproduces neither result. Add both
     cases as `InterpreterTest` regressions so a future accidental switch back to conditional
     pushing is caught.
   - **The `hasKeywordArguments` boolean passed to `invoke(...)`, by contrast, should still be
     derived as `!evaluated.keywords().isEmpty()` — the same derivation `call()` uses — not from
     whether the bag is present in the argument list.** This is a deliberate Java-port-internal
     decision, not a question the oracle can answer: JS has no equivalent boolean at all (it
     inspects the trailing list element's type directly), so this flag exists purely to let
     `HostFunctions.invoke` cheaply reject caller-supplied keyword arguments, which v1 does not
     support for host functions (see the README's "Host-function keyword arguments" note). If the
     flag instead reflected "a bag is present" — now unconditionally true for every call-block
     invocation — `{% call someHostFn() %}` with zero actual keyword arguments would
     unconditionally fail, a real and easily-hit regression for a case upstream has no opinion on
     at all (host-function kwargs rejection is Java-only). Add an `InterpreterTest` regression
     calling a registered host function from inside a `{% call %}` block with no keyword arguments
     that asserts the **exact argument list the host function lambda observes**, not just that the
     call succeeds — see the Known Gaps entry below for why a bare success assertion would hide the
     actual, more interesting behavior here.
   - Evaluate `node.call().callee()`, reject non-`Value.CallableValue` with the existing
     `"Cannot call something that is not a function"` `TYPE` error, then build a child environment
     of `env` with `caller` bound (`newEnv.setVariable("caller", callerCallable)`), and invoke the
     callee with `newEnv` as the call-site environment (so the macro's own `caller()` lookup
     resolves it) plus the evaluated positional/keyword arguments.
   - `evaluateStatement`'s `Statement.CallStatement` case converts the returned `Value` to output
     text using the *same* Null/Undefined-to-empty-string conversion the `Expression` case already
     uses, **and charges it through `budget.chargeOutput(...)` the same way** (Step 1's table shows
     a `{% call %}` target need not be a macro at all, so this must not assume the result is always
     an already-charged `StringValue` produced by a macro body — see the output-charging note at
     the end of Step 6).

6. Implement `Statement.FilterStatement`.

   Extract the body of `filter(Expression.FilterExpression, Environment, RenderBudget)` into
   `applyFilter(Value operand, Expression filterNode, Environment env, RenderBudget budget,
   SourceLocation location)`. The extracted range is `Interpreter.java:249-275`, not just the
   switch at 254-275: line 253 (`var filter = namedArguments(filterNode, env, budget, location,
   "filter");`) must move too, since `applyFilter` takes the raw `filterNode` `Expression` and
   computes `NamedArguments` itself — the caller no longer has a pre-computed `NamedArguments` to
   pass in — and the slice-2 eager-argument-evaluation comment at 249-252 documents that same
   `namedArguments` call, so it moves with it rather than being duplicated or orphaned at the old
   call site. `filter(...)` becomes a thin wrapper: compute `operand` and call
   `applyFilter(operand, expression.filter(), env, budget, expression.location())`. This is a pure
   extraction — behavior for `{{ value | filter }}` must not change.

   Add `evaluateFilterStatement(Statement.FilterStatement node, Environment env, RenderBudget
   budget)`:

   ~~~java
   var rendered = evaluateBlock(node.body(), env, budget);
   if (!(rendered instanceof ExecResult.Normal normal)) return rendered;
   var filtered = applyFilter(new Value.StringValue(normal.output()), node.filter(), env, budget, node.location());
   var text = filtered instanceof Value.NullValue || filtered instanceof Value.UndefinedValue
       ? ""
       : renderText(filtered, node.location());
   budget.chargeOutput(text.length(), node.location());
   return new ExecResult.Normal(text);
   ~~~

   Two details here matter and are easy to drop by copying `renderText` calls out of context
   elsewhere in the file:

   - **The Null/Undefined guard is required, not optional.** `renderText` throws `AssertionError`
     for `Value.NullValue`/`Value.UndefinedValue` (`Interpreter.java:920-921`) because the
     `Expression`-statement arm (`evaluateStatement`, around line 106) already filters those two
     cases out before calling it. None of today's nine filters can return null/undefined, so this
     is unreachable right now — but Step 5's call-block path needs the identical guard for a
     reachable reason (a non-macro callee can return anything), so both new statement types use the
     same three-line conversion for a consistent reason, not "guarded where it happens to matter
     today."
   - **Charge the filtered text's length through `budget.chargeOutput(...)`, mirroring the
     `Expression`-statement arm exactly** — this is a deliberate decision, not a gap: the raw,
     pre-filter body text was already charged once, character by character, while `evaluateBlock`
     evaluated `node.body()`'s own expression statements, but that text is discarded and replaced
     by `filtered` here, so leaving the *emitted* text uncharged would let a filter that grows its
     input (most plausibly `tojson`, via nested structural characters) produce output the
     `maxOutputLength` budget never sees. This double-charges overlapping content in the ordinary
     case (the same way, for example, `{% set x %}...{% endset %}` followed by a later `{{ x }}`
     already charges that text twice), which is consistent with this budget's existing
     charge-at-every-materialization-point design, not a new policy invented for this slice.
     `evaluateCallStatement` in Step 5 needs the same `chargeOutput` call for the same reason —
     its returned `Value` (from a non-macro callee) is never charged anywhere else either.

   Propagating a non-`Normal` `rendered` result outward (rather than erroring) is deliberate and
   *not* part of this slice's Known Gaps: `FilterStatement` never crosses the
   `Value.CallableValue.Callable` boundary, so `evaluateBlock`'s `ExecResult.Break`/`Continue`
   sentinel composes correctly here exactly as it already does in `evaluateSet`'s block-capture —
   confirmed against the oracle in Step 1's characterization work (a bare `break` inside a `{%
   filter %}` block bleeds into the caller's enclosing loop upstream, and this implementation
   reproduces that for free).

7. Verify and update the ledger.

   `Macro`, `FilterStatement`, and `CallStatement` stay at `M3` in `upstream/ast-allowlist.json` —
   the tag schedules work by milestone and is not a binary implemented/unimplemented flag (already
   established by slice 1 and slice 2 leaving their own M3 entries unchanged). After this slice,
   every M3-tagged AST node has interpreter support; do not treat WP5 item 5 ("remove every
   remaining AST allowlist exemption") as in scope here unless a stale-exemption check in
   `upstreamVerify` fails.

   ~~~bash
   ./gradlew test --offline --tests 'se.alipsa.hfjinja.internal.runtime.InterpreterTest'
   ./gradlew nodeCorpusVerify --offline
   ./gradlew corpusCoverage upstreamVerify check --offline
   git diff --check
   ~~~

## Acceptance criteria

- Macros bind positional, keyword, and call-time-evaluated default arguments per Step 1's table,
  ignore extra positionals and unknown keywords silently (matching upstream), and reject a missing
  required positional with a located `ARITY` error.
- A macro body's scope chains to the *call-site* environment, not the definition-site environment —
  demonstrated by both the default-argument late-binding case and the call-block-sees-macro-scope
  case from Step 1's table.
- `{% call %}...{% endcall %}` blocks bind `caller()` and any declared caller parameters correctly,
  and a non-identifier caller parameter is rejected with a located `SYNTAX` error.
- `{% filter %}...{% endfilter %}` blocks render their body and apply the named filter using the
  existing, unmodified filter dispatch, including correct break/continue propagation through the
  block with no special-casing.
- `{% call %}` pushes its keyword-arguments bag onto the callee's argument list unconditionally
  (matching upstream's asymmetry with ordinary calls), demonstrated by a non-macro callee
  (`range`/`namespace`) producing the same result as the oracle; `hasKeywordArguments` is still
  derived from whether the caller actually supplied keywords, so a host function invoked via
  `{% call %}` with no keyword arguments doesn't fail on that check alone. `HostFunctions.invoke`
  strips a trailing empty bag before argument conversion (fixed during review — see the resolved
  Known Gap below), so a host function sees exactly the arguments the template wrote; that exact
  argument list, not mere success, is the property Step 5's regression pins. Both `{% call %}` and
  `{% filter %}` charge their emitted text through `RenderBudget`, mirroring the `Expression`-
  statement arm — including, for both, a second charge for the block's rendered result on top of
  the body's own charge, consistent with `evaluateSet`'s pre-existing block-capture behavior.
- The two remaining documented divergences (break/continue not bleeding through a macro/call-block
  boundary; a new `RESOURCE_LIMIT` macro-depth guard where upstream silently corrupts on deep
  recursion) are each pinned by a named `InterpreterTest` case, and none are present in the corpus.
  The host-function trailing-empty-map behavior described above was a genuine bug, not a
  divergence to document, and is fixed and pinned rather than accepted.
- `Macro`, `FilterStatement`, and `CallStatement` no longer reach `unsupported(...)`; all offline
  verification passes.
