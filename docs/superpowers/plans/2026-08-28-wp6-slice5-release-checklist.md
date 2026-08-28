# WP6 Slice 5 — Clean-Checkout Release Verification

## Goal

Close WP6 item 5 from `req/implementation-plan.md`: produce repeatable, reviewable evidence that
hfjinja can be released from a clean checkout under its pinned toolchain. This slice validates the
existing build, provenance, publication metadata, dependency report, notices, and consumer path;
it does not change runtime semantics, select a release version, sign artifacts, create a tag, or
publish to an external repository.

## Evidence and scope

The preceding WP6 slices have already added concurrent-rendering and resource-limit coverage,
revalidated the approved model-fixture policy, and configured reproducible archive flags, POM
metadata, and a local tokenizer-config example. They did **not** implement archive-reproducibility
or publication-metadata verification tasks; this slice must add those focused checks. WP7 is also
now backed by a release-blocking corpus report: it generates 155 serializable
`templates.test.js` fixtures (from 159 definitions with four explicit JavaScript-function-context
schema exclusions) and 16 interpreter whitespace vectors, executes every
template-bearing corpus record against both the pinned Node oracle and public Java API, and reports
runtime-surface/error-family evidence.

What remains is the final checklist in `req/release-checklist.md`. Its current Markdown boxes are
useful reminders but do not preserve command output, artifact identities, clean-checkout isolation,
or the exact dependency-review result. A release candidate must have those facts in an auditable
report generated from a declared contract and recorded execution-time evidence. The check must remain offline after its explicitly
documented dependency-preparation phase, and it must not treat a developer's normal `~/.gradle`,
Maven Local, current build directory, or local Git worktree as clean-room proof.

The candidate verified by this slice is the current `0.5.0-SNAPSHOT` coordinate, not the future
`0.5.0` release coordinate advertised as a release target. Use JDK 21 and the exact Node version
in `upstream/upstream-lock.json` (currently Node 26.7.0).
Keep Gradle configuration cache compatibility: resolve paths/providers during configuration and do
not retain Gradle model objects in task execution closures. Generated reports and temporary
worktrees belong under `build/` or `/tmp`, never at repository root. The sole exception is the
ignored, reusable `<source-checkout>/.gradle-user/release-verification` cache described below.

## Implementation plan

1. **Define a release-verification manifest and report format before running checks.**

   Add a checked-in, machine-readable manifest under `req/` (or a narrowly named build input)
   describing the immutable release verification contract: required JDK major, exact expected
   bytecode major (65), lock-derived Node version, the source snapshot Maven coordinates/module
   name, the sole exported package (`se.alipsa.hfjinja`), an empty published-runtime dependency
   set, the three release archives, required Gradle verification tasks, and the approved policy
   exclusions to surface in the final report. Derive
   the Node version, upstream package/version, and vendored integrity from
   `upstream/upstream-lock.json`; do not duplicate those values as hand-maintained constants.

   Add a `releaseVerification` task that writes `build/reports/release-verification.md` and a
   companion JSON result. Its Gradle-declared inputs are only stable checked-in contract files:
   the manifest, lock, `NOTICE`, model-fixture policy, changelog, and release checklist. Candidate
   POMs, archives, dependency reports, corpus coverage, and task markers live in the worktree
   created at execution time, so record them as execution-time facts with their paths and SHA-256
   values rather than pretending they are configuration-time inputs. The report records command/
   tool versions (including exact JDK vendor/build), Git HEAD and dirty-state result, archive names/
   SHA-256 values, module descriptor/export check, generated POM result, corpus source/runtime/
   error evidence summary, and every checklist item with a pass/fail/not-applicable result.

   This task reports execution-time facts, so configure `outputs.upToDateWhen { false }` and
   `outputs.cacheIf { false }`. Configuration cache remains supported; disabling its build-output
   reuse is specifically to prevent a prior report from being presented as fresh. Never commit
   generated release evidence or credentials.

2. **Make clean-checkout and offline validation isolated and reproducible.**

   Implement a platform-portable Java 21 `ReleaseVerifierMain` under `src/test/java` and invoke it
   through a configuration-cache-safe `JavaExec` Gradle task. This location is deliberate:
   Gradle's current test outputs use the test classpath, not the module path, so
   `sourceSets.test.runtimeClasspath` needs neither `--patch-module` nor a new export. Name the
   main class outside JUnit discovery conventions and give its separately named unit tests the
   normal `*Test` suffix. Give its process/environment adapter injectable paths, command outputs,
   JDK/Node values, clock, and Git state so error paths can be tested hermetically. It may select
   the repository wrapper appropriate to the host (`gradlew` or `gradlew.bat`), but must not
   require a shell script or Node workflow beyond the existing pinned oracle tasks.

   Before creating a detached worktree, run `git status --porcelain` in the *source checkout* and
   hard-fail for any tracked modification or untracked file. This makes `HEAD` genuinely describe
   the candidate; consequently the plan document and all slice changes must be committed before a
   successful release verification is attempted. Support an explicit `--allow-dirty` development
   mode that still records the complete status and stamps both report formats `NOT A RELEASE
   CANDIDATE`; it must never turn a dirty tree into a passing release result. Create the disposable
   detached worktree outside `build/` (under a validated, uniquely named `java.io.tmpdir` child),
   never where `clean` can delete it. On both success and failure use explicit
   `git worktree remove --force <exact-path>` followed by `git worktree prune`; do not use recursive
   deletion of a broad directory.

   The order is fixed: source-checkout status gate, detached worktree creation, online preparation
   *in that worktree*, then the offline matrix in the same worktree. Use
   `<source-checkout>/.gradle-user/release-verification` as the reusable task-specific Gradle user
   home; `.gradle-user/` is already ignored, so it cannot trip the source dirty-state gate and the
   cache survives disposal of each worktree. The online
   preparation phase runs the same candidate matrix once against that isolated user home, so plugin
   markers, Spotless/google-java-format, CodeNarc, test dependencies, and all project dependencies
   are available. Require a pre-installed JDK 21 and disable/avoid toolchain auto-provisioning; a
   cold toolchain download is not permitted in the later offline run. Every nested Gradle command
   receives this exact `--gradle-user-home` path; every archive/build candidate command after
   preparation also receives `--offline`, and the process adapter fails before launch if either
   required flag is absent. The separately configured sole-file-repository consumer invocation is
   the only exception to `--offline`, as specified in step 3. Then run the actual candidate matrix
   in the isolated checkout.

   The candidate matrix runs in this order: release-only archive reproducibility verifier,
   `clean check` (including the cheap publication-metadata and module-descriptor checks), then the
   dependency review, then the isolated consumer check. The first three steps run with `--offline`;
   running the two destructive archive rebuilds first ensures the final `clean check` recreates the
   POM, corpus coverage report, markers, and other evidence the report records. `upstreamVerify`,
   `formatGoldenVerify`, and `corpusCoverage` are enumerated in the report as individual statuses
   because `check` already invokes them; do not invoke them again. Capture exit status and concise
   output paths in the report. A missing cached dependency during these offline steps is a release-
   verification failure with an actionable preparation instruction, never a reason to silently drop
   `--offline`.

3. **Prove artifacts and local consumption from the isolated candidate.**

   Add the missing focused `verifyPublicationMetadata` task and wire it into `check`; it generates
   and parses the POM and verifies every applicable manifest expectation. Add a separate cheap
   `verifyModuleDescriptor` task to `check`, so bytecode major and the sole public export are
   caught during ordinary development as well as by the release archive check. Add
   `verifyReproducibleArchives` as a **release-only** `JavaExec` task backed by a separate
   `ArchiveReproducibilityMain`; `ReleaseVerifierMain` invokes that Gradle target once in the
   detached worktree, and it is not a dependency of `check` or CI's ordinary `./gradlew check`.
   `ArchiveReproducibilityMain` performs two independent, nested
   `gradlew clean jar sourcesJar javadocJar` invocations, copies each invocation's three artifacts
   to separately validated temporary evidence directories, and compares their SHA-256 values after
   the second clean build. This names the required double-build mechanism without trying to make
   one Gradle task instantiate archive tasks twice. It also inspects the main JAR/module
   descriptor. The comparison is explicitly scoped to
   repeated builds with the same exact JDK vendor/build, which the report records: Javadoc JAR
   contents are not promised identical across arbitrary JDK 21 patch releases. Inspect the main
   JAR with JDK tooling to confirm bytecode major 65, module name
   `se.alipsa.hfjinja`, and only the public package export.

   Add a named `releaseVerificationRepository` Maven repository target to `publishing`, whose URL
   is supplied as a fresh isolated task/property path for each candidate. Publish the unmodified
   source `0.5.0-SNAPSHOT` coordinate there, invoke its corresponding publication task rather than
   `publishToMavenLocal`, and record that coordinate. Compile and run a
   disposable consumer that resolves solely the produced coordinate, uses the documented public
   API, and runs the tokenizer-config example fixture. Do not rely on Gradle offline-mode behavior
   for a freshly published custom file repository. Instead, make this separately invoked consumer
   build declare **exactly one** dependency repository: the candidate's file-backed
   `releaseVerificationRepository`; it has no `mavenCentral`, Maven Local, or other dependency
   repository. Invoke it through the already-cached **candidate worktree's** Gradle wrapper with
   the same isolated `--gradle-user-home`, rather than a consumer-owned wrapper. It may apply only
   core Gradle plugins. Its `settings.gradle` must explicitly contain an empty
   `pluginManagement { repositories { } }` block: an absent block falls back to the Gradle Plugin
   Portal and is a verifier failure, not an equivalent configuration. Toolchain auto-provisioning
   is disabled and it uses the already verified JDK 21. Together, those constraints are the
   consumer's structural no-network guarantee, so the fresh candidate can be resolved without
   assuming it is already in Gradle's module cache. Assert and record its complete dependency and
   plugin repository lists, wrapper/distribution identity, Gradle user-home path, and
   toolchain-provisioning state in the report; add fixtures that prove a second repository, absent
   or non-empty plugin-management repositories, or a provisionable toolchain configuration makes
   the verifier fail. Configure zero changing- and dynamic-module cache TTLs as a second safeguard.

   Compare the main-JAR digest pairwise across the first reproducibility build, second
   reproducibility build, freshly published repository artifact, and consumer-resolved artifact;
   all four must match. Sources and Javadoc JAR identity is established only between the two
   reproducibility builds, because the consumer does not resolve them. Verify its resolved runtime
   graph contains no runtime dependencies and that no `internal.*` import or classpath leakage is
   required. Do not call Sonatype/GitHub release APIs, use signing keys, or modify the user Maven
   repository.

4. **Make dependency, licence, and public-document review explicit.**

   In the online preparation phase, first prove whether `dependencyUpdates` 0.61.0 includes the
   three `plugins {}` markers in its output. Do not assume it does. If it does, retain the captured
   plugin evidence; if it does not, generate a separate resolved-plugin-coordinate inventory and
   require plugin-version availability as an explicit manual sign-off item. Then run
   `dependencyUpdates` against the isolated Gradle user home and stage its timestamped report in a
   validated per-run temporary evidence directory outside the worktree's `build/` tree. The offline
   matrix's explicit fourth `dependencyReview` step consumes that path as a supplied task input;
   it is not a normal `check` dependency and must not run an upgrade scan or any live resolution.
   This configuration-cache-safe task/report examines the resolved build/test/plugin dependency
   graphs, the staged online scan, and the generated POM. It must distinguish build/test dependencies
   from published runtime dependencies, report available upgrades without applying any, and fail if
   the runtime POM gains a dependency. It must explicitly state that the Node oracle is lock-governed
   and not upgraded by this review.

   Have `releaseVerification` validate the required manual-review inputs by digest and path:
   `NOTICE`, `req/model-fixture-policy.md`, `CHANGELOG.md`, generated public Javadoc, generated
   POM, and `build/reports/corpus-coverage.md`. Surface all policy-excluded upstream sources and
   corpus schema exclusions verbatim by identifier/reason. It must mark human approval as pending
   rather than pretending a checksum replaces legal or release-manager review.

5. **Update developer-facing release instructions without claiming a release.**

   Replace the bare checklist commands in `req/release-checklist.md` with the precise
   preparation/isolated-verification invocation, expected report locations, and a short final
   human sign-off section for notices, changelog/version/date, generated POM/Javadoc, dependency
   report, and external publication authority. Replace `publishToMavenLocal` with the isolated
   repository task. Retain unchecked sign-off boxes until a release
   manager actually completes them. Add a concise README maintainer note only if needed to point
   release maintainers to the checklist; do not alter consumer installation instructions or the
   `0.5.0` release status.

   Add an Unreleased `Changed` changelog entry for the new release-verification evidence and
   offline clean-checkout workflow. Do not add a release date or change the project version in this
   slice.

6. **Test the verifier’s failure modes and run the release matrix.**

   Add focused tests (or hermetic Java process-adapter fixtures) that falsify: a dirty source
   checkout, wrong JDK/Node,
   lock mismatch, an absent offline dependency, non-identical second archive, an incorrect module
   export, a generated POM runtime dependency, a consumer repository list containing any second
   entry, consumer plugin-management repository or provisionable toolchain configuration, a missing
   required `--offline` or `--gradle-user-home` flag that fails before process launch, a main-JAR
   digest mismatch between publication and consumer resolution, a missing corpus-coverage category,
   and a missing required review input. Each must fail the corresponding report item with its exact
   diagnostic; do not test success only.

   Confirm the actual JDK and lock-derived Node version before the final run. Then execute the
   isolated preparation phase, the isolated `--offline` candidate matrix, local consumer check,
   `./gradlew check` from the primary checkout, and `git diff --check`. Confirm only that
   `releaseVerification` is configuration-cache compatible with two `releaseVerification --dry-run`
   invocations; this configuration-only probe must not re-run the heavyweight matrix and does not
   claim that a real matrix execution reuses the dry-run entry. Retain only reports under `build/`;
   attach their paths and artifact digests to the review/PR description.

## Acceptance criteria

- A clean detached checkout of the exact candidate commit passes its archive/build matrix offline
  after an explicit, bounded dependency-preparation phase; the fresh-candidate consumer check is
  separately network-isolated by its sole file-backed repository.
- A generated report gives reproducible evidence for toolchain/lock provenance, build status,
  archive hashes, module/publication shape, local consumer use, dependency graph, corpus coverage,
  and required human review inputs.
- The three release archives are byte-identical across two independently cleaned builds in one
  isolated candidate worktree using one exact JDK vendor/build; the candidate POM has no runtime
  dependencies and the published module exports only `se.alipsa.hfjinja`.
- The release checklist tells maintainers how to run and interpret the verification, while release
  versioning, signing, tagging, and external publication remain explicit human-authorized actions.
- Failure tests prove that stale provenance, environment drift, offline leakage, changed artifacts,
  and incomplete evidence cannot be reported as a passing candidate.

## Deliberately deferred

- Choosing the `0.5.0` release date/version, signing, tag creation, GitHub release creation, and
  publication to an external Maven repository; these require release-manager authority.
- Any change to the pinned upstream package, Node version, vendored source, model-template form,
  normal rendering semantics, or public API.
