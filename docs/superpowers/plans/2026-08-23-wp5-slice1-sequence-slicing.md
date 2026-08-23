# WP5 Slice 1 — Sequence Slicing

## Goal

Implement the parser's existing `Expression.SliceExpression` for computed member access, matching
the pinned `@huggingface/jinja` 0.5.9 `slice` utility. This is the first bounded WP5 feature:
it makes `sequence[start:stop:step]` work for arrays, tuples, and strings without taking on
macros, block calls, filter blocks, or spread arguments.

## Current state and scope

`Parser` already produces an `Expression.SliceExpression` for the colon forms inside `[...]`;
its `start`, `stop`, and `step` fields are `null` when omitted. `Interpreter.member`
currently tries to evaluate that node as an ordinary property, so all slice forms fail through the
generic unsupported-expression path. The pinned upstream instead detects `SliceExpression` while
evaluating `MemberExpression`, evaluates the target first, then delegates to `utils.ts`'s
Python-style `slice` function.

Upstream `TupleValue` extends `ArrayValue`, so tuples pass its receiver check. Java models the
two as sibling records; the port must therefore accept tuples explicitly and return a fresh plain
`ArrayValue`, just as upstream's `new ArrayValue(slice(...))` drops tuple-ness.

In scope:

- array, tuple, and string targets;
- omitted, positive, negative, and clipped integer bounds;
- positive and negative integer steps, including the pinned utility's zero-step result;
- Java regressions, a Node-verified corpus vector, and mapping-ledger updates for the newly ported
  `utils.ts` path.

Out of scope:

- float, boolean, string, null, or other explicit bounds/steps;
- keyword/spread expression dispatch, macros, call blocks, and filter blocks;
- unrelated array/string built-in methods.

## Work plan

1. Characterize the pinned oracle before changing Java.

   Read `upstream/vendor/src/runtime.ts`'s `evaluateSliceExpression` and
   `upstream/vendor/src/utils.ts`'s `slice`. Use the pinned Node runtime to record result text,
   value tag, and error shape for:

   | Case | Oracle property to record |
   | --- | --- |
   | `[1,2,3][1:]`, `[:2]`, and `[:]` | omitted bounds default to each direction's endpoints |
   | `[0,1,2,3,4][1:4:2]` and `[-4:-1]` | positive-step exclusive stop, negative bounds, and clipping |
   | `[0,1,2,3,4][::-1]` and `[4:0:-2]` | reverse defaults, negative step, and exclusive stop |
   | `[0,1][::0]` and `[0,1][:]` | explicit zero reaches `Math.sign` and yields `[]`; omitted/undefined step defaults to `1` |
   | `'A😀BC'[1:3]` and `'hello'[::-1]` | strings slice `Array.from(value)` code points, not Java UTF-16 chars |
   | `(1,2)[:]` and `(1,2,3)[::-1]` | tuple input passes the upstream array check and returns a plain array |
   | `{'a':1}[:]`, `1[:]`, `none[:]`, and `x[:]` | receiver text: `Slice object must be an array or string` |
   | `[1][1.0:]`, `[1]['1':]`, `[1][none:]`, `[0,1,2][(6/2):]`, and `[0,1,2][::true]` | explicit non-integer component error; even integral `FloatValue` is rejected |
   | `[0,1,2][3000000000:]`, `[-3000000000:]`, `[5:1]`, `[][:]`, and `''[:]` | large-bound clamping, empty receivers, and exclusive stop |

   Every slice failure above is currently unclassifiable:
   `tools/corpus/error-patterns-0.5.9.json` has no pattern for the receiver or component
   messages. Keep failures out of `v1.jsonl`; preserve each as a Java-only regression with its
   observed Node text documented next to the assertion. In particular, document that
   `{{ 'abc'[9][0:1] }}` produces upstream `TypeError: undefined is not iterable`; Java
   deliberately reports its existing undefined-receiver behavior instead. Do not add broad error
   normalization patterns solely for this slice.

   Append one compact pipe-joined successful vector to `src/test/resources/corpus/v1.jsonl`,
   modelled on `self.ternary-expressions` and covering
   forward/reverse, negative and clipped bounds, zero step, empty receivers, tuple conversion, and
   Unicode. This is the target shape; obtain its exact `expected.text` from Node before committing:

   ```json
   {"id":"self.sequence-slicing","source":"self-authored; verified against @huggingface/jinja 0.5.9","template":"{{ [0,1,2,3,4][1:4:2] }}|{{ [0,1,2,3,4][-4:-1] }}|{{ [0,1,2][3000000000:] }}|{{ [0,1][::0] }}|{{ [][:] }}|{{ ''[:] }}|{{ (1,2)[::-1] }}|{{ 'A😀BC'[1:3] }}|{{ 'hello'[::-1] }}","context":{},"expected":{"text":"[1, 3]|[1, 2, 3]|[]|[]|[]||[2, 1]|😀B|olleh"}}
   ```

   Get the exact text first by directly importing and rendering with the pinned
   `upstream/vendor/dist/index.js` in a small temporary Node invocation, then paste that golden
   into the JSONL record. `nodeCorpusVerify` has no generation/write mode; it verifies the pasted
   text through:

   ```bash
   ./gradlew nodeCorpusVerify --offline
   ```

   The task creates `build/nodeCorpusVerify/verified`; then run
   `./gradlew corpusCoverage --offline` to refresh/check
   `build/reports/corpus-coverage.md`.

2. Add focused interpreter regressions.

   In `InterpreterTest`, assert the oracle matrix in readable groups: ordinary array slices,
   reverse/clamped slices, tuple-to-array conversion, strings with a supplementary Unicode code
   point, and receiver/component failures. Assert both `ErrorCategory` and the deliberately
   chosen location: receiver failures carry `MemberExpression.location()` (the receiver's start),
   while a present invalid component carries that component's own location, not the opening
   bracket's `SliceExpression.location()`. Choose templates where those offsets differ.

   Add the evaluation-order regression `{{ [0,1]['a': missing + 1] }}`. It must report
   `Cannot perform operation + on undefined values`, proving start, stop, and step are all
   evaluated before validation; validating start as it is evaluated would incorrectly report the
   start-type error. Also cover receiver-before-components order with a failing receiver and a
   failing bound expression.

3. Port the utility without Java collection shortcuts.

   Add a small internal utility (for example `internal.util.JsSlice`) that ports the exact
   `utils.ts::slice` normalization and loop rules. It should take a list of runtime values (or
   code points) and integer-or-omitted bounds, returning a new ordered list. Preserve upstream's
   separate positive/negative clamping, exclusive stop, omitted/undefined step default of `1`,
   and explicit zero's `Math.sign(step) == 0` result. Do not use `List.subList`, Java
   `String.substring`, or Python assumptions.

   `IntegerValue` stores a `double`. Normalize and clamp in `double`/ `long` space and
   narrow only after clamping, so bounds such as `3000000000` cannot overflow like the ordinary
   indexed-member cast. For strings, convert to code points before slicing and construct the result
   with `StringBuilder.appendCodePoint`, so supplementary characters cannot be split.

4. Dispatch slices inside member evaluation.

   In `Interpreter.member`, evaluate the receiver once. When a computed property is
   `SliceExpression`, accept `Value.ArrayValue`, `Value.TupleValue`, or a
   non-undefined-backed `Value.StringValue`; otherwise throw the receiver error at
   `MemberExpression.location()`. Reuse the existing `arrayLike()`/`arrayValues()` idiom for
   array-or-tuple inputs. Evaluate absent AST components as `UndefinedValue`; evaluate every
   present component in start, stop, then step order *before validating any of them*. Then validate
   start, stop, step in that order: each must be `IntegerValue` or undefined, with an invalid
   present component reported at that component's location. Return a fresh plain `ArrayValue` for
   arrays and tuples and a `StringValue` for strings. Keep ordinary computed member lookup
   untouched.

   Undefined-backed strings behave as undefined receivers rather than as a sliceable empty string.
   Keep the Java regression and observed upstream `TypeError` text together as described in step
   1. Reuse the same render budget/environment when evaluating the receiver and each component.

5. Update the provenance ledger and verify.

   Change `upstream/mapping.yml` so `utils.ts` is no longer `planned`:
   `plannedPackages` reserves `internal/util` for it, so adding `internal.util.JsSlice`
   requires an `implemented` mapping. Name the existing `Interpreter.java`/`InterpreterTest.java`
   homes for `range` and `strftime_now`, plus `JsSlice.java` and its focused test (or
   `InterpreterTest` if no separate utility test is added). Add a scoping comment that this maps
   the ported runtime paths, not yet-unimplemented upstream utility exports such as `titleCase`
   and `replace`. Retain the M2 milestone label unless verification requires a reviewed change.
   `java:` and `tests:` must remain single-line inline flow lists: `upstreamVerify` parses the
   ledger line-by-line and validates the complete bracketed list, although sharing
   `Interpreter.java`/`InterpreterTest.java` with the `runtime.ts` entry is permitted.
   Keep `SliceExpression`'s M3 entry in `upstream/ast-allowlist.json`: the entry describes the
   planned milestone, not whether this incremental feature has now been delivered. Do not change
   exemptions for keyword/spread expressions or macro/block nodes.

   Iterate with the focused runtime test class, then run before opening the work-package PR:

   ```bash
   ./gradlew test --offline --tests 'se.alipsa.hfjinja.internal.runtime.InterpreterTest'
   ./gradlew nodeCorpusVerify --offline
   ./gradlew corpusCoverage --offline
   ./gradlew upstreamVerify --offline
   ./gradlew check --offline
   git diff --check
   ```

## Acceptance criteria

- The parser-supported slice forms work on arrays, tuples, and strings with the same defaulting,
  clamping, direction, and exclusive-stop semantics as the pinned Node runtime.
- Tuple slicing returns a fresh plain `ArrayValue`; string slicing operates on Unicode code points
  and never emits an invalid surrogate.
- All components are evaluated before validation; only integer and omitted/undefined components
  are accepted, and invalid targets/components produce deliberately located, stable Java errors.
- Java regressions include the error-only evaluation-order case. `v1.jsonl` includes the successful
  forward, reverse, negative-bound, clamping, zero-step, empty-receiver, tuple, and Unicode matrix;
  the Node corpus, coverage, provenance, and offline checks pass.
