# WP5 Slice 6 — Deterministic `strftime_now` Runtime Coverage

## Objective and boundary

Close WP5 item 2 for the only date/format runtime path reachable from the pinned
`@huggingface/jinja` 0.5.9 interpreter: `strftime_now`. The pinned runtime registers this global
from `utils.ts`; its formatter recognizes exactly `%Y`, `%m`, `%d`, `%b`, `%B`, `%H`, `%M`, and
`%%`. The Java implementation already contains these directives, but its behaviour is only partly
covered by an inline interpreter test. This slice makes that port explicit and deterministic. The
Node oracle and Java tests are separate suites: expected text is first verified against Node, then
transcribed into Java assertions; there is no Java-side JSONL corpus runner.

This is deliberately not a port of `src/format.ts` / `Template.format()`. The ledger records it as
reviewed-no-port-impact: no hfjinja runtime path exposes or calls it. It is also not a broad port
of unrelated `utils.ts` exports (`titleCase` and `replace`): they are not globals registered by
upstream `setupGlobals` and no mapped runtime path needs them. Keep the ledger truthful instead of
treating a source-file responsibility as evidence that every utility export is available.

R3 intentionally differs from upstream's ambient implementation: hfjinja requires a
caller-supplied `Clock` and `ZoneId`, and fixed C/POSIX English month names regardless of JVM
locale. Missing time options must remain a categorized hfjinja failure; never read the wall clock,
host zone, or default locale for parity.

## Upstream inventory

`upstream/vendor/src/utils.ts:66-102` implements `strftime_now(format)` as `strftime(new Date(),
format)`. Its regex replacement makes only these two-character tokens special:

| Token | Required result |
| --- | --- |
| `%Y` | local calendar year, ordinary JavaScript year string |
| `%m`, `%d`, `%H`, `%M` | two ASCII digits |
| `%b` | `Jan` … `Dec` |
| `%B` | `January` … `December` |
| `%%` | `%` |

All other `%x` pairs, a terminal `%`, NULs, and ordinary Unicode are literal. The global accepts
extra positional arguments because upstream consumes only its first JavaScript parameter. It must
reject a missing/non-string first argument through the existing stable error boundary.

The oracle fixes `Date`, `TZ`, and `Intl.DateTimeFormat` per corpus record. The Java port must use
the supplied instant and `ZoneId` before taking date fields; a date-boundary vector must prove the
zone conversion happens before formatting.

## Implementation steps

1. **Extend the existing coverage before changing production code.**

   The existing `InterpreterTest` already pins locale invariance, ignored extra arguments, and an
   embedded-NUL/`%%` case. Extend it and add self-authored Node-oracle text records for the missing
   coverage: `%b`/`%B`, an unknown `%x`, terminal `%`, non-ASCII literal text, and a date-boundary
   case using instant `2026-01-01T00:30:00Z` plus zone `America/Los_Angeles` (date fields must be
   2025-12-31 16:30). Give *every* fixed-UTC record explicit `instant` and `zone` fields rather
   than relying on the oracle defaults; the corpus schema requires them as a pair.

   Include a `%Y|%%Y|%%%Y|%q%%|%` literal vector. The Java loop consumes the character after `%`,
   whereas upstream's regex leaves an unrecognized pair untouched; their outputs are equivalent
   because the consumed unknown second character cannot itself begin a recognized directive. Pin
   this instead of claiming the scan mechanics are identical.

   Add Java-only assertions for `strftime_now()` (missing first argument), missing clock, missing
   zone, a present non-string `strftime_now(none)`, and a present undefined-backed string
   `strftime_now(x.missing)`. Each Java test constructs its own `RenderOptions` with a fixed
   `Clock`/`ZoneId` matching its corpus record; JSONL `instant`/`zone` fields never reach Java.
   The non-string/undefined-backed category assertions are intentionally red until step 2 changes
   production validation. Do not add error corpus records: upstream gives all three argument
   shapes the same unmatched TypeError message, so category parity would be unobservable there.

2. **Make the deterministic formatter a named internal unit.**

   Extract the directive loop from `Interpreter.strftime` into `public final`
   `internal.util.PosixStrftime`, accepting `ZonedDateTime` and a format string. It must be public
   because `Interpreter` is in `internal.runtime`; it remains outside hfjinja's public API because
   `module-info.java` exports only `se.alipsa.hfjinja`. Match `JsSlice`'s complete class/method
   Javadoc because the build produces a Javadoc JAR. Keep fixed month-name tables and numeric
   padding under `Locale.ROOT`; do not use `DateTimeFormatter`, JVM defaults, or a production
   dependency. `Interpreter` remains responsible for argument validation, `Clock`/`ZoneId`
   presence, conversion to `ZonedDateTime`, and error categories.

   Preserve output semantics: recognized tokens are replaced, unknown `%x` and terminal `%` are
   literal, and the `%`-edge vector above remains green. Use explicitly enumerated C/POSIX English
   month names rather than a locale formatter, so JDK locale data cannot change fixtures.

   Split `Interpreter.strftime` validation before extraction: first test `arguments.size()` so an
   absent first argument is `ARITY`; only then inspect `argument(arguments, 0)`. That helper
   deliberately collapses an absent argument, `NullValue`, and an undefined-backed string into
   `UndefinedValue`, so branching on its result alone cannot implement this policy. Every present
   nullish or non-string first value is `TYPE`, matching the public `ErrorCategory` taxonomy.
   Upstream gives absent, null, and undefined-backed cases the same unmatched TypeError, so this is
   a deliberate hfjinja category decision; retain a nearby code comment explaining why it is not
   represented as an oracle error record.

3. **Wire and record the completed runtime path.**

   Replace the inline loop with the utility after `ZonedDateTime.now(clock).withZoneSameInstant(zone)`.
   Keep first-argument and missing-options errors, and continue ignoring extras. The
   `utils.ts` mapping comment already states that the reachable `range`/`strftime_now`/`slice`
   paths are ported, so preserve rather than duplicate it. Add `PosixStrftime` and its test to the
   `java:`/`tests:` ledger lists for consistency with `JsSlice`, even though verification only
   requires listed paths to exist. Keep each inline YAML list on one physical line: the mapping
   parser is line-based and wrapped entries would be silently omitted from its check.

4. **Verify the gate evidence.**

   Run `./gradlew spotlessApply`, optionally `./gradlew nodeCorpusVerify` for fast oracle feedback,
   then `./gradlew check`. `check` already depends on both `nodeCorpusVerify` and `upstreamVerify`;
   the latter checks provenance, lock, and mapping state, not a Java corpus runner (none exists).
   Confirm the locked Node version, every new text record, and the independent Java
   `RenderOptions` assertions pass. Review `git diff --check` and the mapping ledger before
   handoff.

## Acceptance criteria

- `strftime_now` has byte-exact coverage for all eight upstream tokens and literal preservation.
- Date fields use the supplied `Clock` converted into the supplied `ZoneId`, including a
  cross-date-boundary vector.
- Formatting is invariant under JVM locale changes and uses fixed C/POSIX English month names.
- Missing first argument is `ARITY`; every present nullish/non-string format is `TYPE`; missing
  clock or zone is `VALUE`. No ambient clock, zone, or locale is read.
- Extra arguments remain ignored; `format.ts`, `titleCase`, and `replace` remain out of scope.
- `./gradlew check` passes.

## Deliberately deferred

- A public template formatter or port of upstream `format.ts`.
- Additional `utils.ts` exports not reachable from `setupGlobals`.
- WP5 items 4–5: property/fuzz suites and removal of remaining ledger exemptions.
