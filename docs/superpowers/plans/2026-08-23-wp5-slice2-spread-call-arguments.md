# WP5 Slice 2 — Spread Call Arguments

## Goal

Implement parsed SpreadExpression nodes in call and filter argument lists, matching pinned
@huggingface/jinja 0.5.9 argument expansion. This enables fn(*values) and value | filter(*values)
without taking on macros, call blocks, filter blocks, or parameterized tests.

## Current state and scope

Parser already recognizes *expression in a call argument list. Interpreter.call and the filter
argument helper each process keyword and positional arguments independently, so a spread currently
reaches generic unsupported-expression dispatch. Upstream runtime.ts centralizes this in
evaluateArguments, shared by calls and filters:

1. arguments evaluate left-to-right before the callee;
2. ArrayValue instances expand (and upstream TupleValue extends ArrayValue);
3. expanded items append to positional arguments;
4. keywords accumulate separately; and
5. the keyword object is appended only at invocation time.

Java Value.TupleValue is a sibling of ArrayValue, so the port must explicitly accept both. In
scope are one-level array/tuple expansion, calls and successfully dispatched filter calls through
one shared evaluator, upstream keyword/spread ordering, and corpus/regression coverage. Out of
scope are standalone spreads, non-array/tuple spread receivers, test-call arguments, macros,
call/filter statements, other spread syntax, and closing the four divergence classes documented in
"Known gaps this slice leaves open" below.

This slice does not change the pre-existing filter-dispatch divergence: Java evaluates
namedArguments before deciding whether the operand supports a filter, while upstream evaluates
arguments only inside the matching dispatch branch. Document it as a known divergence and do not
put an invalid-spread filter case in the corpus. In particular, upstream reports its receiver error
(Cannot apply filter "join" to type: IntegerValue) for 5 | join(*none), never reaching the invalid
spread, whereas Java's namedArguments evaluates and rejects the spread first, throwing Cannot
unpack non-iterable type: NullValue. join exists on both sides, so this isolates dispatch order
from unknown-filter lookup — safe is not implemented in Java at all, so it would conflate the two.

This slice also does not close a second pre-existing divergence that spreads make easy to trigger:
Java's per-filter arity caps throw ARITY once a filter's own positional-argument limit is exceeded
(join at Interpreter.java:347 accepts at most one, default at :306 accepts at most two, int/float
at :394 accept at most one), while upstream silently reads args.at(n) and ignores extras. Verified
against the pinned oracle: {{ [1,2] | join(*['-','+']) }} returns "1-2" upstream but throws Java's
"`join` filter accepts at most one argument" ARITY error, and {{ 'x' | int(1,2,3) }} returns "1"
upstream. Do not add a corpus record whose filter spread expands to more positional values than
that filter's own cap allows; see "Known gaps this slice leaves open" below.

## Known gaps this slice leaves open

- **Eager filter-argument evaluation.** Java evaluates `namedArguments` before dispatching on the
  filter name, while upstream evaluates arguments only inside the matching dispatch branch. `join`
  exists as a filter on both sides, so `5 | join(*none)` isolates this cleanly: upstream reports its
  receiver error (Cannot apply filter "join" to type: IntegerValue) without ever evaluating the
  invalid spread, but Java's namedArguments evaluates and rejects the spread first (Cannot unpack
  non-iterable type: NullValue). Do not use safe for this probe — it is not implemented in Java at
  all, so it would conflate dispatch-order divergence with unknown-filter-name divergence. Mark the
  call site with a comment referencing this section (`// Known gap: see
  docs/superpowers/plans/2026-08-23-wp5-slice2-spread-call-arguments.md#known-gaps-this-slice-leaves-open`)
  and add an `InterpreterTest` case named `filterDispatchOrder_isKnownDivergenceFromUpstream` that
  pins the current (diverging) Java behavior, so a future change to dispatch order is forced to
  notice and update both.
- **Per-filter arity caps reject spreads upstream accepts.** `join` (> 1 positional), `default`
  (> 2), and `int`/`float` (> 1) throw `ARITY` where upstream ignores extra arguments. Use the same
  pattern: a comment at each arity-check call site in `Interpreter.java` referencing this section,
  and one `InterpreterTest` case per filter named `<filter>ArityCap_isKnownDivergenceFromUpstream`
  that asserts the current Java ARITY error for a multi-element spread. For default specifically,
  the spread must be *['a', true, 'c'] — {{ missing | default(*['a', true, 'c']) }} returns "a"
  upstream (extra 'c' ignored) but throws Java's ARITY error, a clean divergence. Do not use
  *['a', 'b', 'c']: upstream still rejects that one, because its second positional ('b') fails the
  unrelated "flag must be a boolean" check, so it would pin a case where both sides error and
  demonstrate nothing about the arity-cap divergence. Do not add a corpus record for any of these
  cases — they would fail `nodeCorpusVerify` once real parity is added, and closing this gap is
  separate follow-up work, not part of this slice.
- **Strict unknown-keyword rejection.** `requireNoUnknownKeywords` throws `VALUE` on any keyword
  outside each filter's single allowed name (e.g. join only allows `separator`), while upstream
  silently ignores unrecognized keywords. Verified: {{ 1 | int(a=1, b=2, c=3) }} returns "1"
  upstream but throws Java's "Unknown `int` filter argument" VALUE error. This is the same shape as
  the arity-cap gap above, and Step 2's keyword-determinism regression below relies on this
  divergent rejection existing — so it must be named here, not left implicit. Use the same
  treatment: a comment at `requireNoUnknownKeywords`'s call sites referencing this section, and an
  `InterpreterTest` case named `unknownFilterKeyword_isKnownDivergenceFromUpstream`. This shares its
  template with Step 2's keyword-determinism regression — both drive
  {{ 1 | int(a=1, b=2, c=3) }} — but they are intentionally two separate assertions on that one
  input (VALUE category here vs. which key is reported there), not the same test under two names;
  do not merge them, and do not write a third copy. No corpus record; closing this gap is separate
  follow-up work.
- **tojson accepts zero arguments; upstream implements four.** `filterToJson` calls
  `requireNoArguments` (Interpreter.java:299), which throws `ARITY` on any positional or keyword
  argument at all, while upstream's tojson implements four real keywords (indent, ensure_ascii,
  sort_keys, separators) and silently ignores positionals. A spread reaches this directly:
  {{ [1,2] | tojson(*[9]) }} returns "[1, 2]" upstream but throws Java's "`tojson` filter accepts no
  arguments" ARITY error, and {{ [1,2] | tojson(indent=2) }} pretty-prints upstream but also throws
  that same ARITY error in Java. This is a distinct guard from the two bullets above —
  `requireNoArguments`, not `requireNoUnknownKeywords` — and a distinct divergence shape (zero
  allowed arguments vs. a capped or allowlisted set), so it needs its own comment-and-pinning-test
  treatment at Interpreter.java:299, named `tojsonArguments_isKnownDivergenceFromUpstream`. The
  other two `requireNoArguments` call sites, length (:323) and the string filters (:339), are not
  this class: upstream also rejects the call form there entirely (e.g. "Unknown StringValue filter:
  length"), just with a different message, so both sides already error and a spread introduces no
  new divergence there. No corpus record for tojson; closing this gap is separate follow-up work.

## Work plan

1. Characterize the pinned runtime before changing Java.

   Inspect runtime.ts evaluateArguments, evaluateCallExpression, and filter dispatch. Run the
   pinned Node runtime and record exact output/error shape for:

   | Case | Hypothesized result (to verify) |
   | --- | --- |
   | range(*[1,4]) | [1, 2, 3] |
   | range(*(1,4)) | tuples expand as arrays |
   | [1,2] | join(*['-']) | 1-2; filters share argument evaluation |
   | range(1, stop=4, *[2]) | [] |
   | range(*[raise_exception('boom')]) and nofn(*[raise_exception('boom')]) | spread evaluation precedes callee lookup/type validation |
   | range(*'14'), range(*{'a':1}), range(*none), and range(*missing) | Cannot unpack non-iterable type: ValueType |
   | range(*[[1,4]]) | []; expansion is exactly one level |
   | range(*[1,4], 9) | [1]; ordinary positional values after a spread remain positional |
   | range(*[1,4], *[9]) | [1]; multiple spreads append in source order |
   | [1,2] | join(separator='-', *['+']) | 1+2; spread after a keyword bypasses the ordinary-after-keyword check |

   Use separator, not sep, as the keyword name: requireNoUnknownKeywords(filter, location,
   "separator") (Interpreter.java:346) is join's only allowed keyword, so sep='-' throws VALUE
   ("Unknown `join` filter argument: sep") before spread handling is ever exercised, while
   separator='-' clears that check, clears the arity cap (size 1), and lets the non-empty
   positional spread value win over the keyword — 1+2, matching upstream.

   Do not add a row (or a corpus record) where a filter spread expands to more positional values
   than that filter's own arity cap allows — e.g. [1,2] | join(*['-','+']) or 'x' | int(1,2,3); see
   "Known gaps this slice leaves open" above.

   This table states a hypothesis for each case, not a foregone conclusion: the pinned Node runtime
   is the actual oracle. If Step 1's run produces a different result for any row, the oracle wins —
   amend this table (and any dependent step) to match before proceeding to Step 2.

   The existing error-patterns-0.5.9.json maps Cannot unpack non-iterable type to TYPE. Append the
   plain array-spread record [1,2] | join(*['-']) -> "1-2" as self.spread-call-arguments — the
   simplest representative case for a filter consuming a spread — and the call-based representative
   TYPE error range(*'14') as self.spread-invalid-receiver, both to src/test/resources/corpus/v1.jsonl,
   with source set to self-authored; verified against @huggingface/jinja 0.5.9. The
   keyword-then-spread case (join(separator='-', *['+'])) stays Java-regression-only per Step 2 and
   is not duplicated into the corpus. Obtain the successful expected text by directly importing
   upstream/vendor/dist/index.js, paste it in, then run:

   ~~~bash
   ./gradlew nodeCorpusVerify --offline
   ~~~

2. Add focused Java regressions.

   In InterpreterTest, cover array/tuple expansion, filter invocation, one-level expansion, and
   invalid-receiver category/location. Confirm a raise_exception inside a spread wins over a
   non-callable callee. Assert ordinary positional arguments after a keyword retain the existing
   error, but positional-after-spread, multiple spreads, and a spread after a keyword append as
   upstream does. Use join(separator='-', *['+']) — not sep='-'; join's requireNoUnknownKeywords
   only allows "separator" (Interpreter.java:346), so sep='-' throws VALUE before spread handling
   ever runs — to distinguish keyword-then-spread behavior. Do not add a duplicate-keyword
   integration test in this slice: Java's current filter keyword validation rejects that shape
   before last-value-wins behavior is observable.

   Also add a regression documenting unknown-keyword reporting order: call a filter with two or
   three unknown keywords, e.g. {{ 1 | int(a=1, b=2, c=3) }}, and assert the reported key is always
   "a" — the first one in source order. This documents the intended LinkedHashMap-backed behavior
   from Step 3, but treat it as a documentation aid, not a reliable regression guard: an
   implementation that used Map.copyOf instead would report a different key across JVM runs, but
   only intermittently (observed [a,c,b], [a,c,b], [b,a,c], [a,b,c], [c,b,a] across five runs of a
   three-key Map.copyOf — the first key repeated in 2 of 5), so this single-run assertion would pass
   against a broken implementation roughly a third of the time. Pair it with a deterministic guard:
   add it as its own @Test method in InterpreterTest — not a separate test class — matching the
   file-reading pattern in AstInventoryTest, asserting Interpreter.java contains no `Map.copyOf`
   substring at all. It appears exactly once today, at the line 475 call this step replaces, so a
   plain substring-absence check is simpler than and at least as strong as matching the exact
   replacement statement text, with zero false positives now or after the refactor, and it catches
   the regression on every run rather than by luck. Keeping it in InterpreterTest matters for
   iteration: Step 5's targeted run filters to
   'se.alipsa.hfjinja.internal.runtime.InterpreterTest', so a guard placed in a separate class would
   pass that command silently without ever running, even though the full `check` in Step 5 would
   still catch it. This substring check means any inline comment near the replacement must not
   spell out the literal token `Map.copyOf` either — see the note at the end of Step 3.

3. Centralize argument evaluation.

   Replace the duplicate loops in Interpreter.call and namedArguments with one helper returning
   immutable positional values and insertion-ordered keywords. The helper must return positional
   values with List.copyOf and keywords with
   Collections.unmodifiableMap(new LinkedHashMap<>(keywords)); never use Map.copyOf, whose
   iteration order is not the source insertion order. Evaluate each source argument exactly once
   in source order:

   - KeywordArgumentExpression: evaluate its value and overwrite an earlier duplicate key.
   - SpreadExpression: evaluate argument(), accept ArrayValue and TupleValue, and append its
     elements. Reject every other value with a located TYPE error using the upstream value-type
     name.
   - Ordinary expression: reject it after any keyword, then append its evaluated value.

   Do not reject a spread after a keyword. Upstream's spread branch bypasses the
   ordinary-after-keyword check. Preserve KeywordArgumentsValue construction only at callable
   invocation; filters consume the helper's separate positional/keyword result.

   If you leave an inline comment at the replacement site explaining the List.copyOf/LinkedHashMap
   choice, reference this plan section (e.g. "see docs/superpowers/plans/2026-08-23-wp5-slice2-spread-call-arguments.md,
   Step 2's source guard") rather than writing the literal token `Map.copyOf` in prose. Step 2's
   guard test asserts that exact substring is absent from the file, so a comment that names the
   type it deliberately avoids would trip a false failure.

   namedArguments' existing bare-identifier fast path (current lines 454-455:
   `if (expression instanceof Expression.Identifier id) return new NamedArguments(id.value(),
   List.of(), Map.of());`) short-circuits a parenthesis-less filter like `| join` before any
   argument loop runs. That check targets the CallExpression-vs-Identifier shape, not the
   argument-evaluation loop the new helper replaces — keep it as a guard in namedArguments ahead of
   the helper call; do not fold it into the shared helper.

4. Wire the shared result into call and filters.

   Call copies the immutable positional result into a fresh ArrayList before appending
   KeywordArgumentsValue when keywords are non-empty, then evaluates and validates the callee as
   today. namedArguments supplies the shared result to filter implementations without creating a
   KeywordArgumentsValue. Standalone SpreadExpression remains unsupported.

   Use SpreadExpression.location() for invalid receiver errors and the nested argument location for
   evaluation failures. Reuse the current environment and render budget.

   The call site's invocation flag, f.callable().invoke(arguments, !keywords.isEmpty(),
   n.location()) (current line 707), keeps deriving directly from the helper's keyword map being
   non-empty. A spread only ever contributes positional values, never a keyword, so it must never
   flip that flag; no change to the flag's derivation is needed, only confirmation via the Step 2
   regressions that it still reflects the helper's keyword map.

5. Verify and preserve the ledger.

   Keep SpreadExpression and KeywordArgumentExpression at M3 in upstream/ast-allowlist.json; the
   entries schedule work and are not binary runtime flags. The runtime.ts mapping already covers
   this path, so no mapping change is expected.

   ~~~bash
   ./gradlew test --offline --tests 'se.alipsa.hfjinja.internal.runtime.InterpreterTest'
   ./gradlew nodeCorpusVerify --offline
   ./gradlew corpusCoverage upstreamVerify check --offline
   git diff --check
   ~~~

## Acceptance criteria

- Calls and successfully dispatched filter calls expand arrays and tuples one level in source
  order, bounded by each filter's own pre-existing arity cap and keyword/argument allowlist; the
  four divergences from "Known gaps this slice leaves open" (eager filter dispatch, per-filter
  arity caps, strict unknown-keyword rejection, tojson's zero-argument guard) remain documented via
  code comment and a pinning test each, and closing any of them is out of scope for this slice.
- Invalid spread receivers produce a located TYPE error mapped by the existing corpus classifier.
- Argument evaluation precedes callee validation; ordinary positional-after-keyword rejection and
  upstream keyword-then-spread behavior are both pinned.
- Java regressions and corpus records cover expansion, filtering, tuples, ordering, and invalid
  receivers; all offline verification passes.
