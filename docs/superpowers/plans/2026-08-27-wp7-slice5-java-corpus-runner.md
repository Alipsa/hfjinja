# WP7 Slice 5 — Java Differential-Corpus Execution

## Goal

Close the Java half of the WP4 gate in
[`req/implementation-plan.md`](../../../req/implementation-plan.md#wp4--interpreter-core)
(lines 173–174): both runners must agree byte-for-byte on successful output and by category on
Node-comparable errors. This is a prerequisite-sized part of WP7 item 2, not completion of that
item's inventory expansion.

The pinned Node oracle already validates that each checked-in expected result is a real
`@huggingface/jinja` 0.5.9 result. This slice makes the same record binding on Java, rather than
depending on a separately hand-written `InterpreterTest` assertion for every record. It follows
Slice 4, whose plan deliberately required paired Java assertions only because this runner did not
yet exist.

This is not the complete upstream-vector-inventory conversion promised by WP7 item 2. It provides
the Java enforcement foundation first; the following inventory slice must expand the reviewed
upstream-vector mapping and its report without reintroducing manual Java-pairing bookkeeping.

## Evidence and scope

`src/test/resources/corpus/v1.jsonl` currently contains 131 inline-template, Node-verified records
(72 text outcomes and 59 `errorCategory` outcomes), and no hash-only records. `nodeCorpusVerify`
executes those records only against the pinned Node runtime. `corpusCoverage`
currently extracts and checks only three fixtures from `templates.test.js`; its report explicitly
says the remaining unit vectors still need supported extraction or reviewed manual transcription.
Neither task invokes hfjinja. A new corpus record can therefore pass all Node-side checks while
having no Java assertion—exactly the coverage gap found while reviewing Slice 3.

The public API provides everything a Java corpus runner needs:

- `Template.parse(template).render(context, options)` returns byte-bearing text;
- `HfJinjaException.category()` is the stable Java category for parse and render failures; and
- `RenderOptions` accepts a `Clock` and `ZoneId`, which lets the Java runner reproduce the Node
  oracle's default `2000-01-02T03:04:05Z` / `UTC` pair and each record's explicit pair.

There is intentionally no JSON production dependency. The runner is test-only infrastructure and
must not add Jackson, Gson, JSON-P, a new exported package, or a production JSON parser merely to
read its own test resource.

## Compatibility contract

- A *template-bearing* record has an inline `template` string, regardless of whether its expected
  outcome is `text` or `errorCategory`; hash-only/model-provenance records are not
  template-bearing. Every template-bearing record in `v1.jsonl` is rendered by hfjinja.
  `expected.text` compares exact Java `String` contents, including Unicode, whitespace, and line
  endings. `expected.errorCategory` compares only `HfJinjaException.category()`, exactly as the
  Node runner's reviewed pattern table normalizes upstream errors.
- A successful Java render for a record expecting an error, an `HfJinjaException` for a record
  expecting text, a non-`HfJinjaException` failure, malformed fixture, or an unconsumed record is
  a test failure labelled with the corpus id and physical JSONL line.
- The runner supplies no host functions. It constructs fresh `RenderOptions` per record with the
  fixed/default instant and zone and `RenderOptions.DEFAULT`'s finite render budgets; no mutable
  state is shared between records. The finite `RenderOptions.DEFAULT` budgets terminate ordinary
  runaway renders with a Java `RESOURCE_LIMIT` rather than an unbounded loop or oversized-output
  OOM. Also wrap each dynamic-test executable in JUnit's `assertTimeoutPreemptively` with a
  30-second duration as a diagnostic backstop for pathological slowness. It reports failure on
  expiry, but
  cannot itself kill an uninterruptible render; a budget/timeout failure is a Java safety failure,
  not a Node parity result. The corpus has no `RESOURCE_LIMIT` expectation because the reviewed Node
  error-pattern table has none.
- Parsing uses `TemplateOptions.DEFAULT`, whose public Javadoc documents it as matching upstream's
  public `Template` constructor. The schema deliberately has no template-options field. A
  source/token/AST-depth parse-budget failure is therefore a Java-only limit, not a mismatch.
- Time is deterministic: the clock is fixed to the record's explicit instant or the Node oracle's
  default instant, while `RenderOptions.zoneId()` is the record's explicit zone or `UTC`. The
  clock's zone is irrelevant — `strftime_now` reads `RenderOptions.zoneId()`.
- Locale asymmetry is deliberate: the Node oracle pins `Intl` and `localeCompare` to `en-US`, while
  hfjinja pins locale at its relevant implementation sites (`Locale.ROOT`/`Locale.US`), so the Java
  runner needs no process-locale override.
- The runner must understand the current public JSONL schema rather than silently accepting a
  convenient subset. In particular, it validates unique nonblank ids, object contexts, text versus
  hash-only exclusivity, the one-outcome `expected` object, `instant`/`zone` pairing, and rejects
  `globals` until the pinned public API supports it. Its behavior for malformed corpus input is a
  harness failure, never a Java-versus-Node parity result.
- Hash-only/model-provenance records remain outside normal Java execution, just as they are outside
  `nodeCorpusVerify`: report them as skipped and fail the harness if no template-bearing record ran.
  Do not fetch a model, materialize a template from a hash, or treat its hash/output as a Java
  rendering expectation in this slice.
- There is no exclusion list for template-bearing records: a failing record requires a Java fix or a
  reviewed corpus correction, never a skip.
- This runner supersedes the *future* requirement for a manually paired Java assertion per corpus
  row. Keep focused unit tests where they test a mechanism, location, Java-only safety contract,
  or a mutation not represented by a corpus result. Do not delete existing focused regressions as
  part of this infrastructure change.

## Implementation plan

1. **Add a test-only, schema-strict corpus reader.**

   Add a small package-private support class under `src/test/java/se/alipsa/hfjinja/` (for example
   `CorpusFixtures`). Its loader accepts a resource path or supplied content (not a hard-coded
   `v1.jsonl` path), reads UTF-8, retains each nonempty physical line number, and exposes immutable
   record data to the differential test. Split input with `\r?\n` and skip only
   `line.isEmpty()`; a whitespace-only line is JSON input and must fail, matching the Node reader.
   Keep parsed JSON values in ordinary Java shapes: `LinkedHashMap<String, Object>`, `List<Object>`,
   `String`, `Boolean`, `null`, and `Double`. `Values.fromHost` already converts these JSON-shaped
   context values under the documented Java/JS number contract.

   Implement a narrow recursive-descent JSON reader in that test support class rather than a regex
   extractor. It must cover objects, arrays, strings and all JSON escapes (including surrogate-pair
   `\\u` escapes), booleans, null, and JSON numbers with fraction/exponent forms; reject trailing
   data and give failures the resource path and physical line. Preserve object insertion order.
   Duplicate JSON object members follow `JSON.parse`'s last-member-wins behavior, so parse into a
   `LinkedHashMap` using `put`, not a duplicate-key rejection.

   Validate the complete current schema after parsing. Require the same fields and outcomes as
   `tools/corpus/corpus.mjs` for ordinary text records, including no unknown top-level or expected
   keys. Validate a hash-only record's SHA-256/provenance shape enough to distinguish it from a
   malformed text record, then mark it skipped; do not implement external fixture loading here.
   Make `tools/corpus/corpus.mjs` the single instant-grammar authority. Retain its `Date.parse`
   validity check and tighten its admission rule to
   `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?Z$`. Also require the parsed date's
   `toISOString()` to equal the input normalized to exactly three fractional digits: use the
   optional fractional capture, right-pad it to three digits (or use `000`), and replace the
   optional fractional part together with the `Z` suffix with `.<padded>Z`. This canonical round
   trip rejects `Date.parse` rollover inputs
   such as `2000-02-30T00:00:00Z` and `24:00:00Z`, so Node-valid corpus input is representable by
   Java. Have the Java reader require the same grammar before `Instant.parse`; do not add
   Java-only fractional-precision rules. The Node gate remains intentionally stricter than Java
   for a leap second, because only Node-admitted corpus input can reach Java. Define zones there
   as exact-case canonical IANA identifiers (plus `UTC`):
   use Node's canonical time-zone list rather than `Intl.DateTimeFormat`'s alias acceptance, and
   have Java require the same exact-case IANA/`UTC` form before `ZoneId.of`. This rejects aliases
   such as `utc` and Java-only fixed-offset forms such as `GMT+5`. Retain Node's requirement that
   either both fields appear or neither appears. `globals` must fail with an explicit “not supported
   by the pinned Template API” harness error, matching the Node schema rule. Give the support class
   focused tests for nested context conversion, escaped/newline and
   supplementary-Unicode strings, number forms, duplicate keys, physical line reporting, malformed
   schema, paired time fields, the instant grammar/canonicality rule (including rejection of a
   minute-only instant, rollover date, and `24:00:00Z`), exact-case IANA zone admission (including
   rejection of `utc` and `GMT+5`), a skipped hash-only record, and an all-hash-only input. Put
   malformed JSONL, `globals`, hash-only, and all-hash-only cases
   in `@TempDir` files (or supplied content), never in the checked-in corpus. State in the tests
   that today's checked-in corpus has no hash-only rows, so those paths are synthetic coverage.

   Add a checked-in no-time-fields record, Node-verified with the pinned oracle:

   ```json
   {"id":"self.strftime-default-instant-zone","source":"self-authored; verified against @huggingface/jinja 0.5.9","template":"{{ strftime_now('%Y-%m-%d %H:%M') }}","context":{},"expected":{"text":"2000-01-02 03:04"}}
   ```

   This binds the default instant and zone on both runners; the existing seven `strftime_now`
   records bind only their explicit values. The default instant's `:05` seconds are
   deliberately unobservable because `%S` is not in the pinned runtimes' supported directive set.

2. **Execute the corpus through the public Java boundary.**

   Add `CorpusDifferentialTest` in `src/test/java/se/alipsa/hfjinja/`, alongside
   `FormatDifferentialTest`, so it exercises the exported `Template` and `RenderOptions` APIs
   rather than reaching into `internal.runtime.Interpreter`. Use a JUnit `@TestFactory` (one named
   dynamic test per record) or an equivalently named parameterized test so an IDE and Gradle failure
   identifies both `id` and JSONL line. The dynamic-test-generation helper, not just the loader,
   accepts a resource path or supplied corpus content; the production `@TestFactory` binds it to
   `v1.jsonl`, while unit tests feed synthetic fixtures. Assert after loading that every
   template-bearing record became exactly one dynamic test and that at least one template-bearing
   record exists.

   For each record, create:

   ```java
   RenderOptions.builder()
       .clock(Clock.fixed(record.instantOrDefault(), ZoneOffset.UTC))
       .zoneId(record.zoneOrDefault())
       .build();
   ```

   The clock's zone deliberately remains `UTC`; `strftime_now` observes the `zoneId` option, not
   the clock's zone. Render with `Template.parse(record.template()).render(record.context(),
   options)`. For text outcomes,
   assert the exact expected text. For error outcomes, require an `HfJinjaException` and compare
   its `ErrorCategory`; include the actual category, message, and id/line in a mismatch failure.
   Do not compare Java messages in this generic runner—message exactness belongs to the feature
   regressions and the reviewed Node classifier/contract. An unexpected `RuntimeException` or
   `Error` must fail visibly as a Java harness/implementation failure, never be normalized to a
   category.

   Keep the runner deterministic and isolated: no Node subprocess, system-clock access, system
   default-zone access, locale mutation, static parsed-template cache, or parallel shared context.
   `Template.parse` and `render` stay inside each dynamic test so parser and renderer failures are
   attributed to the record that caused them. Wrap each dynamic-test executable in
   `assertTimeoutPreemptively` with a 30-second duration, rather than annotating the `@TestFactory`
   or using non-preemptive `assertTimeout`, to report pathological slowness at the record boundary.
   This is not an outer-process kill for code that ignores interruption; the finite render budgets
   are the termination mechanism for ordinary renderer paths.

3. **Make Java corpus execution a normal verification dependency.**

   `CorpusDifferentialTest` runs under Gradle's existing `test` task, which `check` already
   includes. Add a focused Gradle task only if it can run this class without executing the complete
   suite a second time; otherwise document the fast-feedback command as
   `./gradlew test --tests se.alipsa.hfjinja.CorpusDifferentialTest`. Do not create a second
   source set, launch a Java subprocess, or duplicate every Java test solely to obtain a task name.

   In the existing `tasks.withType(Test).configureEach` block, set
   `timeout = java.time.Duration.ofMinutes(5)` to give Gradle an outer task bound, matching the
   build script's existing fully qualified `java.time.Duration` style and avoiding an unnecessary
   import. If an uninterruptible, non-daemon test-worker thread outlives the per-record timeout,
   Gradle terminates the test task rather than leaving CI indefinitely blocked. Update `build.gradle`
   task inputs only if a new dedicated task is introduced. The ordinary `test` task
   already sees `src/test/resources`; a stale or changed `v1.jsonl` must invalidate its test inputs.
   Keep `nodeCorpusVerify` independent: it
   continues to prove expected values against the pinned upstream, while
   `CorpusDifferentialTest` proves hfjinja against those reviewed values. `check` must run both.

4. **Record the ownership change without overstating inventory completion.**

   Add an Unreleased `Added`/`Changed` entry to `CHANGELOG.md` stating that the checked-in
   differential corpus now runs against Java as well as the pinned Node oracle. Update
   `README.md`'s corpus-build description to say that the test build runs both the pinned Node
   oracle and the Java differential runner over `src/test/resources/corpus/v1.jsonl`. Leave dated
   plan documents unchanged as historical records, including the WP5 Slice 6 and WP7 Slice 2
   statements that correctly described the runner as absent at the time.

   Do not claim WP7 item 2 is complete. Extend `corpusCoverage`'s report wording, if needed, to
   distinguish two facts: all *committed* template-bearing records execute on both runtimes after
   this slice, while the converter still covers only its reviewed source subset. The next slice must broaden
   `convert-upstream-tests.mjs`/the manual-transcription ledger to cover every executable,
   non-model upstream vector and make that inventory report release-blocking. It must preserve this
   runner rather than restoring manual `InterpreterTest` mirrors.

5. **Falsify the runner and verify both sides.**

   Add corpus-fixture unit tests that temporarily or in-memory exercise these failure modes:

   - alter a text expected value; its dynamic Java case must fail with the id and physical line;
   - alter an error category; its case must fail with expected versus actual categories;
   - make an error-expecting record render successfully; its case must fail rather than being
     treated as a passing text outcome;
   - make the runner use the current clock for the no-time-fields `strftime_now` record; it must
     fail, proving the default instant binding rather than only explicit fields;
   - make the runner use `ZoneId.systemDefault()` with the fixed default clock for that record; it
     must fail, independently proving the default-zone binding;
   - omit one loaded record from dynamic-test creation; the loaded/executed-count assertion must
     fail;
   - feed a malformed JSONL/schema fixture and a `globals` fixture; both must fail as harness
     errors before rendering;
   - mutate a known Java behavior (for example have `renderText` return a wrong string for a
     normal scalar); a normal corpus record must fail without adding a hand-written assertion.

   Revert every mutation. Confirm JDK 21 and Node 26.7.0, then run:

   ```bash
   ./gradlew spotlessApply
   ./gradlew test --tests se.alipsa.hfjinja.CorpusDifferentialTest
   node --test tools/corpus/corpus.test.mjs
   node tools/corpus/run-node-oracle.mjs --corpus src/test/resources/corpus/v1.jsonl --patterns tools/corpus/error-patterns-0.5.9.json --lock upstream/upstream-lock.json
   ./gradlew corpusCoverage upstreamVerify
   ./gradlew check
   git diff --check
   ```

## Acceptance criteria

- Every template-bearing `v1.jsonl` record is a named Java test using the public API, with exact
  output or `ErrorCategory` comparison and source/id/line diagnostics.
- The Java runner supplies Node-equivalent deterministic time/zone values, uses the production
  default finite render budgets, and applies a finite timeout per corpus case.
- The test-only JSON/schema reader is complete for the repository's JSONL contract, rejects
  malformed/unrepresentable input loudly, uses the Node-owned canonical instant and exact-case
  IANA-zone grammar, and adds no production dependency or exported API.
- Hash-only records remain explicitly skipped by normal Java execution; dynamic-test generation
  accepts supplied content so all-hash-only corpus input fails as a harness configuration error.
- `check` executes both the Node oracle verifier and Java corpus test. A changed expected result,
  omitted record, category regression, or ordinary Java rendering regression fails the build.
- No template-bearing record can be excluded: its failure requires a Java fix or a reviewed corpus
  correction.
- The coverage report clearly distinguishes committed-record execution from the still-incomplete
  upstream-vector inventory.

## Deliberately deferred

- WP7 item 2's complete converter/manual-transcription inventory for every executable non-model
  vector and the release-blocking unsupported/exclusion report.
- Hash-only model-fixture materialization and execution; that remains gated by the corpus schema's
  approved external-fixture workflow.
- Remaining WP7 item 1 semantic work, including function-value rendering and the documented
  undefined-backed raw-upstream-error cases.
- Parser exact-message/end-of-input crash parity, publication/reproducibility work, and the final
  release checklist.
