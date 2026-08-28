# Release checklist

- [ ] Run `./gradlew clean check` with JDK 21 and the Node version in `upstream/upstream-lock.json`.
- [ ] Run `./gradlew --offline check` after dependencies have been resolved locally.
- [ ] Build the JAR twice from clean build directories and compare SHA-256 checksums.
- [ ] Run `./gradlew publishToMavenLocal` and compile the tokenizer-config example against the local artifact.
- [ ] Review `NOTICE`, `req/model-fixture-policy.md`, `CHANGELOG.md`, public Javadoc, and the generated POM.
- [ ] Review dependency updates with `./gradlew dependencyUpdates`; do not upgrade the pinned Node oracle implicitly.
- [ ] Confirm `build/reports/corpus-coverage.md` contains source, runtime-surface, and error-family evidence.
