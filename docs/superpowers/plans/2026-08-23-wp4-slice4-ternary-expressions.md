# WP4 Slice 4 — Full Ternary Expressions

## Goal

Complete the remaining M2 conditional-expression path by evaluating the parser's existing
`Expression.Ternary` node with the same lazy branch selection as pinned
`@huggingface/jinja` 0.5.9. This makes `value if condition else fallback` usable while leaving
slices, macros, call/filter blocks, and keyword/spread support to their assigned later slices.

## Current state and scope

The lexer, AST, and parser already support both conditional forms:

- `Expression.SelectExpression` for `value if condition`, implemented in the interpreter; and
- `Expression.Ternary` for `value if condition else fallback`, currently rejected by the
  interpreter's generic unsupported-expression branch.

The pinned runtime's `evaluateTernaryExpression` evaluates the condition once, applies runtime
truthiness, then evaluates exactly one selected branch. The Java implementation must reuse
`Interpreter.truthy(Value)` and `evaluateExpression`; it must not use Java boolean coercion or
eagerly evaluate both branches.

Out of scope:

- parser/AST shape changes, including alternate ternary syntax;
- `SliceExpression`, `KeywordArgumentExpression`, and `SpreadExpression` runtime evaluation;
- macro, call-block, and filter-block support;
- adding filters or tests.

## Work plan

1. Characterize and pin the upstream behavior.

   Inspect `upstream/vendor/src/runtime.ts`'s `evaluateTernaryExpression` and run the Node
   oracle for this compact matrix before changing Java code:

   | Case | Required result |
   | --- | --- |
   | true condition | yields only the true expression |
   | false condition | yields only the false expression |
   | falsy/truthy runtime values | uses the existing Java runtime truthiness rules, including null, undefined, empty string, zero, empty array/object, and `NaN` |
   | nested ternary | preserves the parser's associativity and selected output |
   | non-selected invalid/access expression | does not evaluate or report its error |
   | selected invalid/access expression | preserves the normal located render error and category |

   Append representative successful vectors to `src/test/resources/corpus/v1.jsonl`, using the
   established record shape:

   - `id`, for example `self.ternary-false-branch`;
   - `source` set to `self-authored; verified against @huggingface/jinja 0.5.9`;
   - `template`, `context`, and `expected.text` for a successful result; or
     `expected.errorCategory` for a Node-comparable failure.

   Generate the expected values through the pinned Node oracle rather than hand-writing an
   unverified golden. Represent comparable failing cases with their `ErrorCategory`; do not
   normalize an error that Node does not classify.

2. Add focused Java regressions first.

   Add an `InterpreterTest` method covering true and false selection, nested conditionals, and
   lazy evaluation of the non-selected branch. Exercise every existing `truthy` class in ternary
   context, rather than using a single representative falsy value:

   | Condition | Runtime `Value` class | Expected branch |
   | --- | --- | --- |
   | `missing`, `none`, `''`, `0`, `0.0`, `[]`, `{}`, and `(0.0 / 0.0)` | undefined, null, string, integer, float, array, object, and NaN float | false branch |
   | `true`, `'x'`, `1`, `1.0`, `[1]`, `{'x': 1}`, `(1, 2)`, `namespace(a=1)`, and `range` | boolean, string, integer, non-NaN float, array, object, tuple, keyword arguments, and callable | true branch |

   Keep one assertion for an error on the selected branch so the new dispatch does not swallow
   location/category propagation. Extend `v1.jsonl` with a compact, Node-verified vector for both
   outcomes, truthiness, nesting, and non-selected-branch laziness rather than relying only on
   Java assertions.

3. Implement direct interpreter dispatch.

   Replace the `Expression.Ternary` unsupported branch with a private helper that:

   1. evaluates `condition()` once;
   2. calls the existing `truthy` helper; and
   3. evaluates and returns only `trueExpr()` or `falseExpr()` in the same environment and
      render budget.

   Preserve the AST node's `SourceLocation` by delegating branch failures to the existing
   expression evaluators. No new `Value` conversion, formatter, or error category is needed.

4. Keep the provenance ledger honest.

   Confirm `Ternary` remains an M2 node in `upstream/ast-allowlist.json` and that
   `upstream/mapping.yml` continues to map `runtime.ts` to `Interpreter.java` and its tests. If
   a mapping/allowlist assertion exposes a stale status after the implementation, update it in
   the same change; do not remove M3 exemptions for slices, spreads, keyword arguments, macros,
   call blocks, or filter blocks.

5. Verify in layers.

   During iteration run the focused `InterpreterTest` method. Before opening the work-package
   PR, run:

   ```bash
   ./gradlew test --offline --tests 'se.alipsa.hfjinja.internal.runtime.InterpreterTest'
   ./gradlew nodeCorpusVerify --offline
   ./gradlew upstreamVerify --offline
   ./gradlew corpusCoverage --offline
   ./gradlew check --offline
   git diff --check
   ```

## Acceptance criteria

- `{{ 'yes' if true else 'no' }}` and `{{ 'yes' if false else 'no' }}` match the pinned Node
  oracle.
- Only the selected ternary branch is evaluated; an invalid non-selected branch cannot affect
  output, budget consumption, or errors.
- Selected-branch failures retain their established location and `ErrorCategory`.
- Java regressions and versioned corpus vectors cover both outcomes, runtime truthiness, nesting,
  and laziness.
- The offline check, provenance verification, Node corpus verification, and corpus coverage all
  pass.
