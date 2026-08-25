# WP5 Slice 7 — AST Allowlist Cleanup

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close WP5 item 5 by removing the interpreter's only remaining "not yet supported"
placeholder arms, replacing them with an unreachable-branch assertion that matches what the parser
actually guarantees.

**Architecture:** No new behavior. `Interpreter.evaluateExpression`'s exhaustive switch over the
sealed `Expression` interface has three arms — `SliceExpression`, `KeywordArgumentExpression`,
`SpreadExpression` — that throw a template-facing "not yet supported" error even though all three
node types are fully implemented everywhere the parser can actually place them. Replace those three
arms with the same `AssertionError("unreachable: " + …)` idiom this file already uses for
`renderText`'s equally-unreachable `NullValue`/`UndefinedValue` arms, delete the now-dead helper
that built the old exception, and record closure in `req/implementation-plan.md`.

**Tech Stack:** Java 21, JUnit 5. No new dependency, no upstream re-sync, no corpus change.

**Spec:** `req/implementation-plan.md` (WP5 item 5: "Remove every remaining AST allowlist
exemption").

## Global Constraints

Copied verbatim from `req/implementation-plan.md`'s "Working rules"; note where each does not apply
to this slice rather than silently dropping it:

- Pin `@huggingface/jinja` 0.5.9 only through a reviewed `sync-upstream` change. The Java build is
  offline; Node is used only by the explicit oracle/update workflow. — *Not touched: this slice
  makes no upstream-facing change and does not run the Node oracle.*
- Keep `upstream/mapping.yml` current in the same change as every ported upstream behavior. — *No
  new port: `Interpreter.java` is already listed under `runtime.ts` as `implemented` with its
  current `java`/`tests` lists; this slice adds no new file, so `mapping.yml` does not change.*
- Add a differential mismatch as a focused Java regression before changing expected results. — *No
  differential/oracle mismatch is involved; this slice touches code the parser can never reach.*
- Do not add a production dependency to solve parsing, JSON, date formatting, or execution.
- Keep implementation packages unexported. Only `se.alipsa.hfjinja` is public.

---

## Objective and boundary

WP5 item 5 reads: "Remove every remaining AST allowlist exemption." `upstream/ast-allowlist.json`
tags six AST node types `M3`: `Macro`, `FilterStatement`, `CallStatement` (statements) and
`SliceExpression`, `KeywordArgumentExpression`, `SpreadExpression` (expressions). All six are
fully implemented today — slices 1–3 of this work package ported slicing, spread/keyword call
arguments, macros, call blocks, and filter blocks. `Interpreter.evaluateStatement`'s switch
(`Interpreter.java:114-123`) already routes `Macro`/`FilterStatement`/`CallStatement` to real
implementations (`evaluateMacro`/`evaluateFilterStatement`/`evaluateCallStatement`) with no
placeholder arm left. `Interpreter.evaluateExpression`'s switch
(`Interpreter.java:136-163`) is the only place that still has one — three, in fact:

```java
      case Expression.SliceExpression x ->
          throw unsupportedExpression("SliceExpression", x.location());
      case Expression.KeywordArgumentExpression x ->
          throw unsupportedExpression("KeywordArgumentExpression", x.location());
      case Expression.SpreadExpression x ->
          throw unsupportedExpression("SpreadExpression", x.location());
```

These are the *only* remaining `unsupportedExpression` call sites in the file (confirmed by
`grep -n unsupportedExpression src/main/java/se/alipsa/hfjinja/internal/runtime/Interpreter.java`
— four hits: the three throws above and the helper's own definition at line 172). No test in the
repository asserts on the "is not yet supported" message for any of these three node types
(confirmed by `grep -rn "SliceExpression\|KeywordArgumentExpression\|SpreadExpression"
src/test/java/` — the only hits are `AstSnapshot.java`'s AST-printer cases and one unrelated
assertion described below), so removing them changes no observable test result.

**Why these three arms are provably unreachable, not merely untested.** The parser constructs each
of these node types in exactly one place, and the interpreter intercepts the value at that same
structural position before it ever reaches generic `evaluateExpression` dispatch:

- `Parser.parseMemberExpressionArgumentsList` (`Parser.java:401-432`) is the only site that builds
  a `SliceExpression`; it always returns it as the `property` of a computed `MemberExpression`
  (`Parser.java:428`). `Interpreter.member` (`Interpreter.java:779-782`) special-cases
  `n.computed() && n.property() instanceof Expression.SliceExpression slice` and dispatches to
  `slice(...)` before any other code path can hand that `property` to `evaluateExpression`.
- `Parser.parseArgumentsList` (`Parser.java:374-399`) is the only site that builds a
  `KeywordArgumentExpression` or a `SpreadExpression`; it backs `Parser.parseArgs()`, which in turn
  feeds three call sites: a `CallExpression`'s `args()`, a macro's own parameter list (feeding
  `Statement.Macro`'s `args()`, via the same `parseArgs()` call at `Parser.java:166`), and a
  `{% call %}` block's caller parameter list (`Parser.parseCallStatement`, `Parser.java:196`). On
  the call-expression side, `evaluateArguments` (`Interpreter.java:642-677`) switches on
  `KeywordArgumentExpression` and `SpreadExpression` itself (lines 653, 656) and only falls through
  to `evaluateExpression` for anything else. On the macro-parameter side, `evaluateMacro`'s
  parameter-binding loop (`Interpreter.java:957-980`) does the same for `KeywordArgumentExpression`
  (line 965); a bare `Identifier` is the only other case it accepts. On the caller-parameter side,
  `evaluateCallStatement`'s caller-parameter loop (`Interpreter.java:1007-1019`) rejects anything
  but an `Identifier` with `"Caller parameter must be an identifier, got " +
  param.getClass().getSimpleName()` before ever reaching `evaluateExpression`.

No valid AST this parser can produce ever hands one of these three node types to
`evaluateExpression` directly. This mirrors an existing, already-accepted pattern in the same
file: `renderText` (`Interpreter.java:1250-1264`) has two arms — `NullValue`, `UndefinedValue` —
that are equally unreachable (both are filtered out by every caller before `renderText` runs) and
already throw `AssertionError("unreachable: " + v)` rather than a template-facing exception, with
no dedicated test. This slice makes the three expression arms consistent with that existing
convention instead of inventing a new one.

**Explicitly out of scope — three things that look related but are not part of this exemption.**

1. `evaluateMacro`'s parameter-binding loop has its own defensive arm
   (`Interpreter.java:975-980`): `else { throw new TemplateRenderException("Unknown argument
   type: " + nodeArg.getClass().getSimpleName(), ErrorCategory.SYNTAX, nodeArg.location()); }`.
   Unlike the three arms above, this one *is* reachable: a macro's parameter list reuses the
   general call-argument parser, so `{% macro foo(*args) %}` parses successfully as a
   `SpreadExpression` parameter — and only fails, with this exact message, when the macro is
   later called. This is not a leftover placeholder; it is a faithful, already-tested port of
   upstream's identical behavior at `upstream/vendor/src/runtime.ts:1755-1757` (`else { throw new
   Error(\`Unknown argument type: ${nodeArg.type}\`); }`), and `InterpreterTest` already pins the
   exact message `"Unknown argument type: SpreadExpression"` (`InterpreterTest.java:1070`). Do not
   change this arm.
2. `evaluateCallStatement`'s caller-parameter loop has the equivalent defensive arm for the third
   `parseArgs()` consumer (`Interpreter.java:1007-1019`): `if (!(param instanceof
   Expression.Identifier id)) throw new TemplateRenderException("Caller parameter must be an
   identifier, got " + param.getClass().getSimpleName(), ErrorCategory.SYNTAX,
   param.location());`. Reachable the same way: `{% call(*x) f() %}` or `{% call(k=1) f() %}`
   parses successfully as a `SpreadExpression`/`KeywordArgumentExpression` caller parameter and
   only fails, with this exact message, when the call block runs. `InterpreterTest` already pins
   this arm for the sibling `MemberExpression` shape
   (`callBlockNonIdentifierCallerParameterIsSyntaxError`, `InterpreterTest.java:1118-1130`); it
   does not yet cover the `SpreadExpression`/`KeywordArgumentExpression` shapes, but that gap is
   pre-existing and out of scope for this slice. Do not change this arm either.
3. `upstream/ast-allowlist.json`'s per-node milestone tags (`M1`/`M2`/`M3`) are not part of this
   cleanup. `build.gradle`'s `upstreamVerify` task (`build.gradle:316-320`) checks only that every
   AST node type discovered in the sealed `Statement`/`Expression` inventory has *some* key in the
   allowlist file (a superset check: `discoveredNodes.findAll { !declaredNodes.containsKey(it) }`
   must be empty). Exact key-set equality is enforced separately by
   `AstInventoryTest.javaNodesCoverEveryUpstreamAstNode`
   (`src/test/java/se/alipsa/hfjinja/internal/ast/AstInventoryTest.java:18-27`). Neither check reads
   the milestone *value* for anything. There is no build-enforced notion of an AST node's milestone
   value becoming "cleared" once it ships; the value is historical documentation of which milestone
   introduced the node, not a pending-work flag a check can fail on. This slice does not edit
   `ast-allowlist.json`.

## Implementation steps

1. **Replace the three placeholder arms and delete the now-dead helper.**

   In `src/main/java/se/alipsa/hfjinja/internal/runtime/Interpreter.java`, replace:

   ```java
      case Expression.SliceExpression x ->
          throw unsupportedExpression("SliceExpression", x.location());
      case Expression.KeywordArgumentExpression x ->
          throw unsupportedExpression("KeywordArgumentExpression", x.location());
      case Expression.SpreadExpression x ->
          throw unsupportedExpression("SpreadExpression", x.location());
   ```

   with:

   ```java
      // SliceExpression/KeywordArgumentExpression/SpreadExpression are the only three sealed
      // Expression cases with no real evaluation logic here, and that is not a gap: the parser
      // constructs each of them in exactly one place, and every structural position they can
      // occupy is intercepted before it can ever reach this generic dispatch.
      // Parser.parseMemberExpressionArgumentsList is the only site that builds a SliceExpression,
      // and it always becomes the `property` of a computed MemberExpression; member()
      // special-cases `n.computed() && n.property() instanceof SliceExpression` before ever
      // calling evaluateExpression on that property. Parser.parseArgumentsList is the only site
      // that builds a KeywordArgumentExpression or SpreadExpression, feeding a CallExpression's
      // argument list, a Macro's own parameter list, or a {% call %} block's caller parameter
      // list; evaluateArguments() (ordinary calls), evaluateMacro's parameter-binding loop (macro
      // parameter declarations), and evaluateCallStatement's caller-parameter loop (caller
      // parameter declarations) all switch on the node type themselves and only fall through to
      // evaluateExpression for anything else. No valid AST from this parser can hand one of these
      // three node types to this switch directly, so — matching renderText's identical
      // NullValue/UndefinedValue arms below — these throw AssertionError rather than a
      // template-facing exception: reaching here is an interpreter bug, not a template author's
      // mistake.
      case Expression.SliceExpression x ->
          throw new AssertionError(
              "unreachable: " + x.getClass().getSimpleName() + " at " + x.location());
      case Expression.KeywordArgumentExpression x ->
          throw new AssertionError(
              "unreachable: " + x.getClass().getSimpleName() + " at " + x.location());
      case Expression.SpreadExpression x ->
          throw new AssertionError(
              "unreachable: " + x.getClass().getSimpleName() + " at " + x.location());
   ```

   Then delete the now-unused helper (it has no other caller after this change):

   ```java
     private static TemplateRenderException unsupportedExpression(String n, SourceLocation l) {
       return new TemplateRenderException(
           n + " is not yet supported", ErrorCategory.UNDEFINED_OR_ACCESS, l);
     }
   ```

   - [ ] Make the replacement and the deletion described above.
   - [ ] Run `./gradlew spotlessApply` (required after any Java edit in this repo, before `check`).

2. **Pin the new behavior with a direct white-box test.**

   This test constructs each of the three node types outside their only valid structural position
   and calls `Interpreter.evaluateExpression` on them directly — the one way to exercise a branch
   the parser itself can never reach. Add this test method to
   `src/test/java/se/alipsa/hfjinja/internal/runtime/InterpreterTest.java` (same package as
   `Interpreter`, `Environment`, and `RenderBudget`, so their package-private constructors and
   `evaluateExpression` are all directly callable):

   ```java
     @Test
     void evaluateExpressionAssertsUnreachableForParserOnlyExpressionShapes() {
       // Parser.parseMemberExpressionArgumentsList only ever returns a SliceExpression as a
       // computed MemberExpression's property, and Parser.parseArgumentsList only ever returns a
       // KeywordArgumentExpression/SpreadExpression inside a CallExpression's argument list, a
       // Macro's parameter list, or a {% call %} block's caller parameter list -- every one of
       // those call sites intercepts the value before evaluateExpression's generic dispatch ever
       // sees it (see the comment above Interpreter.evaluateExpression's three matching cases).
       // Handing one directly to evaluateExpression, as this test does, is the only way to reach
       // those arms at all, and pins that they now assert rather than throwing a template-facing
       // "not yet supported" error.
       var location = new SourceLocation(0, 1, 1);
       var env = new Environment(null);
       var budget = new RenderBudget(RenderOptions.builder().build());
       var slice = new Expression.SliceExpression(null, null, null, location);
       var keywordArgument =
           new Expression.KeywordArgumentExpression(
               new Expression.Identifier("k", location),
               new Expression.IntegerLiteral(1, location),
               location);
       var spread =
           new Expression.SpreadExpression(new Expression.Identifier("x", location), location);
       assertAll(
           () ->
               assertTrue(
                   assertThrows(
                           AssertionError.class,
                           () -> Interpreter.evaluateExpression(slice, env, budget))
                       .getMessage()
                       .startsWith("unreachable: ")),
           () ->
               assertTrue(
                   assertThrows(
                           AssertionError.class,
                           () -> Interpreter.evaluateExpression(keywordArgument, env, budget))
                       .getMessage()
                       .startsWith("unreachable: ")),
           () ->
               assertTrue(
                   assertThrows(
                           AssertionError.class,
                           () -> Interpreter.evaluateExpression(spread, env, budget))
                       .getMessage()
                       .startsWith("unreachable: ")));
     }
   ```

   Add `import se.alipsa.hfjinja.internal.ast.Expression;` to `InterpreterTest.java`'s import
   block (it is not already imported — confirmed by `grep -n "^import"
   src/test/java/se/alipsa/hfjinja/internal/runtime/InterpreterTest.java`). `assertAll`,
   `assertThrows`, `assertTrue`, `RenderOptions`, and `SourceLocation` are already imported.

   - [ ] Add the test method and the new import exactly as above.
   - [ ] Run `./gradlew test --tests
     se.alipsa.hfjinja.internal.runtime.InterpreterTest` and confirm it passes.
   - [ ] Run `./gradlew spotlessApply` again (the new test method may reformat).

3. **Record closure in the spec and commit.**

   In `req/implementation-plan.md`, WP5 item 5 currently reads only:

   ```markdown
   5. Remove every remaining AST allowlist exemption.
   ```

   Append a status sentence, matching the precedent already set for WP5 item 3 in the same list
   (which documents what slices 4–5 closed and what remains open). Replace that line with:

   ```markdown
   5. Remove every remaining AST allowlist exemption. Closed: `Interpreter.evaluateExpression`'s
      only remaining placeholder arms — `SliceExpression`, `KeywordArgumentExpression`, and
      `SpreadExpression` — now assert unreachability instead of throwing a template-facing "not
      yet supported" error, matching the parser guarantee that these three node types never reach
      that generic dispatch path. `evaluateStatement`'s M3 nodes (`Macro`, `FilterStatement`,
      `CallStatement`) already had no such placeholder. `upstream/ast-allowlist.json`'s per-node
      milestone tags are unaffected: `upstreamVerify` only checks that every discovered AST node
      has a key in the allowlist, and `AstInventoryTest` checks exact key-set equality — neither
      reads the milestone value, so there was nothing there to clear.
   ```

   - [ ] Make that edit to `req/implementation-plan.md`.
   - [ ] Run `./gradlew check` (full offline verification: `nodeCorpusTest`, `nodeOracleVersion`,
     `nodeCorpusVerify`, `corpusCoverage`, `upstreamVerify`, `astSnapshotVerify`,
     `codenarcBuildScripts`, and the full JUnit suite) and confirm `BUILD SUCCESSFUL`.
   - [ ] Confirm no stale references remain:
     `grep -rn "unsupportedExpression\|is not yet supported"
     src/main/java/se/alipsa/hfjinja/internal/runtime/Interpreter.java` must return no output.
   - [ ] `git status --short` should show exactly two modified files (`Interpreter.java`,
     `InterpreterTest.java`), one modified spec file (`req/implementation-plan.md`), and this plan
     file itself (`docs/superpowers/plans/2026-08-25-wp5-slice7-ast-allowlist-cleanup.md`, currently
     untracked, following the precedent set by every prior WP4/WP5 slice plan being committed
     alongside its implementation) — no other file changes.
   - [ ] Commit:

     ```bash
     git add src/main/java/se/alipsa/hfjinja/internal/runtime/Interpreter.java \
         src/test/java/se/alipsa/hfjinja/internal/runtime/InterpreterTest.java \
         req/implementation-plan.md \
         docs/superpowers/plans/2026-08-25-wp5-slice7-ast-allowlist-cleanup.md
     git commit -m "Close WP5 item 5: assert unreachability for the last AST placeholder arms"
     ```

## Acceptance criteria

- [ ] `Interpreter.java` contains no `unsupportedExpression` method and no "is not yet supported"
  string.
- [ ] `Interpreter.evaluateExpression`'s `SliceExpression`, `KeywordArgumentExpression`, and
  `SpreadExpression` arms each throw `AssertionError` with a message starting `"unreachable: "`,
  not `TemplateRenderException`.
- [ ] `InterpreterTest.evaluateExpressionAssertsUnreachableForParserOnlyExpressionShapes` passes.
- [ ] `evaluateMacro`'s `"Unknown argument type: " + …` arm for a spread/positional-only macro
  parameter is unchanged, still throws `TemplateRenderException` with `ErrorCategory.SYNTAX`, and
  `InterpreterTest`'s existing assertion of `"Unknown argument type: SpreadExpression"` still
  passes unmodified.
- [ ] `evaluateCallStatement`'s `"Caller parameter must be an identifier, got " + …` arm for a
  spread/keyword-argument caller parameter is unchanged, still throws `TemplateRenderException`
  with `ErrorCategory.SYNTAX`, and
  `InterpreterTest.callBlockNonIdentifierCallerParameterIsSyntaxError` still passes unmodified.
- [ ] `upstream/ast-allowlist.json` and `upstream/mapping.yml` are unmodified by this slice.
- [ ] `req/implementation-plan.md`'s WP5 item 5 line reflects closure with the exact wording above.
- [ ] `./gradlew check` passes (`BUILD SUCCESSFUL`), including `upstreamVerify` and
  `astSnapshotVerify`, proving this slice introduced no upstream-provenance or AST-inventory
  regression.

## Deliberately deferred

- WP5 item 3's outstanding follow-up: a corpus schema change to carry model provenance fields so
  the Mistral/Qwen/Step3 resource-backed goldens (slices 4–5) can become `v1.jsonl` records. Not
  touched by this slice.
- WP5 item 4: fuzz/property suites with harness timeouts. Not touched by this slice.
- `evaluateMacro`'s rejection of a spread/positional-only macro parameter declaration (see
  "Explicitly out of scope" above) — correct, reachable, already tested, and a faithful port of
  upstream's identical behavior. Not a placeholder; not touched by this slice.
- `evaluateCallStatement`'s rejection of a spread/keyword-argument caller parameter declaration
  (see "Explicitly out of scope" above) — the equivalent defensive arm for `{% call %}` blocks,
  correct and reachable, though its existing test coverage only pins the sibling
  `MemberExpression` shape. Not a placeholder; not touched by this slice.
