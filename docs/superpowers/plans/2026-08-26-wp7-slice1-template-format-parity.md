# WP7 Slice 1 — Pinned `Template.format()` Parity

## Goal

Implement the pinned `@huggingface/jinja` `Template.format()` feature in hfjinja and prove its
canonical output against the Node 0.5.9 oracle. This is the next implementation priority: release
packaging, publication polish, and a consumer example remain deferred until the upstream public
feature surface is complete.

## Evidence and scope

The pinned upstream `Template` constructor stores a parsed `Program`, `render()` evaluates it, and
`format(options?: { indent: string | number })` delegates to `format.ts`. That formatter serializes
the full parsed AST to a canonical template style; it is not the runtime `strftime` formatter and
must use the existing JavaScript-format helpers where they directly mirror upstream. In particular,
string literals use `JsFormat.quote(String)` for `JSON.stringify`, and integer/float literals use
`JsFormat.plainString(double)` for JavaScript interpolation. It must **not** use
`JsFormat.floatString(double)`: that runtime-display helper deliberately retains an integral
fractional zero, while upstream formatting turns `{{ 1.0 }}` into `{{- 1 -}}`. `PosixStrftime`,
not `JsFormat`, is the unrelated runtime date formatter. Upstream has seven canonical formatting
vectors in `upstream/vendor/test/format.test.js` and additionally checks that formatting then
reparsing preserves rendered output across its template test strings.

hfjinja currently has `Template.parse` and the render overloads but no public formatting method.
`upstream/mapping.yml` marks `index.ts` planned and `format.ts` as a no-runtime-path exclusion.
This slice removes both gaps. It does not attempt the remaining WP7 runtime divergences or WP6
publication work.

For this exact pinned Node/V8 oracle, define one formatter compatibility constant:
`MAX_PINNED_NODE_STRING_LENGTH = 536870888` (`2^29 - 24`). Numeric indentation and repeated string
indentation must reject a requested code-unit length strictly greater than that value. The first
rejected finite count, derived from that constant, is `MAX_PINNED_NODE_STRING_LENGTH + 1`.

## Compatibility contract

- Add public `Template.format()` for upstream's default formatting behavior: a tab indentation
  unit and removal of exactly one trailing newline, matching `body.replace(/\n$/, "")`.
- Add Java overloads `Template.format(String indent)` and `Template.format(double indent)` to express
  upstream's allowed string-or-number indent without an `Object`-typed API. Match upstream's
  effective defaulting: an empty string, zero, negative zero, or `NaN` selects the default tab; a
  non-empty string is repeated per nesting depth; and numeric indentation follows the pinned
  JavaScript `String.repeat` count conversion rather than Java's integer-only API. In particular,
  a truthy count in `(-1, 0)` truncates to negative zero and produces an **empty** indentation unit:
  it is neither the tab default nor a rejection. A positive fractional count truncates toward zero
  before producing spaces.
- Oracle-test default-tab, empty-unit, N-spaces, and rejected numeric outcomes. Cover negative
  fractions, positive fractions, `NaN`, infinities, and the pinned Node's smallest rejected finite
  count as well as ordinary integral cases. Reject every count the pinned `String.repeat` rejects
  eagerly with documented `IllegalArgumentException`, matching the project's existing public
  builder-argument-validation convention. hfjinja owns the stable Java message; Node RangeError
  wording is not part of this Java API contract. Normalize the number with
  `JsFormat.plainString` when a message includes it, so integral doubles do not become `-2.0`.
  This validation happens before AST traversal, including for an empty template.
- Formatting consumes the immutable parse tree only: it must never mutate `Template`, cache a
  caller graph, evaluate expressions, invoke host functions, inspect `RenderOptions`, or apply
  render budgets. It is deterministic and safe for concurrent calls on one `Template`.
- It must serialize every AST node that the parser can produce, including comments, conditionals
  and elif chains, loops/else/select expressions, set capture/value forms, macros, calls, filter
  blocks, break/continue, all literals, calls, members, filters/tests, keyword/spread arguments,
  slices, and ternaries. Parser guarantees about unreachable generic-dispatch nodes do not excuse
  a formatter omission.
- Do not impose idempotence. Upstream preserves comment text verbatim while adding comment padding,
  so repeated formatting intentionally accumulates spaces in comments; this behavior needs a
  named oracle vector.

## Implementation plan

1. **Add a checked-in Node-golden format harness before the Java formatter.**

   Add versioned source vectors under `src/test/resources/format/` and a Node formatter script under
   `tools/format/`. The source record names the source template, default/string/numeric indentation
   operation, upstream test source location, and a `roundTrip` expectation of either `preserves` or
   `upstream-diverges`. Keep this separate from `v1.jsonl`; ordinary rendering corpus records must
   not gain ambiguous operation fields.

   The explicit golden-update command runs the pinned Node implementation and writes a reviewed
   `src/test/resources/format/upstream-formatted.jsonl` artifact containing exact UTF-8 formatted
   output and, where requested, original/reparsed render results. A `formatGoldenVerify` Gradle
   `Exec` task runs the same script in `--check` mode, declares the script, vectors, checked-in
   golden, and vendored `dist/index.js` as inputs, writes only a build marker, and is a direct
   dependency of `check`. `FormatDifferentialTest`—added with the Java implementation in step 3—
   reads that checked-in Node artifact, following `AstSnapshotDifferentialTest`, and byte-compares
   the Java results. It does not attempt to read `v1.jsonl`.

   Convert all named cases in `format.test.js` into reviewed resource records, preserving their
   upstream source locations. Those seven cases contain only un-nested output expressions, so their
   `indent: 4` is unobservable; generated/manual vectors are the required, not supplemental,
   coverage for default tab, positive/fractional numeric, non-empty string, zero/empty/`NaN`
   defaulting, the negative-fraction empty-unit case (for example `-0.5`), rejected negative and
   infinite counts, and nested statement indentation. Add a rejected large-finite-count vector at
   `MAX_PINNED_NODE_STRING_LENGTH + 1` (the *smallest* rejected finite count) using an empty
   template, so it proves eager rejection without materializing a giant formatted result.
   Split numeric validation/normalization from indentation-unit construction so a Java-only unit
   test can check the accepted boundary without allocating its roughly 512 MiB space string.
   Document the value as a pinned-Node compatibility limit, not an ECMAScript guarantee. Represent
   the oversized-string-indent case with a generated indentation length in the vector schema, not
   a checked-in multi-megabyte string. Add vectors for empty templates, comments (including
   deliberate repeated-format non-idempotence), escaped strings/unicode, and every
   parsed expression/statement variant not reached by upstream's seven named examples.

   Record re-rendering expectations per vector. For `preserves`, parse the formatted output and
   compare its rendered result to the original under fixed context/options. For
   `upstream-diverges`, compare each result to its Node-golden value rather than failing the test;
   pin `{{ 1.0 }}|{{ 2.50 }}` because upstream formatting loses the integral-float identity and
   reparses it as `1|2.5`. Do not use hand-written Java expected text where the Node oracle can
   provide it.

2. **Design and add the narrow public API before Java differential tests.**

   Add the three `Template.format` overloads above with complete Javadocs, null validation, and the
   documented eager `IllegalArgumentException` for oracle-rejected numeric counts. Keep
   `Template`'s only instance field as its final `Statement.Program`; no format result cache is
   allowed. Add public-boundary tests for the
   default and overload routing, null handling, no mutation, and concurrent repeated formatting.
   State in `format(double)`'s Javadoc that `format('\t')` widens the character to numeric code
   point nine, while `format("\t")` selects a tab indentation unit through `format(String)`;
   callers needing text must use the `String` overload.

   Update README's feature/API list to state that templates can be canonically formatted, with a
   compact `format()` example. Do not change parse/render semantics or add an API for arbitrary
   Java values.

3. **Port `format.ts` structurally over the Java AST.**

   Create an unexported formatter in `se.alipsa.hfjinja.internal` that accepts only
   `Statement.Program` and an already-validated indentation specification. Keep numeric
   normalization/validation in a separate package-private helper that returns the normalized count
   before constructing a space string; unit-test its accepted/rejected boundaries without output
   allocation. Before every indentation-string repetition, validate the requested code-unit length
   against `MAX_PINNED_NODE_STRING_LENGTH`. This covers both numeric space construction and a
   caller-supplied `String` indentation unit at deep AST nesting, preventing Java from allocating a
   string where pinned Node raises. Use a generated test indent string and nested source rather than
   checking in a multi-megabyte resource. Port upstream helper-for-helper:
   statement delimiters/newlines, indentation, `if`/`elif`/`else`, `for`/`else` and select form,
   set/capture, macro, call, filter, comment, and the default output expression form.

   Port expression formatting with the same binary precedence and associativity rules, unary
   grouping, ternary parentheses, member-access grouping, computed/noncomputed properties, call
   arguments, filters/tests, collections, object entry ordering, slices, keyword args, and spreads.
   Use `JsFormat.quote` for string literals and `JsFormat.plainString` for both numeric literal
   records; add a dedicated helper only for a format.ts behavior those two APIs do not cover.
   Preserve insertion order and JavaScript-style string escaping exactly. Do not make the formatter
   depend on interpreter `Value` instances.

   Add `FormatDifferentialTest` in this step, now that `Template.format` compiles. It loads the
   checked-in Node golden described in step 1, verifies every format result byte-for-byte, and
   applies each record's round-trip expectation. It must report the vector name/source location,
   indentation mode, and expected/actual UTF-8 difference on failure.

4. **Make the mapping and parity evidence truthful.**

   Change `upstream/mapping.yml` so both `format.ts` and `index.ts` are implemented and list only
   their relevant Java sources and Java tests, matching the existing ledger schema and
   `upstreamVerify`'s `.java` existence validation. Retire—not as a stale hash—the currently valid
   reviewed-no-port-impact entry for `format.ts`; retain no generic `index.ts` planned entry.

   Keep Node scripts and resource vectors as explicit inputs of `formatGoldenVerify`, rather than
   pretending the mapping ledger validates them. In `--check` mode, the Node script parses every
   source vector through the pinned, unminified, hash-pinned `dist/index.js`, walks the resulting
   AST, and diffs each `node.constructor.name` against `upstream/ast-allowlist.json`. Constructor
   names match the inventory's ast.ts class names (for example `SetStatement`, rather than runtime
   `node.type` value `Set`); this is safe only because the exact vendor bundle is reviewed and
   pin-verified. Exempt only the three abstract inventory entries `Statement`, `Expression`, and
   `Literal`; `Program` must be present. The script writes the sorted seen/missing report under
   `build/reports/` and fails for any unrepresented concrete node, making this AST
   formatter-coverage gate a direct `formatGoldenVerify` and `check` failure.

5. **Falsify coverage before accepting it.**

   Make temporary, uncommitted mutations and run the focused Java and Node format suites after
   each: change the statement delimiter trim markers, remove the exactly-one trailing-newline trim,
   alter a binary-precedence value, remove member grouping, omit `elif` flattening, remove ternary
   parenthesization, and change indentation repetition/defaulting. Temporarily reject every
   negative double and separately remove the pinned large-count guard; confirm the negative-fraction
   empty-unit vector and large-finite rejection vector fail respectively. Separately remove the
   repeated-string-length guard and confirm the generated oversized-string-indent validator test
   fails before attempting formatting/allocation. Confirm the appropriate distinct vector fails,
   then revert. Also make a temporary `Template` format
   cache field and confirm the existing structural concurrency/retention test fails or extend that
   named test so it does. Do not commit mutations.

6. **Verify under the pinned toolchain.**

   Confirm JDK 21 and Node 26.7.0. After Java edits, run:

   ```bash
   ./gradlew spotlessApply
   ./gradlew test --tests se.alipsa.hfjinja.PublicApiTest --tests se.alipsa.hfjinja.TemplateConcurrencyTest --tests se.alipsa.hfjinja.FormatDifferentialTest
   ./gradlew formatGoldenVerify upstreamVerify
   ./gradlew check
   git diff --check
   ```

## Acceptance criteria

- hfjinja exposes `Template.format()` plus typed indentation overloads and has a documented eager
  `IllegalArgumentException` contract for every oracle-rejected numeric indentation. It separately
  pins tab defaulting, negative-fraction empty indentation, truncated positive fractions, and the
  pinned Node's large-finite rejection boundary, including deep repetition of a caller-supplied
  string indent.
- Every parseable AST node has Node-oracle-backed canonical-format coverage; the formatter produces
  byte-exact output for the pinned upstream vectors.
- Default, numeric, and string indentation behavior matches the upstream operation. Each vector
  explicitly pins whether formatting/reparse preserves output or follows a demonstrated upstream
  divergence.
- Formatting is deterministic, concurrent-safe, non-evaluating, and adds no mutable per-template
  state.
- `format.ts` and `index.ts` are implemented in the mapping ledger; no no-runtime-path or public
  API exclusion remains.

## Deliberately deferred

- The remaining WP7 normal-runtime parity gaps, which follow as focused oracle-backed slices.
- WP6 Slice 4 publication/reproducibility/Javadoc/tokenizer-example work and WP6 item 5 release
  checklist work. They resume only after the pinned feature surface is complete.
