# WP7 Slice 2 — Error-Contract and Filter-Diagnostic Parity

## Goal

Close WP7 item 4 from `req/implementation-plan.md`: specify the error contract for the pinned
`@huggingface/jinja` 0.5.9 runtime and eliminate the currently known call-form filter diagnostic
gaps. This slice makes the Node oracle able to classify the pinned runtime's `Unknown …Value
filter: …` errors, then uses corpus-first regressions to align Java's behavior for `safe(...)`,
`items(...)`, and no-argument sequence filters. `v1.jsonl` is presently consumed only by the Node
oracle, so every new Node corpus record in this slice must have a named, equivalent
`InterpreterTest` regression; this slice does not falsely claim a Java corpus runner exists.

This is parity work, not a public-error-message compatibility promise. `ErrorCategory` remains the
stable hfjinja API; exact upstream messages are compared only for error families whose wording is
stable in the reviewed 0.5.9 vendor bundle and is useful to pin the intended dispatch path.

## Evidence and scope

WP7 Slice 1 is complete: `Template.format()` is public, mapped, and checked against the pinned
Node oracle. The next numbered WP7 item is error-contract closure.

The current Java `Interpreter.applyFilter` eagerly evaluates every filter call's arguments through
`namedArguments` before selecting a filter, then applies Java arity validation. The pinned
`runtime.ts` instead dispatches bare identifier filters separately from call-form filters. Its
call-form branches evaluate arguments only inside the applicable branch. Consequently, for the
pinned runtime:

- bare `safe` returns its operand, while a call-form `safe(...)` on a string reaches the
  type-specific fallback and reports `Unknown StringValue filter: safe`; call-form numeric,
  boolean, null, and undefined receivers instead report `Cannot apply filter "safe" to type:
  <concrete type>`;
- bare `items` on an object returns key/value pairs, while `items(...)` resolves the object's
  built-in `items` function, evaluates its supplied arguments, and ignores them;
- call-form array filters outside the explicitly implemented call-form set (for example `first()`
  and `last()`) report `Unknown ArrayValue filter: <name>` without evaluating arguments; a tuple
  is an `ArrayValue` in upstream and must produce the same message.

The Node classifier deliberately lacks this type-specific unknown-filter family, so proposed
corpus entries for these cases would currently fail as unclassified even when the requested
category is `TYPE`. Java tests presently pin `Unknown filter: safe` and an `ARITY` result for
`items(1)`; both conflict with the oracle behavior above. The existing macro-is-not-a-filter test
also deliberately pins `Unknown filter: f`; retain its assertion that macros are not filters while
updating its message to the oracle's receiver-specific unknown-filter diagnostic.

Because this refactor controls dispatch, it must cover the whole reviewed no-argument call-form
matrix, not just its most visible examples. The following current Java successes must become the
pinned upstream errors: string `length`, `lower`, `upper`, `trim`, `string`, `title`, and
`capitalize`; array `length`, `list`, `first`, `last`, `reverse`, `unique`, and `string`; object
`length`; numeric `string` and `abs`; and boolean `string` and `bool`. In addition,
`selectattr()` and `rejectattr()` need three separately pinned array cases: an empty array succeeds
with `[]`; a non-object array reports `` `selectattr`/`rejectattr` can only be applied to array of
objects`` as `TYPE`; and an array of objects crashes inside the pinned Node implementation because
the omitted attribute is dereferenced. The crash remains explicitly deferred, following the
existing formatter-crash precedent, and Java must not reproduce it. String, object, numeric, and
boolean receivers must return their uniform upstream `TYPE` result instead of Java's premature
`ARITY`. The slice also covers the typed-unknown cases necessary to oracle-back every classifier
kind. It deliberately does not change the category-only typed-message differences for bare
`[1] | items` or `'abc' | abs`, nor any other WP7 semantic gap. Those are known-name
wrong-receiver cases and remain category-only; they are distinct from an unknown filter name,
which this slice makes type-specific for every applicable bare receiver kind.

## Compatibility contract

- Preserve the public `ErrorCategory` taxonomy. Map the reviewed
  `Unknown (Array|String|Numeric|Object|Boolean)Value filter: <name>` family to `TYPE` (bare-only
  for Numeric/Boolean; bare and call-form for Array/String/Object), the reviewed
  `Cannot apply filter "<name>" to type: <type>` family to `TYPE`, and the reviewed
  `` `<selectattr-or-rejectattr>` can only be applied to array of objects`` family to `TYPE`.
  Do not add a catch-all classifier rule that could silently accept a future upstream diagnostic
  family.
- Each new `v1.jsonl` record must pass through the Node runner and have an equivalent named Java
  `InterpreterTest`. The Node result is authoritative for success/error selection and category;
  Java assertions prove the selected dispatch path and any exact message promised by this slice.
- Distinguish two error families in Java: unknown bare filter names use the reviewed type-specific
  `Unknown …Value filter` wording for Array/String/Numeric/Object/Boolean (with tuples treated as
  arrays), and use the double-quoted receiver-error helper for Null/Undefined/Function values;
  unsupported call forms use
  either that Array/String/Object fallback or upstream's double-quoted `Cannot apply filter
  "<name>" to type: <concrete type>` receiver error for numeric, boolean, null, and undefined.
  Do not generate `Unknown NumericValue` or `Unknown BooleanValue` on a call-form path.
- Normalize the existing `filterReceiver` helper to that same double-quoted `Cannot apply filter
  "<name>" to type: <type>` spelling. This covers call-form known-name receiver errors such as
  `none | int()` and `1 | join()`. It is byte-exact only for the reviewed cases where Node selects
  that family; bare known-name wrong-receiver cases such as `1 | upper` remain category-only when
  upstream instead selects an `Unknown …Value filter` diagnostic.
- For every call-form filter covered by this slice, evaluate arguments at the pinned upstream's
  branch-specific point. An unsupported call form must not evaluate an argument merely because
  Java's generic argument collector runs first.
- Keep host-boundary failures and resource limits outside this runtime parity contract. Do not
  change `Template.format()` error classification in this slice.

## Implementation plan

1. **Build the reviewed Node error matrix before touching Java production code.**

   Add self-authored, version-pinned records to `src/test/resources/corpus/v1.jsonl` for bare and
   call-form `safe`, bare and call-form object `items`, each receiver row in the complete matrix
   above, and one filter-block case (`{% filter safe(1) %}x{% endfilter %}`). Include both zero-
   and nonzero-argument `first`/`last` cases: the former currently succeeds in Java while the
   latter currently becomes `ARITY`. Use `raise_exception('sentinel')` and the verified spreads
   `'abc' | safe(*'zz')` and `{'a': 1} | items(*'ab')` to prove the two opposite evaluation
   boundaries. Add `{{ 1 | upper }}`, `{{ 1 | frob }}`, `{{ 1.5 | frob }}`, and
   `{{ true | frob }}`: the two `frob` number records prove IntegerValue and FloatValue collapse
   to the bare `Unknown NumericValue filter` spelling, while the boolean record exercises its
   typed-message helper arm and `upper` remains a category-only known-name receiver mismatch. Add
   one float call-form record, `{{ 1.5 | abs() }}`, to prove the receiver-error helper instead uses
   the concrete `FloatValue` name. Add `{{ none | frob }}`, `{{ nope | frob }}`, and
   `{{ range | frob }}` to prove NullValue,
   UndefinedValue, and FunctionValue use the receiver-error fallback; the null case also gives the
   second classifier pattern a bare-path vector. Include both `{{ (1, 2) | first() }}` and bare
   `{{ (1, 2) | frob }}` records to prove TupleValue maps to ArrayValue in call and bare paths.
   Add only records whose exact templates and output/error category can be retained under the
   existing corpus policy.

   Run the records first against `tools/corpus/run-node-oracle.mjs` to capture the exact pinned
   messages and validate the intended category. Extend
   `tools/corpus/error-patterns-0.5.9.json` with one anchored, type-enumerated named-capture
   pattern for the five reviewed `…Value` kinds, a second anchored
   `^Cannot apply filter "(?<name>.+)" to type: (?<type>.+)$` pattern, and a third anchored
   expression for ``^`(?<name>selectattr|rejectattr)` can only be applied to array of objects$``.
   Add a fourth anchored pattern for `` `map` expressions without `attribute` set are not currently
   supported.``. Add JavaScript unit tests for a match and a near miss for each family, plus each
   typed-unknown boundary type; retain the version check in `corpus.mjs`. Do not weaken the
   classifier into a generic `Unknown.*filter` rule.

2. **Make Java filter dispatch follow the pinned bare-versus-call-form split.**

   Refactor `Interpreter.applyFilter` through a pre-dispatch shape selector. For bare forms,
   preserve successful behavior and known-name receiver behavior, but change every unknown name to
   the message defined in the compatibility contract: typed unknown for Array/String/Numeric/
   Object/Boolean, receiver error for Null/Undefined/Function. Keep `namedArguments` as the sole
   evaluator, but invoke it at the same upstream branch point: `tojson`, `int`, `float`, and
   `default` evaluate first; `join` first validates its receiver and then evaluates; array `sort`,
   `selectattr`, `rejectattr`, and `map` evaluate only after the array branch is selected; string
   `indent` and `replace` evaluate only after the string branch; and an object FunctionValue
   builtin evaluates only after lookup succeeds. The exact object call-form builtin set is `get`,
   `items`, `keys`, `values`, and `dictsort`; every other object call-form name (including
   `length()`) takes the ObjectValue unknown-filter fallback. Every unsupported call-form matrix
   entry fails before argument evaluation.

   Implement the complete matrix: only bare `safe` is a no-op; call-form `items` on an object
   evaluates arguments once and ignores them as the pinned builtin does; unsupported call-form
   array/string/object names take the appropriate typed unknown-filter fallback; and
   numeric/boolean/null/undefined call forms take the double-quoted receiver-error fallback.
   For `selectattr`/`rejectattr`, dispatch in this order: an empty array returns `[]`; any array
   containing a non-object returns the pinned `TYPE` message; and an all-object array with no
   arguments retains Java's current named `ARITY` divergence instead of reproducing the pinned
   JavaScript crash. Document that final outcome and test it as a Java-only divergence. Route Java
   `TupleValue` through the ArrayValue fallback to mirror upstream inheritance.

   Use separate private helpers for bare typed-unknown messages and call-form receiver errors.
   The former maps only Array/String/Numeric/Object/Boolean (with tuple as Array); the latter uses
   the concrete upstream type names and double quotes, reusing `type()`'s existing
   CallableValue-to-`FunctionValue` mapping. Do not use the bare helper on a call-form numeric or
   boolean error. Update the existing shared `filterReceiver` helper to double quotes;
   do not otherwise widen category-only bare known-name receiver mismatches outside the explicitly
   tested classifier vectors. `KeywordArgumentsValue` cannot be an independently evaluated filter
   operand; assert it is unreachable in both helpers rather than giving it a distinct diagnostic,
   matching upstream's ObjectValue inheritance and the existing assert-unreachable convention.
   The existing `unreachable(Expression)` helper requires a source location, so add a small
   Value-specific assertion helper rather than reusing it from either value diagnostic path.

3. **Add focused Java regressions and document the comparison boundary.**

   Replace the conflicting expectations in `InterpreterTest` with exact message plus
   `ErrorCategory.TYPE` assertions for the complete matrix. Add tests that prove:

   - `safe` stays a no-op only in bare form and a call-form `safe` does not execute its argument;
   - object `items(...)` returns the same result as bare `items` and evaluates its supplied
     argument exactly once when the oracle does;
   - both `first()`/`last()` and their argument-bearing forms use the typed unknown-filter
     diagnostic rather than succeeding or producing `ARITY`, including the tuple case, and their
     throwing arguments are not evaluated;
   - `selectattr()`/`rejectattr()` distinguish empty-array success, non-object-array `TYPE`, and
     the named Java-only `ARITY` outcome for an all-object array with no arguments; and return
     `TYPE`, not `ARITY`, for the four uniform non-array receiver rows;
   - a numeric `join(raise_exception('x'))` still rejects its receiver before evaluation, while
     numeric `int(raise_exception('x'))` evaluates and raises, pinning the branch-specific timing;
   - `none | int()` and `1 | join()` use the normalized double-quoted receiver-error wording;
   - a filter block reaches the same `applyFilter` behavior as an output expression; and
   - integer and float `frob` records both use `Unknown NumericValue filter`, while float `abs()`
     uses the concrete `FloatValue` receiver error; and
   - `{{ true | frob }}` exercises the BooleanValue bare typed-unknown helper arm; and
   - the existing macro-is-not-a-filter regression retains its namespace-resolution assertion but
     updates `Unknown filter: f` to the oracle's receiver-specific message for both string and
     array macro-filter operands;
   - representative bare non-matrix filters retain their existing behavior.

   A named Java regression may group a coherent receiver family and assert several corresponding
   `v1.jsonl` records; it need not be one method per record. Each assertion must identify the
   mirrored corpus record ID in its display name or failure message, so the Node and Java evidence
   remains directly traceable.

   Update the public error documentation (README and/or `req/project-description.md`) to state
   that normal runtime parity compares categories by default. State explicitly that this slice
   pins exact messages only for bare typed-unknown filters and the reviewed call-form receiver
   errors. State explicitly that Java's normalized receiver-error text is byte-exact only where
   the pinned oracle selects that family; `1 | upper` remains category-only because Node instead
   selects `Unknown NumericValue filter: upper`. Also distinguish known-name wrong-receiver
   category-only cases (`[1] | items`, `'abc' | abs`). Record this distinction in the relevant
   parity note; do not claim exact message parity globally.

4. **Falsify the new evidence.**

   Temporarily remove each of the four new Node classifier patterns and confirm its dedicated
   corpus error records fail as unmatched. Temporarily make call-form `safe` evaluate its
   argument, reject `items(1)`, and restore the old bare `first` dispatch; in each case, confirm
   its dedicated Java and/or corpus regression fails. Separately make numeric `join`
   evaluate before its receiver, make tuple fall through to `TupleValue`, and make an empty
   `selectattr` array reach the arity check; confirm the timing, tuple, and empty-array regressions
   fail. Revert every mutation before committing.

5. **Verify with the pinned toolchain.**

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

- Every new error corpus record is accepted by the version-pinned Node classifier and has a named
  Java `InterpreterTest` that agrees on `TYPE`/success outcome.
- The classifier recognizes the reviewed typed-unknown, double-quoted receiver-error, and
  non-object `selectattr`/`rejectattr` families, and rejects a near miss for each.
- Every listed unsupported call-form matrix entry, call-form `safe`, object `items`, and the
  filter-block path match the pinned dispatch, argument-evaluation, output, and category behavior.
- Tests explicitly prove the non-evaluation/evaluation boundaries rather than merely asserting
  final error categories.
- The project documents its category-first error contract and any deliberately exact diagnostic
  family; no unsupported blanket claim of byte-exact error messages is added.

## Deliberately deferred

- Remaining WP7 item 1 semantic divergences outside this call-form matrix, including supported-
  filter eager evaluation, ignored extra arguments/unknown keywords, macro/call-block control
  flow, and the listed `tojson`/`dictsort`/`replace`/`get`/`lower` cases.
- The pinned `selectattr()`/`rejectattr()` array-of-objects `TypeError` caused by dereferencing an
  omitted attribute. It has no stable public error category; retain it as a documented Node crash
  until the wider error-contract work determines an appropriate treatment.
- The pinned object `get` crashes for both bare and zero-argument call forms while dereferencing a
  missing key argument. Java reports a stable `TYPE` error instead; retain this as a documented
  Node crash until the wider error-contract work determines an appropriate treatment.
- `selectattr`/`rejectattr` on an all-object array with a non-string supplied attribute: the pinned
  runtime reports ``arguments of `selectattr`/`rejectattr` must be strings`` while Java reports a
  differently worded `TYPE` error. This is outside the no-argument matrix and remains
  category-only until its diagnostic family is reviewed and classified.
- Rendering a host function through `safe` or `default()` produces Java's stable `<function>` text
  while the pinned runtime renders JavaScript function source. This pre-existing host-function
  representation difference is outside the normal host-value compatibility contract.
- WP7 item 2's complete executable upstream-vector inventory and its release-blocking report.
- WP7 item 5's converter re-run and complete pinned upstream test-suite gate.
- WP6 Slice 4 publication/reproducibility/API-example work and the final release checklist.
