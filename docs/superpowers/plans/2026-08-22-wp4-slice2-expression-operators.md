# WP4 Slice 2 — Expression Operators and JavaScript Number Semantics

## Goal

Replace Slice 1's `BinaryExpression` and `UnaryExpression` placeholders with the pinned
`@huggingface/jinja` 0.5.9 behavior.  This slice completes ordinary operator expressions:
short-circuiting, equality, arithmetic, comparisons, concatenation, membership, and `not`.
It also makes the split `IntegerValue`/`FloatValue` model behave as JavaScript doubles during
evaluation, including non-finite values.  Filters/tests, ternaries, slices, macro features, and
the corpus gate remain later slices.

## Scope and constraints

- Implement behavior equivalent to `upstream/vendor/src/runtime.ts`'s
  `evaluateBinaryExpression` and `evaluateUnaryExpression`; preserve the upstream branch order
  wherever it is observable. Do not attempt a line-by-line TypeScript-to-Java translation.
  Preserve its intentional quirks, including loose equality and its different
  string-concatenation (`+`) versus `~` paths.
- Keep all runtime state per render.  Do not add production dependencies or expose an internal
  package from `module-info.java`.
- Maintain the value-tag split.  Integer-syntax operands stay `IntegerValue` for `+`, `-`, `*`,
  and `%`; `/` always produces `FloatValue`.  The payload is nevertheless an IEEE-754 double, so
  precision loss, signed zero, `NaN`, and infinities must be retained and rendered by `JsFormat`.
- A value is undefined/null only where the upstream operation permits it.  Convert unsupported
  operations into `TemplateRenderException` with a location and the established comparable error
  category; never leak a Java arithmetic or collection exception.
- Deliberate compatibility deviation: equality permits null/undefined pairs, but mixed
  null/undefined-to-non-null equality (for example, `null == 0`) is rejected as
  `UNDEFINED_OR_ACCESS` rather than returning the upstream's `false`.
- `runtime.ts` remains `implemented` in `upstream/mapping.yml`; append any new Java/test files to
  its inline lists in the same change.  `utils.ts` and `index.ts` remain planned.

## Work plan

1. Establish the compatibility matrix before changing production code.

   Add focused `InterpreterTest` cases for every operator/operator-family and capture the upstream
   result with the existing pinned Node oracle when source inspection leaves a JavaScript coercion
   question open.  Cover:

   - `and`/`or` return the exact left/right operand rather than a Boolean, and do not evaluate a
     raising right side when short-circuited; explicitly assert `0 and 5 -> 0` and `3 or 5 -> 3`;
   - loose `==`/`!=`, including null/undefined and number/string/boolean combinations; explicitly
     assert the allowed null/undefined cases (`null == undefined`, `null == null`, and
     `undefined == undefined`) and that `null == 0` and `undefined == false` fail with
     `UNDEFINED_OR_ACCESS` rather than being treated as falsy;
   - integer/float result tags, division by zero, remainder signs, precision loss beyond 2^53,
     `NaN`, infinity, and negative zero; include operator-produced `-0.0`, such as
     `0.0 * -1.0`, and `1.0 / -0.0 -> -Infinity`, asserting both the `FloatValue` tag and rendered
     text;
   - `+`, `-`, `*`, `/`, `%`, `~`, array concatenation, numeric comparisons, string membership,
     array membership, object-key membership, and `in`/`not in` with an undefined right operand
     (`x in undefined -> false`; `x not in undefined -> true`);
   - the exact error category and source location for invalid undefined/null/unsupported pairs;
   - unary `not` with every `Value` variant, in particular empty versus populated tuple/object/
     array and undefined-backed strings; assert the upstream raw-JavaScript distinction that
     `not []`/`not {}` are false while `if []`/`if {}` use the value model and are false.

   Keep parser-to-render examples in `InterpreterTest` and place coercion/unit cases in a new
   `JsOperationsTest`; add the new test class to `runtime.ts.tests` in the same change. Do not turn
   this into the Slice 4 corpus suite.

2. Create a package-private `final JsOperations` core in `internal.runtime`.

   Move the existing `Interpreter` helpers used by `range` (`jsAdd`, `jsNumber`, `jsText`, and
   ECMAScript whitespace handling) into it, and add the operator-facing operations: `add`,
   `toNumber`, `toText`, `looseEquals`, numeric arithmetic/comparison, concatenation, and
   membership. Keep it stateless and package-private; `Interpreter` remains responsible only for
   expression evaluation order and location-bearing exceptions. ECMAScript whitespace normalization
   belongs beside `toNumber`, not in `JsFormat`, because it is input coercion rather than shared
   output formatting. `range` and binary `+` must call the same addition/conversion operation so
   they cannot drift. Reuse `JsFormat` for output formatting; do not duplicate number-to-text rules.
   Place `JsOperationsTest` in the same `internal.runtime` package so it can directly exercise the
   package-private operations.

3. Implement `BinaryExpression` in `Interpreter.evaluateExpression`.

   Replace only its placeholder with a private evaluator that:

   - evaluates the left operand first, performs `and`/`or` short-circuiting, then evaluates the
     right operand exactly once for all other operators;
   - handles upstream loose equality before undefined/null rejection;
   - implements numeric, array, string, and object branches in the upstream order, including
     array concatenation and the special undefined membership result;
   - returns a boolean, operand, string, array, integer, or float value as the upstream branch
     dictates, rather than coercing all results to one Java type;
   - reports null/undefined operands through a new private `operatorNullUndefined(...)` factory returning
     `TemplateRenderException(..., ErrorCategory.UNDEFINED_OR_ACCESS, location)` for both null and
     undefined, and other
     unsupported type pairs through `operatorUnsupportedTypes(...)` returning
     `TemplateRenderException(..., ErrorCategory.TYPE, location)`; the Slice 3+ placeholders
     continue to use the existing `unsupportedExpression(...)` factory and its
     `UNDEFINED_OR_ACCESS` category.

4. Implement `UnaryExpression`.

   Replace its placeholder with the upstream `not` behavior: test raw JavaScript-value truthiness,
   not `Interpreter.truthy`. Empty arrays and objects are truthy under raw JavaScript truthiness,
   so `not []` and `not {}` evaluate to false, while `if`/`elif` and loop filtering continue to
   call the value model's `__bool__` equivalent and treat those containers as false. Capture each
   side of this deliberate split in an oracle-backed test; do not normalize it.

5. Consolidate and protect formatting/coercion boundaries.

   Make `JsFormat` the single formatter used by runtime rendering and parser snapshot tests:
   replace `AstSnapshot.number()` and `AstSnapshot.q()` bodies with delegates to `JsFormat` as
   promised by Slice 1. Add tests for exponent thresholds, `-0`, `NaN`, infinities, quote escaping,
   and values created by operator evaluation. Run the existing AST snapshot suite and verify every
   snapshot remains byte-for-byte identical. If delegation exposes a formatter discrepancy, fix
   `JsFormat`; change a snapshot only with an explicit compatibility explanation. Do not change its
   expected shape incidentally.

6. Update provenance and tests together.

   Add `JsOperationsTest` to the inline `runtime.ts.tests` mapping entry, retain existing Slice 1
   comments, and update checked-in corpus records only for
   operators covered by this slice. A template requiring filters, tests, ternaries, slices, or
   member-method fallback remains unchanged or skipped. A mismatch discovered through the oracle
   becomes a minimal Java regression before its implementation is changed.

7. Verify the work package.

   Run `./gradlew check --offline`, `./gradlew upstreamVerify --offline`,
   `javadoc -private -Xdoclint:all -Xmaxwarns 100000`, and `git diff --check`. Run the pinned Node
   oracle reproducibly with:

   ```bash
   node tools/corpus/run-node-oracle.mjs --corpus src/test/resources/corpus/v1.jsonl \
     --patterns tools/corpus/error-patterns-0.5.9.json --lock upstream/upstream-lock.json
   ```

   The run must produce no new mismatches for templates exercising only Slice 2 operators. Fix each
   mismatch in Java or record it as a skipped/categorized corpus entry before commit. Node remains
   an update-time oracle, never a production or JUnit dependency.

8. Commit as one cohesive change.

   Suggested message: `WP4-S2: implement expression operators and JS arithmetic`.

## Acceptance criteria

- `BinaryExpression` and `UnaryExpression` no longer take the Slice 1 unsupported path.
- The characterized operator matrix agrees with the pinned upstream engine for successful output
  and comparable error behavior.
- `range` and binary `+` share `JsOperations` numeric conversion/addition semantics.
- Parser snapshots and runtime output share `JsFormat`'s number and JSON-string quoting core.
- `-0.0`, `NaN`, `Infinity`, and `-Infinity` produced by operators render identically to the
  pinned upstream oracle.
- Slice 3 placeholders (`FilterExpression`, `TestExpression`, `Ternary`, general
  `SelectExpression` testing, and member-method fallback) are unchanged and still fail in the
  documented way.
