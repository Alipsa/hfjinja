# WP4 Slice 3 — Filters, Tests, and Corpus Expansion

## Goal

Replace the current `FilterExpression` and `TestExpression` placeholders with the next
reviewable subset of the pinned `@huggingface/jinja` 0.5.9 runtime. This slice should make the
common chat-template filters and tests available without starting the later macro, call-block,
slice, or keyword/spread work.

## Scope and constraints

- Preserve the upstream evaluation order: evaluate the operand once, then evaluate filter/test
  arguments from left to right. Keep all errors location-bearing and mapped to existing
  `ErrorCategory` values.
- Reuse `Value`, `JsOperations`, `JsFormat`, `RenderBudget`, and `Interpreter` helpers; do not add
  a production dependency or widen the exported public API.
- Keep `tojson` deterministic and use the existing runtime JSON formatter. Do not duplicate JSON
  or number formatting logic. This deliberately brings the basic `tojson` path forward from the
  M3 list in `req/project-description.md`; M3 retains its remaining formatting-helper and
  full-feature-parity work, and milestone tracking must record this acceleration.
- Start with the filter/test cases represented by vendored unit tests and current corpus needs.
  Leave unsupported advanced filters/tests explicit rather than accepting them with approximate
  host-language behavior.
- Keep `upstream/mapping.yml` current in the same change. Any behavior not ported must remain
  accounted for by the existing milestone mapping.
- Generate manual Javadoc only beneath `build/` or a temporary directory, never the repository
  root.

## Work plan

1. Characterize upstream behavior and publish a checkable matrix.

   Inventory `runtime.ts` dispatch for the proposed subset: `tojson`, `default`, `length`,
   `lower`, `upper`, `trim`, `join`, `int`, and `float`; and defined/undefined, none, boolean,
   number, string, sequence, iterable, and equality tests. Commit a compact design table before
   implementation, with one row per item and columns for accepted input `Value` tags, argument
   arity/type rules, result `Value` tag and rendered text, undefined/null behavior, and upstream
   error shape/category. Confirm from the pinned Node oracle whether `iterable` differs from
   `sequence`; do not assume Jinja2 semantics. The table is the Step 1 deliverable and provides a
   per-item implementation checklist.

   Implemented matrix (pinned `runtime.ts` dispatch, confirmed by the Node oracle):

   | Item | Input tags and arguments | Result / undefined-null rule | Unsupported shape |
   | --- | --- | --- | --- |
   | `tojson` | all JSON-renderable values; no options in this accelerated basic path | `StringValue` from `JsFormat.runtimeJson` | `ARITY` for options; full options remain M3 |
   | `default` | all values; fallback and optional boolean flag | substitutes only undefined (including undefined-backed string), or falsy with `boolean=true`; null stays present | `ARITY` / `TYPE` |
   | `length` | array/tuple, string, object; no arguments | `IntegerValue` length | `TYPE` receiver, `ARITY` arguments |
   | `lower`, `upper`, `trim` | present string; no arguments | transformed `StringValue` | `TYPE` receiver, `ARITY` arguments |
   | `join` | array/tuple or present string; optional string separator | `StringValue`; string is joined by Unicode code point | `TYPE` receiver/separator, `VALUE` unknown keyword, `ARITY` excess |
   | `int`, `float` | string, number, boolean; optional default | parsed/coerced numeric value, default on failed string parse | `TYPE` receiver, `VALUE` unknown keyword, `ARITY` excess |
   | `defined`, `undefined`, `none`, `boolean`, `number`, `string` | no arguments | `BooleanValue`; only undefined is undefined, while null is `none` and defined | `ARITY` arguments |
   | `iterable` | array/tuple or present string | `BooleanValue`; distinct from `sequence` | `ARITY` arguments |
   | `sequence` | array/tuple, object, or present string | `BooleanValue`; upstream includes objects here but not in `iterable` | `ARITY` arguments |
   | `eq`, `equalto` | parser permits only the identifier, so upstream receives no comparison value | `BooleanValue` comparing the operand payload with `undefined` | meaningful argument-bearing equality remains deferred with a parser/AST extension |

2. Add regressions before implementation.

   Add parser-to-render examples in `InterpreterTest` for each selected filter/test, including
   chained filters, test arguments, undefined-backed strings, null, non-finite numbers, and bad
   operand/argument types. Add package-private unit coverage only for reusable coercion helpers.
   Assert categories and locations for failures rather than brittle upstream message text. Use
   `TYPE` for an unsupported filter/test name or an invalid receiver type, `ARITY` for an invalid
   argument count, and `VALUE` for a recognized operation whose argument value is invalid; record
   any confirmed upstream exception in the Step 1 matrix before deliberately choosing otherwise.

3. Implement filter evaluation.

   Add a private interpreter evaluator for `FilterExpression`. It must dispatch by explicit filter
   name and runtime value type, evaluate supplied arguments exactly once, and return immutable
   `Value` instances. Route `tojson` through `JsFormat.runtimeJson`; route string/numeric
   conversions through existing JavaScript-compatible formatting and conversion helpers. Reject
   unsupported filters and invalid receiver types with a located `TYPE` render error; use `ARITY`
   or `VALUE` for the respective argument failures defined in Step 2.

4. Implement test evaluation.

   Add a private evaluator for `TestExpression` that produces `BooleanValue`. Implement the
   selected predicates using the closed `Value` model rather than Java reflection or collection
   shortcuts. Reuse the established undefined/null distinction: an undefined-backed string is an
   undefined payload for defined/undefined tests, while a present Java null remains `NullValue`.

5. Expand the differential corpus.

   Add small approved text-bearing corpus cases for the completed filters/tests, with deterministic
   contexts and expected Node output/categories. Update the converter coverage report and keep
   model-derived fixtures out unless separately approved by the fixture policy.

   Review `upstream/ast-allowlist.json` and its AST-inventory test as part of this change. Remove
   or update the `FilterExpression` and `TestExpression` M2 exemptions only when this slice has
   made those node kinds fully supported; otherwise retain the exemptions and document the
   remaining unsupported names in the mapping/table so the partial support is not represented as
   complete M2 coverage.

6. Verify and hand off.

   Iterate with `./gradlew test --offline --tests '*RuntimeTest'` (and the relevant
   `InterpreterTest` selection), then run `./gradlew check --offline` and
   `./gradlew upstreamVerify --offline`. Run the pinned Node oracle explicitly with
   `./gradlew nodeCorpusVerify --offline`; it validates
   `src/test/resources/corpus/v1.jsonl` and writes its success marker to
   `build/nodeCorpusVerify/verified`. Also run `./gradlew corpusCoverage --offline`, which writes
   `build/reports/corpus-coverage.md`, and `git diff --check`. If API documentation is checked
   manually, direct its output to `build/` or a temporary directory. Submit one review-ready
   work-package PR.

## Acceptance criteria

- `FilterExpression` and `TestExpression` no longer use the generic unsupported path for the
  selected upstream subset.
- Successful outputs and comparable failures match the pinned Node oracle for the added cases.
- Filter/test dispatch is explicit, deterministic, and does not expose host objects or methods.
- JSON and number text use shared formatters; no duplicate formatter is introduced.
- `upstream/mapping.yml`, corpus coverage, tests, and implementation change together.
- The full offline verification suite passes without generating files in the repository root.
