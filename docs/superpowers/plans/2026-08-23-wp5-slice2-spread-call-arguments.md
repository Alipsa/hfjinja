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
call/filter statements, and other spread syntax.

This slice does not change the pre-existing filter-dispatch divergence: Java evaluates
namedArguments before deciding whether the operand supports a filter, while upstream evaluates
arguments only inside the matching dispatch branch. Document it as a known divergence and do not
put an invalid-spread filter case in the corpus. In particular, upstream reports its receiver error
for 5 | safe(*none), whereas Java currently evaluates the spread first.

## Work plan

1. Characterize the pinned runtime before changing Java.

   Inspect runtime.ts evaluateArguments, evaluateCallExpression, and filter dispatch. Run the
   pinned Node runtime and record exact output/error shape for:

   | Case | Required result |
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
   | [1,2] | join(sep='-', *['+']) | 1+2; spread after a keyword bypasses the ordinary-after-keyword check |

   The existing error-patterns-0.5.9.json maps Cannot unpack non-iterable type to TYPE. Append a
   successful pipe-joined record and the call-based representative TYPE error range(*'14') to
   src/test/resources/corpus/v1.jsonl. Use ids self.spread-call-arguments and
   self.spread-invalid-receiver, with source set to self-authored; verified against
   @huggingface/jinja 0.5.9. Obtain the successful expected text by directly importing
   upstream/vendor/dist/index.js, paste it in, then run:

   ~~~bash
   ./gradlew nodeCorpusVerify --offline
   ~~~

2. Add focused Java regressions.

   In InterpreterTest, cover array/tuple expansion, filter invocation, one-level expansion, and
   invalid-receiver category/location. Confirm a raise_exception inside a spread wins over a
   non-callable callee. Assert ordinary positional arguments after a keyword retain the existing
   error, but positional-after-spread, multiple spreads, and a spread after a keyword append as
   upstream does. Use join(sep='-', *['+']) to distinguish keyword-then-spread behavior. Do not
   add a duplicate-keyword integration test in this slice: Java's current filter keyword validation
   rejects that shape before last-value-wins behavior is observable.

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

4. Wire the shared result into call and filters.

   Call copies the immutable positional result into a fresh ArrayList before appending
   KeywordArgumentsValue when keywords are non-empty, then evaluates and validates the callee as
   today. namedArguments supplies the shared result to filter implementations without creating a
   KeywordArgumentsValue. Standalone SpreadExpression remains unsupported.

   Use SpreadExpression.location() for invalid receiver errors and the nested argument location for
   evaluation failures. Reuse the current environment and render budget.

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
  order, while the existing eager-invalid-filter divergence remains documented.
- Invalid spread receivers produce a located TYPE error mapped by the existing corpus classifier.
- Argument evaluation precedes callee validation; ordinary positional-after-keyword rejection and
  upstream keyword-then-spread behavior are both pinned.
- Java regressions and corpus records cover expansion, filtering, tuples, ordering, and invalid
  receivers; all offline verification passes.
