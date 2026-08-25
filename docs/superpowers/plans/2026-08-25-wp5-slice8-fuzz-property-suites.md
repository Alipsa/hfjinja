# WP5 Slice 8 — Deterministic Fuzz and Property Suites

## Goal

Close WP5 item 4: run bounded lexer/parser property and differential-fuzz suites under external
harness timeouts, and turn every confirmed Node-versus-Java mismatch into a small checked-in Java
regression before accepting a fix. This slice is test infrastructure and regression coverage; it
does not widen the template language, change the pinned upstream package, or add a production
dependency.

## Why this is the next slice

PR #18 closed WP5 item 5. Items 1–3 are already delivered by slices 1–6 (slices, call arguments,
macros/call/filter blocks, retained model goldens, and `strftime_now`). The remaining open WP5
item is therefore item 4. The project description requires fuzz/property tests specifically for
lexer/parser termination and says that differential mismatches must be retained as Java
regressions.

The repository currently has two useful but insufficient pieces:

- `LexerTest` and `ParserTest` have representative edge and deep-input cases, but no generated
  corpus or seed replay mechanism.
- `astSnapshotVerify` executes the pinned Node parser only for checked-in fixtures, then
  `AstSnapshotDifferentialTest` compares Java with the checked-in result. `nodeCorpusVerify` is
  also Node-only. Neither runs generated inputs through both implementations in one invocation.

Do not describe either Node-only task as a parity runner. The production code remains dependency
free; all new code belongs under `src/test` and `tools`.

## Scope and invariants

- Use a deterministic, documented seed set. A failure report must include the seed, generated
  case id, source encoded losslessly, parser options, and the smallest reproducible input.
- Exercise both valid grammar-shaped templates and hostile arbitrary source. Valid candidates can
  be compared structurally; arbitrary candidates have the termination/no-crash property only,
  because error wording and source spans are deliberately not a byte-for-byte oracle contract.
- Treat an external process timeout as a harness failure, never as a matching parse result. Keep
  individual cases bounded as well, so one pathological seed has an identifiable label.
- Do not use `assertTimeoutPreemptively` to solve a possible infinite parser loop: interrupting a
  parser thread can leave an uncooperative thread alive in the Gradle test JVM. Run the Java side
  in a separate process with a per-request wall-clock timeout.
- Keep generated cases out of `v1.jsonl`. That corpus is reviewed, source-attributed compatibility
  evidence. A fuzz seed becomes a permanent fixture only after it exposes a confirmed semantic
  mismatch.
- Render budgets are not part of this slice. Those have a distinct WP6 safety gate and must not
  turn parser-harness termination into `RESOURCE_LIMIT` parity.

## Implementation plan

1. **Define a compact, deterministic candidate protocol and generator.**

   Add `tools/fuzz/generate-parser-cases.mjs` plus a Node unit test. It accepts explicit
   `--seed`, `--count`, and `--max-source-code-units` arguments and writes newline-delimited
   records using only a stable protocol (case id, family, base64 UTF-16LE source, the two
   `TemplateOptions` booleans, and declared UTF-16 code-unit length). Do not use `Math.random`;
   implement a named small PRNG in the generator and version its algorithm in the output
   header/metadata. Seeds are unsigned 32-bit words; use `>>> 0` at every PRNG state/update
   boundary rather than signed `| 0` coercion.

   Generate two separately reported families:

   - Grammar-shaped templates composed from a depth-bounded expression/statement grammar already
     supported by the pinned parser: text, escaped strings, literals, arrays/objects/tuples,
     member and slice access, unary/binary/ternary expressions, filters/tests, `if`/`for`, `set`,
     macros, call/filter blocks, and whitespace-control delimiters. Parameter and recursion
     limits must be lower than the configured `TemplateOptions` limits so these are ordinary
     compatibility candidates, not budget probes.
   - Arbitrary hostile source, including delimiter fragments, unmatched quotes/brackets, comments,
     NUL, CR/LF combinations, non-BMP Unicode, isolated surrogate code units, and long repeated
     punctuation. Cap length at **512 UTF-16 code units**, every bracket/parenthesis, delimiter,
     and tag-prefix run at **32**, and total simultaneously unclosed delimiters at **64**; do not
     generate unbounded nesting or giant output. Keep syntactically generated
     expression/statement nesting at **16** or less. Those nesting caps are deliberately below
     Java's parser `maxAstDepth` 256; the hostile-family result rules below still handle a limit or
     stack outcome defensively rather than claiming source length alone proves it impossible.

   Pin a small smoke seed list in source and permit a CI-sized default count. Provide a locally
   selectable larger count, but keep `check` deterministic and fast: use seeds
   `0x5EEDC0DE`, `0xC0FFEE42`, and `0x13579BDF`, with **100 grammar-shaped and 100 hostile cases
   per seed**. The generator test must pin byte-for-byte output for `0x5EEDC0DE` in the checked-in
   `tools/fuzz/testdata/parser-cases-5eedc0de.ndjson` fixture (declared as a `fuzzParserTest`
   input), verify unique ids, unsigned-seed handling, bounds, lossless decoding, and that each
   family is nonempty.

2. **Build test-only runners with a normalized result protocol.**

   Add a Node runner beside the generator that parses every candidate with
   `upstream/vendor/dist/index.js`, after enforcing `upstream/upstream-lock.json`'s exact Node
   version. Its result for grammar-shaped candidates is a canonical AST serialization imported
   from the shared `tools/ast-snapshot/ast-serialize.mjs` module. Its result for arbitrary input
   is `PARSED`, `SYNTAX`, `LIMIT`, or `OTHER_ERROR`: emit `LIMIT` when the caught value is a Node
   `RangeError` (the pinned Node's stack-overflow type), and otherwise distinguish syntax by type,
   not by message. Do not classify arbitrary upstream error messages with the render-error
   classifier.
   Map the protocol booleans directly to upstream's `trim_blocks` and `lstrip_blocks` options;
   map them to `TemplateOptions.Builder.trimBlocks(...)` and `lstripBlocks(...)` on Java.

   Add a package-private Java test runner under
   `src/test/java/se/alipsa/hfjinja/internal/parser/` (the same package as package-private
   `AstSnapshot`). It reads the same line protocol, invokes
   `Lexer.tokenize` and `Parser.parse` with the supplied options, and emits the matching normalized
   result. Reuse `AstSnapshot.of(...)` rather than maintaining a second Java AST printer. Catch
   `TemplateSyntaxException`; for `TemplateRenderException`, inspect and report
   `ErrorCategory.RESOURCE_LIMIT` as the protocol's `LIMIT` result rather than catching all render
   exceptions as an expected parse result. A Java `StackOverflowError` is always a runner failure,
   never `LIMIT`: `Parser` should have hit its explicit depth guard first, so treating a bypass as
   a documented cross-runtime limit divergence would mask a defect. Allow any other throwable to
   fail the runner with its case id and an escaped source. The runner
   must not render templates or use `Template.render`.

   Use base64 of **UTF-16LE code units** rather than ad-hoc JSON parsing in Java. The Node side
   encodes with `Buffer.from(source, 'utf16le')`. The Java runner must reject an odd decoded-byte
   count as `HARNESS`, then assemble a `char[]` pair by pair, little-endian, and construct the
   source with `new String(chars)` — it must **not** call `new String(bytes, UTF_16LE)`, whose
   charset decoder replaces malformed surrogate sequences:

   ```java
   var chars = new char[bytes.length / 2];
   for (var index = 0; index < chars.length; index++)
     chars[index] = (char) ((bytes[2 * index] & 0xff) | ((bytes[2 * index + 1] & 0xff) << 8));
   var source = new String(chars);
   ```

   The runner then asserts that
   `source.length()` equals the record's declared UTF-16 code-unit length. Pin `A\uD800B` in the
   runner test: its decoded units must be `U+0041 U+D800 U+0042` and its length three. This keeps
   NUL, newlines, and unpaired surrogate code units lossless. The candidates are strings/code
   units, not arbitrary invalid UTF-8 byte streams; invalid byte sequences are deliberately
   outside this parser-input protocol.

   Extract the Node `emit` function from `tools/ast-snapshot/snapshot.mjs` into an exported shared
   `tools/ast-snapshot/ast-serialize.mjs`. Both the checked-in snapshot command and the Node fuzz
   runner import it. Do not add location filtering: upstream AST nodes do not contain source
   locations, and the current snapshot serializer already emits every property in insertion order.

3. **Add one external, bidirectional differential task.**

   Add `tools/fuzz/compare-parser-results.mjs` and a Gradle `fuzzParserVerify` `Exec` task. The
   script generates candidates, launches the Node runner and the Java test runner as child
   processes, pairs results by id, and fails with a replay command plus a minimized source when a
   grammar-shaped AST/result differs. The Gradle task must depend on compiled test classes and pass
   the Java 21 toolchain executable and `sourceSets.test.runtimeClasspath.asPath` to the comparator
   as explicit `--java` and `--java-classpath` arguments; the comparator passes those exact values
   to the Java child. Do not spawn bare `java`, infer `JAVA_HOME`, or launch nested Gradle: the
   machine default JVM may not be 21.

   Keep the Java and Node runners persistent for the comparator's lifetime, accepting one request
   at a time over stdin and returning one result over stdout; do not pay JVM startup for every
   reduction trial. The **15-second** limit is per request/idle interval, not per process. Before
   writing a request, the comparator records its case id as in-flight. If no matching response
   arrives in 15 seconds, it reports a `HARNESS` failure attributed to that exact id and kills the
   stalled child. The baseline run aborts on its first timeout after emitting that attributed
   failure; reduction trials likewise stop and report the original
   mismatch as `minimization=timeout` rather than continuing with an untrusted channel. A malformed
   runner output, duplicate/missing id, or nonzero runner exit is also a `HARNESS` failure with a
   nonzero task exit, not a parity mismatch. Give `fuzzParserVerify` a **120-second** Gradle
   `timeout`, consistent with the existing Node verification tasks.

   The reducer must use deterministic deletion passes with an explicit **30-second or 200-trial**
   budget, whichever is reached first. Budget exhaustion is not a harness failure: report the
   original unminimized mismatch with `minimization=budget-exhausted`. Reserve `HARNESS` for
   malformed reducer output, a reducer crash, or a reduction that no longer reproduces while being
   reported as minimized.

   A Node rejection of a grammar-shaped candidate is a generator **HARNESS** failure, not a parity
   mismatch: the grammar generator promised an upstream-valid candidate. Compare accepted
   grammar-shaped candidates exactly by normalized AST serialization. A Java `RESOURCE_LIMIT` for
   a grammar-shaped candidate is a generator or Java bug and therefore a failure; the hostile
   carve-out must not be applied to that family. For hostile candidates, first assert each
   implementation terminates; report an accept/reject mismatch only when neither side hit a
   documented parser-limit/stack outcome. Java
   `TemplateRenderException(RESOURCE_LIMIT)` and a Node `LIMIT`/`RangeError` stack-overflow outcome are
   documented non-parity outcomes for this family and are counted/reported separately, not compared
   as accept/reject. Preserve seed order and serialize the first failure deterministically so CI
   logs are reproducible.

4. **Promote confirmed discrepancies rather than weakening the fuzz oracle.**

   Run the initial smoke seed set against the pinned Node oracle. For each mismatch, first replay
   the minimized input against both runners and read the relevant vendored lexer/parser code. Then:

   - If Java is wrong, add a focused deterministic regression to `LexerTest`, `ParserTest`, or
     `AstSnapshotDifferentialTest` (and a checked-in snapshot fixture when AST shape is the
     contract) *before* changing Java production code.
   - If Node is wrong, toolchain-dependent, or the input is deliberately outside the supported
     protocol, record a narrowly justified exclusion keyed by seed/case and leave the candidate
     generator otherwise unchanged. Do not silently discard a seed or broaden `OTHER_ERROR`.
   - If a mismatch exposes an upstream behavior that Java deliberately diverges from, add a named
     Java regression documenting that decision and keep it out of the parity count. Such an
     exclusion needs an explicit rationale in the fuzz tool, not a comment only in a test method.
   - If Java is demonstrably wrong but the required correction is too large for this work package,
     add a seed/case-keyed known-defect exclusion with the minimized input and a filed follow-up
     reference. The report must list it prominently and CI must fail if its key no longer
     reproduces the recorded mismatch, so an exclusion cannot become a silent permanent waiver.

   The landing change must contain no unexplained baseline mismatch. The initial seed list and
   count are acceptance evidence, not a claim that random testing proves full compatibility.

5. **Wire verification and ledger evidence.**

   Register `fuzzParserTest` as a second Node `Exec` test task (or extend `nodeCorpusTest` to run
   both test files), and make `check` depend on it. Its inputs include the fuzz scripts and its
   timeout is **30 seconds**. Make `check` also depend on `fuzzParserVerify` after
   `nodeOracleVersion` and `upstreamVerify`. Include the generator, shared AST serializer, runners,
   comparator, lock, vendored parser entry point, and `sourceSets.test.runtimeClasspath` as task
   inputs. Also set `outputs.upToDateWhen { false }`, matching `nodeOracleVersion`, so a changed
   launcher/classpath cannot leave an old parity result trusted. In that configuration the inputs
   document the complete dependency surface rather than acting as the up-to-date mechanism. Make
   its marker/report an output under `build/reports/`. Add `ast-serialize.mjs` to
   `astSnapshotVerify` inputs as well, since that task now imports it.

   Add the new Java runner explicitly to both `lexer.ts` and `parser.ts` `tests:` lists in
   `upstream/mapping.yml` (keeping each one-line flow list). This is a mapping convention choice,
   not something `upstreamVerify` discovers automatically.

   Run the required JDK 21 and pinned Node 26.7.0 checks, then:

   ```bash
   ./gradlew spotlessApply
   ./gradlew fuzzParserVerify
   ./gradlew check
   git diff --check
   ```

   Record the exact seed set, case count, timeout values, and any justified exclusions in the
   generated report so a future upstream sync can replay it without guessing.

## Acceptance criteria

- A deterministic grammar-shaped and hostile-source suite exercises both pinned Node and Java
  lexer/parser paths, with reproducible seeds and lossless failing sources.
- Both subprocesses and the enclosing Gradle task have external wall-clock limits; timeout is a
  harness failure, never evidence of compatibility.
- Grammar-shaped candidates compare canonical AST output; a Java parser limit there is a failure.
  Hostile inputs require termination and consistent accept/reject behavior except for separately
  reported Java `RESOURCE_LIMIT` and Node stack-overflow outcomes, which are not parity outcomes.
- The build has no unexplained initial-seed mismatch. Every confirmed Java defect is captured by a
  small permanent regression before the implementation changes that fix it, or has a prominently
  reported, seed-keyed known-defect exclusion with a minimized input and filed follow-up.
- `./gradlew check` runs the suite successfully with JDK 21 and the locked Node version.

## Deliberately deferred

- Render/output fuzzing, resource-budget stress, concurrent-render testing, and retention checks:
  WP6 owns those safety/release concerns.
- A general Java JSONL corpus runner or importing retained model resources into `v1.jsonl`; those
  remain separate schema/harness work called out by WP1b and WP5 slice 4/5.
- New syntax, new upstream versions, or changing intentionally documented semantic divergences.
