# WP6 Slice 4 — Publication, Reproducibility, API Documentation, and Tokenizer Example

## Goal

Close WP6 item 4 from `req/implementation-plan.md`: validate module metadata, Maven publication,
reproducible artifacts, API documentation, and a minimal local tokenizer-config integration
example. This is release-hardening work; it does not change template-language semantics, the
pinned upstream version, retained fixture forms, or the public API surface.

## Scheduling

PR #22 completed the third WP6 slice: it revalidated the approved Qwen3.8 MLX fixture and NOTICE
record, while the preceding slices completed concurrent-rendering and resource-limit boundary
coverage. This is the next *remaining WP6* requirement, but it is deliberately deferred behind
WP7 feature-parity closure. Item 5 remains the final WP6 release checklist slice, and neither
slice can claim G6 or G7 is complete.

The current build already produces source and Javadoc JARs and declares a basic Maven publication,
but it does not yet prove byte-reproducible archives, complete publication metadata, usable API
documentation, or an end-to-end consumer path from a local `tokenizer_config.json` to a rendered
prompt.

## Feature-gap handoff: `Template.format()`

This slice must not hide the remaining public feature-equivalence gap. The pinned upstream
`Template` exposes `format(options?: { indent: string | number })`, implemented by
`upstream/vendor/src/format.ts` and covered by `upstream/vendor/test/format.test.js` plus the
format-preserves-rendering vector in `templates.test.js`. At the time this publication plan was
written, hfjinja exposed parse/render only; WP7 Slice 1 subsequently implemented formatting and
updated `upstream/mapping.yml`. This handoff is retained as the reason publication work was
deferred behind the feature-equivalence gap.

WP7 Slice 1 must port formatting as public API, after first designing an idiomatic Java
representation of the upstream string-or-number `indent` option. It must add Node-oracle vectors
for default tab, numeric indentation, string indentation, malformed/edge indentation values, and
parse-render-format-reparse rendering equivalence. Then it updates `Template`, Javadocs, README,
`mapping.yml`, the upstream lock/mapping evidence, and public API tests together. No public-API
exclusion is permitted.

The tokenizer-config example in this slice uses only rendering. It must state neither that
hfjinja implements all pinned upstream public methods nor that it reformats model templates.

## Scope and invariants

- Keep the published module name `se.alipsa.hfjinja`, exporting only
  `se.alipsa.hfjinja`. Do not export `internal.*` merely to simplify an example or test.
- Keep the main artifact dependency-free. Build-only and test-only dependencies remain outside the
  published runtime dependency graph.
- The example reads a caller-supplied local tokenizer config; it must not download from the Hub,
  add a JSON library, or vendor a model tokenizer/configuration file.
- Parse the `chat_template` value with a deliberately small local JSON-string extractor sufficient
  for the documented example, or add the example as a self-contained source file that uses only
  JDK APIs. Do not present this extractor as a general JSON parser. The library intentionally does
  not provide tokenizer-config parsing.
- Do not publish, sign, or release to an external repository in this slice. Validate locally with
  `publishToMavenLocal` or an isolated file repository only.
- Do not edit the version from `0.1.0-SNAPSHOT`; release version selection and credentialed
  publishing belong to the final release checklist.

## Implementation plan

1. **Audit and complete publication metadata.**

   Update `build.gradle`'s `MavenPublication` POM to contain the normal consumer-facing metadata:
   project URL, MIT license name and canonical URL, SCM connection/developer connection/URL,
   issue tracker if one is authoritative for this repository, and the project developer entry.
   Use the repository's canonical GitHub coordinates, not guessed organization or email details.
   Keep `from components.java`, `withSourcesJar()`, and `withJavadocJar()`; do not add production
   dependencies. Configure signing only when credentials/keys are explicitly supplied, so local
   verification never requires secrets.

   Add a Gradle verification task (for example `verifyPublicationMetadata`) that generates the POM
   and asserts the publication has exactly the expected GAV, packaging, name, description, license,
   URL, SCM, developer, and no runtime dependency entries. Make it consume the generated POM as a
   declared input/output rather than parsing mutable build-script state in an execution closure, so
   it remains compatible with Gradle's configuration cache. Wire it into `check`.

2. **Make every published archive explicitly reproducible and verify it.**

   Configure all archive-producing tasks (`jar`, `sourcesJar`, and `javadocJar`) with reproducible
   file order and without preserved timestamps. Do not rely on a Gradle default: this is a release
   contract and must remain explicit in the build script.

   Add a configuration-cache-safe `verifyReproducibleArchives` task that builds the three archives
   twice into separate, disposable build locations (or captures the first run's SHA-256 outside the
   task output and forces a clean archive rebuild), then compares each matching artifact byte for
   byte. Assert the main JAR contains `module-info.class`, contains public API classes, and contains
   no `se/alipsa/hfjinja/internal/` package classes exposed through module exports. Inspect the
   module descriptor with JDK tooling and assert its name is `se.alipsa.hfjinja` and its sole
   export is `se.alipsa.hfjinja`.

   Ensure the task does not use the Gradle `layout`/`project` model from a `doLast` closure. Resolve
   required paths/providers during configuration, following the existing `upstreamVerify` pattern.
   Wire it into `check`; leave actual repository publication independent of `check`.

3. **Turn public API documentation into a usable consumer contract.**

   Review every public type and public member under `src/main/java/se/alipsa/hfjinja/`. Expand its
   Javadoc to describe nullability, accepted host-value forms, default limits/options, exception
   categories, concurrency and buffering guarantees, and deterministic clock/zone behavior where
   applicable. In particular, document all `Template.parse`/`render` overloads, both options
   builders, `HostFunction` argument/return constraints, `SourceLocation` coordinate semantics,
   and the stability expectations of `ErrorCategory`.

   Enable strict Javadoc validation for the public module with warnings treated as errors where
   JDK 21 supports it, while excluding non-public implementation packages through the module's
   export boundary rather than suppressing warnings globally. Add a focused test/task assertion
   that the Javadoc JAR contains the public module's generated documentation and no build-path
   leakage. Fix documentation warnings in source; do not suppress them.

4. **Add a dependency-free, local tokenizer-config integration example.**

   Add a small, compilable Java example under a conventional example/documentation location (not
   `src/main/java`) that accepts a local `tokenizer_config.json` path, extracts its string-valued
   `chat_template`, parses it once, and renders a fixed `messages` context. The example must use
   `Template.parse` and `Template.render` exclusively through the public API, state that production
   clients should use their established JSON parser, and make no network requests.

   Include a tiny self-authored tokenizer-config fixture containing only an innocuous template and
   its expected rendered prompt. It must not contain a retained model template, weights, tokenizer
   data, or model-card text. Add a Gradle task/test that compiles and runs the example against the
   built JAR on the module path, then compares its stdout byte-for-byte with the expected output.
   This proves both normal Maven-classpath consumption and JPMS module use advertised in the README.

   Update README installation/quick-start material to link to the example, distinguish the
   library's template rendering from tokenizer-config JSON parsing, and give an accurate local
   command for running it. Keep the existing no-Hub/no-runtime-dependency promise explicit.

5. **Verify the locally publishable consumer artifact.**

   Run `publishToMavenLocal` with the snapshot version and create a minimal disposable consumer
   project under `build/` (or a JUnit/Gradle fixture) that resolves only
   `se.alipsa:hfjinja:<current-version>` from Maven Local. Compile and run it using the documented
   public API; assert it neither needs an `internal.*` import nor pulls a runtime dependency. Keep
   this verification fully local and clean up its generated files through Gradle, not source-tree
   mutations.

6. **Run the release-slice verification matrix.**

   Confirm `java -version` is JDK 21 and `node --version` equals the lockfile's `nodeVersion`.
   After Java/build-script edits, run:

   ```bash
   ./gradlew spotlessApply
   ./gradlew javadocJar verifyPublicationMetadata verifyReproducibleArchives
   ./gradlew publishToMavenLocal
   ./gradlew check
   git diff --check
   ```

   Run the build once without a configuration-cache entry and once again to confirm the cache is
   reused without configuration-cache problems. Record artifact SHA-256 values and the generated
   POM path in the PR description or commit notes, not as source-controlled release artifacts.

## Acceptance criteria

- `jar`, `sourcesJar`, and `javadocJar` are byte-identical across independent builds and have
  explicit reproducibility settings.
- The main JAR is a modular Java 21 artifact named `se.alipsa.hfjinja`, exporting only the public
  package; no runtime dependencies are published.
- The generated Maven POM has complete, reviewable metadata and local publication can be resolved
  by a clean consumer without credentials.
- Public Javadocs build without warnings and describe all public methods, configuration defaults,
  exceptions, value boundary, concurrency, buffering, and deterministic-time contracts.
- A self-authored local tokenizer-config fixture drives a public-API-only example to the exact
  documented prompt, with no Hub access or model text.
- `check`, focused publication/reproducibility/example tests, and a warm configuration-cache build
  pass on JDK 21 with the locked Node version.

## Deliberately deferred

- Clean-checkout build, offline dependency/license review, release checklist, release version
  selection, signing keys, and external publication (WP6 item 5).
- WP7's complete pinned-upstream vector inventory, remaining parity work, error-contract
  classifier expansion, and required `Template.format()` implementation. G7 remains the final
  release gate.
- A general tokenizer-config parser, Hub client, tokenizer implementation, model downloads, or
  any new retained model-template form.
