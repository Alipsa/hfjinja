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
of unrelated `utils.ts` exports (`titleCase` and `replace`). Neither is a global registered by
upstream `setupGlobals` — that part of the boundary is real — but that is not why they are out of
scope here: `runtime.ts` does consume both (`titleCase` backs the `title` string builtin; `replace`
backs the `replace` string builtin and filter). They are unreachable through hfjinja's template
surface today because hfjinja has not ported those `title`/`replace` builtins, not because upstream
lacks a caller for them. Keep the ledger truthful about *that* reason (no hfjinja caller exists
yet) instead of treating a source-file responsibility as evidence that every utility export is
available.

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

`%b`/`%B` parity between the ICU-backed oracle and the hard-coded C/POSIX month tables below is not
locale-independent by accident: it holds specifically because `run-node-oracle.mjs` pins
`defaultLocale = 'en-US'`, whose `Intl.DateTimeFormat` short/long month names are byte-identical to
the English abbreviations hard-coded here. A different oracle locale would desynchronize these
fixtures without any Java change; this plan does not need to guard against that, but the fixture's
stability rests on that pinned locale, not on the tokens being locale-agnostic upstream.

## Implementation steps

1. **Extend the existing coverage before changing production code.**

   The existing `InterpreterTest` already pins locale invariance, ignored extra arguments, and an
   embedded-NUL/`%%` case. Extend it and add self-authored Node-oracle text records for the missing
   coverage — `%b`/`%B`, an unknown `%x`, terminal `%`, non-ASCII literal text, and a date-boundary
   case using template `{{ strftime_now('%Y-%m-%d %H:%M') }}`, instant `2026-01-01T00:30:00Z`, zone
   `America/Los_Angeles`, and expected text `2025-12-31 16:30` — to
   `src/test/resources/corpus/v1.jsonl`, which today has zero `strftime_now`
   records (the only existing one is the inline fixture at `tools/corpus/corpus.test.mjs:119`,
   which exercises the oracle runner's fixed-UTC-clock fallback when a text record omits time
   fields, not a Java-verified record). Follow the file's existing hand-written conventions
   exactly: prefix new `id`s with `self.` (e.g. `self.strftime-month-names`), not `templates.` —
   `corpusCoverage` runs `convert-upstream-tests.mjs --check`, which fails the build on any
   `templates.`-prefixed id it did not itself generate from `templates.test.js` — and set `source`
   to the fixed string `"self-authored; verified against @huggingface/jinja 0.5.9"`, matching all
   23 existing hand-written records. Give *every* fixed-UTC record explicit `instant` and `zone`
   fields rather than relying on the oracle defaults; the corpus schema requires them as a pair.

   Include a `%Y|%%Y|%%%Y|%q%%|%` literal vector using the same fixed instant as the existing
   `InterpreterTest` fixture, `2026-08-21T09:05:00Z` in zone `UTC` — expected text
   `2026|%Y|%2026|%q%|%` — rather than leaving the instant to the blanket "every fixed-UTC record
   gets explicit `instant`/`zone`" rule; naming it here makes this record as reproducible from the
   plan alone as the date-boundary record above. The Java loop consumes the character after `%`,
   whereas upstream's regex leaves an unrecognized pair untouched; their outputs are equivalent
   because the consumed unknown second character cannot itself begin a recognized directive. Pin
   this instead of claiming the scan mechanics are identical.

   Between these records, every one of the eight upstream tokens has an oracle-verified carrier:
   the literal vector above covers `%Y` and `%%`; the month-names record covers `%b`/`%B`; the
   date-boundary record's `%Y-%m-%d %H:%M` template is what covers `%m`, `%d`, `%H`, and `%M` —
   without naming that template explicitly, those four tokens would have no corpus record behind
   them at all, since `v1.jsonl` has none today and the existing `InterpreterTest` assertion for
   the same format string is a plain Java string comparison with no oracle record backing it. This
   is what makes the acceptance criterion "byte-exact coverage for all eight upstream tokens"
   checkable against the record set rather than by inspection.

   Add Java-only assertions for `strftime_now()` (missing first argument), `{% call
   strftime_now() %}…{% endcall %}` and `strftime_now(fmt='%Y')` (both reach `strftime` with a
   non-empty `arguments` list — a `KeywordArgumentsValue` bag in `arguments.get(0)` — and no
   positional value; both are `ARITY` today and must remain `ARITY` after step 2's refactor),
   missing clock, missing zone, a present non-string `strftime_now(none)`, and a present
   undefined-backed string `strftime_now(x.missing)`. Text-record assertions construct their own
   `RenderOptions` with a fixed `Clock`/`ZoneId` matching the corpus record; JSONL `instant`/`zone`
   fields never reach Java. The `ARITY` and `TYPE` assertions above, by contrast, must use
   `Template.parse(source).render(context)` with no `RenderOptions` at all — the existing
   `raisedMessage(String, Map)` helper (`InterpreterTest.java:593`) takes no options parameter, and
   using it here is deliberate: it proves the argument guard runs and throws before the
   missing-clock/missing-zone check ever runs, since default `RenderOptions` has neither a `Clock`
   nor a `ZoneId` set. That ordering is a real behavioral contract with zero coverage today (no
   test currently asserts any `strftime_now` error) — if a future edit moves the clock/zone check
   above the argument guard, `{{ strftime_now() }}` would silently become `VALUE` instead of
   `ARITY` and nothing would catch it, so pin it explicitly, e.g. `assertEquals("strftime_now()
   expected one string argument", raisedMessage("{{ strftime_now() }}", Map.of()))`. The
   missing-clock and missing-zone cases are the opposite: they need `assertThrows` directly against
   a `RenderOptions.builder()` that sets only `.zoneId(...)` or only `.clock(...)`, since
   `raisedMessage` cannot express a partially-built options object. The `ARITY` assertions already
   pass unmodified today; the `TYPE` assertions (`strftime_now(none)` and `strftime_now(x.missing)`,
   asserting both `ErrorCategory.TYPE` and the `"strftime_now() format must be a string"` message)
   are intentionally red, since today's unmodified code throws `ARITY` with the arity message for
   both — they only turn green once step 2 changes production validation. Do not add error corpus
   records: upstream's `convertToRuntimeValues` unwraps every argument to its raw JS `.value`
   before calling `strftime_now`, so an absent
   argument and an undefined-backed value both surface as `Cannot read properties of undefined
   (reading 'replace')`, while `none` surfaces as `Cannot read properties of null (reading
   'replace')` — different text per shape, and `tools/corpus/error-patterns-0.5.9.json` has no
   pattern matching either message. Category parity is unobservable there regardless of whether the
   messages match, so it is still not worth an oracle error record — just don't claim the messages
   are identical.

2. **Make the deterministic formatter a named internal unit.**

   Extract the directive loop from `Interpreter.strftime` into `public final`
   `internal.util.PosixStrftime`, accepting `ZonedDateTime` and a format string. It must be public
   because `Interpreter` is in `internal.runtime`; it remains outside hfjinja's public API because
   `module-info.java` exports only `se.alipsa.hfjinja`. Match `JsSlice`'s complete class/method
   Javadoc because the build produces a Javadoc JAR. Keep fixed month-name tables and numeric
   padding under `Locale.ROOT`; do not use `DateTimeFormatter`, JVM defaults, or a production
   dependency. `Interpreter` remains responsible for argument validation, `Clock`/`ZoneId`
   presence, conversion to `ZonedDateTime`, and error categories.

   Write `src/test/java/se/alipsa/hfjinja/internal/util/PosixStrftimeTest.java` alongside this
   extraction, mirroring `JsSliceTest.java`'s shape, and exercise the directive loop directly
   against a `ZonedDateTime` (the token table, the `%`-edge vector, and unknown-directive
   passthrough). Keep this in the same step as the extraction, not deferred to step 3: step 1's own
   "extend coverage before changing production code" discipline means the new unit's test should
   land with the code it tests, and step 3 is ledger bookkeeping only — it must not be the first
   place `PosixStrftimeTest.java` is created.

   Preserve output semantics: recognized tokens are replaced, unknown `%x` and terminal `%` are
   literal, and the `%`-edge vector above remains green. Use explicitly enumerated C/POSIX English
   month names rather than a locale formatter, so JDK locale data cannot change fixtures.

   Split `Interpreter.strftime` validation before extraction: `ARITY` means "no positional format
   argument was supplied," which is not the same test as `arguments.isEmpty()`. Two call shapes
   reach `strftime` with a non-empty `arguments` list yet no positional value: `{% call
   strftime_now() %}…{% endcall %}` always appends a `KeywordArgumentsValue` bag as `arguments.get(0)`
   even when the call has no keywords (`evaluateCallStatement`, unlike `call()`'s conditional
   append; the surrounding comment about `HostFunctions.invoke` stripping that bag does not apply
   here — `strftime_now` is registered directly against `env`, not through `HostFunctions`), and
   `strftime_now(fmt='%Y')` appends a non-empty bag the same way through `call()`. Guard on the
   shape, not just the count:

   ```java
   if (arguments.isEmpty() || arguments.get(0) instanceof Value.KeywordArgumentsValue)
     throw new TemplateRenderException(
         "strftime_now() expected one string argument", ErrorCategory.ARITY, l);
   ```

   Only once that guard passes does `argument(arguments, 0)` apply. That helper deliberately
   collapses `NullValue` and an undefined-backed string into `UndefinedValue`, so branching on its
   result alone cannot distinguish "argument present but nullish" from "argument absent" — the
   guard above must run first. Every present nullish or non-string first value is `TYPE`, matching
   the public `ErrorCategory` taxonomy, and must use its own message text rather than reusing the
   `ARITY` string above — `"strftime_now() format must be a string"` — so the two categories stay
   distinguishable to a `raisedMessage`-style assertion, not just to `ErrorCategory`. Upstream's
   `convertToRuntimeValues` unwraps every argument
   shape to its raw `.value` before calling `strftime_now`, so absent/undefined-backed and explicit
   `none` reach the same unguarded `format.replace(...)` call and only differ in which `Cannot read
   properties of ... (reading 'replace')` message surfaces — never a shared, single message, and
   never a distinguishable category. This is a deliberate hfjinja category decision that upstream
   cannot express; retain a nearby code comment explaining why it is not represented as an oracle
   error record. The `{% call strftime_now() %}` and `strftime_now(fmt='%Y')` assertions added in
   step 1 must still pass as `ARITY` after this refactor — if this guard is skipped or reordered,
   both regress silently to `TYPE`.

3. **Wire and record the completed runtime path.**

   Replace the inline loop with the utility after `ZonedDateTime.now(clock).withZoneSameInstant(zone)`.
   Keep first-argument and missing-options errors, and continue ignoring extras. The
   `utils.ts` mapping comment already states that the reachable `range`/`strftime_now`/`slice`
   paths are ported, so preserve rather than duplicate it. Add both `PosixStrftime.java` and the
   `PosixStrftimeTest.java` written in step 2 to the mapping's `java:`/`tests:` lists — this step
   only edits the ledger; both files already exist by now. `build.gradle`'s `upstreamVerify` check
   for `status: implemented` entries requires every listed path to exist as a real file (it fails
   the build with "Mapping entry names a missing file" otherwise), which is exactly why the test
   was written in step 2 rather than here. Keep each inline YAML list on one physical line: the
   mapping parser is line-based and wrapped entries would be silently omitted from its check.

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
- No positional format argument supplied — absent, or only a keyword-arguments bag as in `{% call
  strftime_now() %}` or `strftime_now(fmt='%Y')` — is `ARITY`; every present nullish/non-string
  format is `TYPE` with its own message text; missing clock or zone is `VALUE`. No ambient clock,
  zone, or locale is read.
- Extra arguments remain ignored; `format.ts`, `titleCase`, and `replace` remain out of scope.
- `./gradlew check` passes.

## Deliberately deferred

- A public template formatter or port of upstream `format.ts`.
- `titleCase`/`replace` and any other `utils.ts` export with no hfjinja-side caller yet (they back
  upstream's `title`/`replace` builtins, which hfjinja has not ported).
- WP5 items 4–5: property/fuzz suites and removal of remaining ledger exemptions.
