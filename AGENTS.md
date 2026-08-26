# Repository guidance

## Build toolchain

- The Gradle build requires **JDK 21** (`build.gradle` toolchain). Spotless bundles
  `google-java-format` 1.28.0, which refuses to run on JVM 25+, so a newer default JDK fails at
  `spotlessJava` with a `Cannot fingerprint input property 'stepsInternalEquality'` error rather
  than a readable version message.
- The Node oracle requires the **exact** version in `upstream/upstream-lock.json` (`nodeVersion`),
  which `.nvmrc` already tracks. Run `nvm use` in the repo root; a mismatch fails
  `:nodeOracleVersion`, which blocks `corpusCoverage`, `upstreamVerify`, and `check`.
- Both failures look like build breakage rather than environment drift. Before concluding a change
  is at fault, confirm `java -version` is 21 and `node --version` matches the lock.
- Run `./gradlew spotlessApply` after editing Java sources, before `check` — `google-java-format`
  reflows Javadoc differently than hand-written wrapping.

## Generated documentation

- When generating Javadoc manually, write it only under `build/` or a temporary directory; never
  use the repository root as the output directory.

## Releases and changelog

- Maintain [`CHANGELOG.md`](CHANGELOG.md) for every notable user-facing change. Its format is
  based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), using an `Unreleased` section
  and the applicable change categories (`Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, and
  `Security`).
- The project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). The initial
  release version is `0.5.0`; use ISO 8601 release dates when a version is published.

## Pull requests

- Create pull requests ready for review by default; use drafts only when explicitly requested.
- Prefer work-package-sized pull requests over one pull request per numbered plan step. Keep
  commits focused and verify incrementally, but combine cohesive, low-risk work so review latency
  does not dominate delivery time. Split only when a change is independently reviewable, risky, or
  needs an earlier decision.

## Code review

- Review diffs and pull requests inline, reading the changed code directly. Use the `/code-review`
  skill only as a parallel cross-check, never as the primary review or as a substitute for reading
  the diff yourself.
- Treat a review agent's findings as claims to verify, not results to relay. Confirm a claimed
  missing test by mutating the code and running the suite; confirm a claimed parity gap against the
  pinned Node oracle before reporting it.
