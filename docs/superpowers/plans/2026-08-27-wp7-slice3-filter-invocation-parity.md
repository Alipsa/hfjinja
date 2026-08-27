# WP7 Slice 3 — Filter Call Semantics Parity

## Goal

Close the next bounded part of WP7 item 1 in
[`req/implementation-plan.md`](../../../req/implementation-plan.md): remove Java-only
validation from recognized filter calls where pinned `@huggingface/jinja` 0.5.9 eagerly evaluates
all arguments and then ignores surplus values, and port the one exceptional pre-evaluation path for
`selectattr`/`rejectattr`.

This follows Slice 2. Slice 2 correctly established receiver selection before argument evaluation
for unsupported call forms. For every *recognized* call branch other than `selectattr` and
`rejectattr`, pinned `runtime.ts` calls `evaluateArguments(filter.args, environment)` eagerly.
Its later use of only selected slots/keywords does **not** make later expressions lazy. Java's
existing per-branch `filterArguments(...)` shape already follows that rule; this slice must not
replace it with a per-slot or lazy argument abstraction.

## Evidence and scope

The relevant upstream branches are `runtime.ts`'s `tojson`, `join`, `int`/`float`, `default`,
`sort`, `map`, `indent`, `replace`, and object-builtin call paths. Argument ordering is
per-branch: `int`/`float`, `tojson`, and `default` evaluate arguments before inspecting their
receiver, while `join` rejects an invalid receiver before evaluating arguments. Once a recognized
branch elects to evaluate, it evaluates each argument left-to-right before discarding values it does
not consult. Java currently has the same shape through `filterArguments` after the relevant branch
check. In particular, ignored sentinel arguments to `int`, `float`, `sort`, `join`, `indent`,
`map`, `tojson`, `default`, and `replace` must still raise the dedicated eager sentinel when their
branch evaluates them.

The actual Java deltas are narrower:

- `filterNumber` rejects surplus positional arguments and unknown keywords that upstream ignores;
- selected `join` and `default` calls have equivalent Java-only arity/keyword restrictions that
  need an oracle matrix;
- filter-form `replace` must append a `KeywordArgumentsValue` bag unconditionally, unlike the
  member-call path, so one positional value reaches the builtin's string-type error rather than
  the member call's missing-argument error;
- object filter forwarding must preserve the upstream keyword-bag placement. For `get`, that can
  let `KeywordArgumentsValue` escape as a value and later produce the classified
  `Cannot convert to JSON: KeywordArgumentsValue` error. The equivalent member-call protocol is
  implicated and must be changed only with explicit oracle evidence;
- string member `split` has the same trailing-bag slot problem as `get`: stripping the bag makes
  Java silently accept `maxsplit=` or an unknown keyword where upstream supplies the bag as the
  optional second slot and raises a type error. `replace`, `get`, and `split` are the reviewed
  `positional()` blast radius. `startswith`, `endswith`, and `dictsort` are verified unaffected;
- `selectattr`/`rejectattr` validate receiver contents and argument AST node types before they
  evaluate any argument. Java presently calls `filterArguments` too early, reports a different
  keyword error, and imposes a three-argument cap that upstream does not have.

`evaluateArguments` also has one exact normal-runtime diagnostic delta: upstream says
`Positional arguments must come before keyword arguments`; Java says `cannot follow`. It is reached
through filters, macros, and host-function calls. This slice treats it as `SYNTAX`, preserving the
existing stable Java category for an invalid call shape while matching the pinned message; the
versioned Node classifier must map the exact message to `SYNTAX` rather than relying on the parser
error family.

Empty `first`/`last` direct output, chaining, and boolean/test contexts are deliberately outside
this slice: Node returns raw JavaScript `undefined` and then throws an unclassified TypeError.
They cannot enter `v1.jsonl` under the no-catch-all classifier rule. The survivable assignment case
(`{% set x = []|first %}{{ x }}`) already renders `""` in Java and Node and may be retained as one
corpus regression; the raw-crash paths remain the documented Java-only divergence. Empty tuple
coverage must use a produced empty tuple (for example `(1, 2)[0:0]`), not `()`, which is a parser
error.

Keep macro/call-block control propagation, undefined-key `tojson`/`dictsort`, undefined-backed
`lower`, function-value rendering, and the complete vector inventory as later WP7 slices.

## Compatibility contract

- For a recognized call-form filter, evaluate every argument expression exactly once in source
  order, even when the selected branch ignores its resulting value. This includes positional
  arguments, keyword values, and spreads. Preserve each branch's existing type validation after
  eager evaluation.
- Unsupported call forms retain Slice 2's pre-evaluation receiver/unknown-filter failure.
- `selectattr` and `rejectattr` are the sole reviewed exception: after confirming an array receiver
  of objects, inspect the raw AST arguments and reject every non-`StringLiteral` before evaluating
  any argument. Keyword arguments consequently fail the pinned string-literal `TYPE` family, not
  a Java `ARITY` error. Do not impose Java's one-to-three argument cap: upstream evaluates four
  or more string literals and produces its ordinary result.
- Ignore surplus positionals and unknown keywords only where the selected upstream implementation
  ignores their already-evaluated values. Retain validations for consumed options such as
  `tojson` options, `sort`'s `reverse`/`case_sensitive`/`attribute`, `indent` width, `join`
  separator, and `default`'s boolean flag. `int`/`float` default values themselves have no
  upstream type validation.
- Keep filter-form and member-form `replace` distinct only in empty-keyword bag creation: the
  filter path always appends a bag, while the member path appends one only when keywords exist.
  When a bag exists, both paths must expose it as an ordinary raw slot to `replace`; a one-
  positional call consequently sees it as the replacement candidate. When the raw third slot is a
  keyword bag, read `count` from that bag; otherwise the third raw slot is the positional count.
  Retain the member path's genuine missing-two-argument check when no keyword bag exists.
- Any `get` keyword-bag value escaping into normal rendering must use the project `Value` model and
  be classified explicitly; it is not unreachable. This slice scopes that escape to the reviewed
  JSON-render route only. Its definedness, length, member access, and other downstream operations
  remain unspecified follow-up work and must not have assertions relaxed opportunistically. Raw
  upstream `o|get()` TypeErrors remain documented Java-only behavior unless a stable error contract
  is separately approved.
- A positional argument after a keyword argument is a `SYNTAX` call-shape error with the exact
  pinned message `Positional arguments must come before keyword arguments`, consistently through
  filter, macro, and host-function invocation paths.

## Implementation plan

1. **Build the real Node matrix before changing Java.**

   Add reviewed records using the established `wp7.*` series used by Slices 1–2, and source
   `self-authored; verified against @huggingface/jinja 0.5.9`. Run each through
   `tools/corpus/run-node-oracle.mjs` first. Every retained record gets an equivalent named
   `InterpreterTest` assertion identifying its corpus ID.

   Before adding any eager-error record, add the exact anchored
   `^wp7-eager-sentinel$` → `EXPLICIT_RAISE` classifier pattern and a match/near-miss Node unit
   test. Use `raise_exception('wp7-eager-sentinel')` for every eager sentinel in this slice; do not
   add a generic `^boom$` or other catch-all explicit-raise pattern.

   Add eager-sentinel pairs for `int`, `float`, `sort`, `join`, `indent`, `map`, `tojson`,
   `default`, and `replace`: an ignored successful value proves final output, while
   `raise_exception('wp7-eager-sentinel')` in the same ignored position proves it is nevertheless
   evaluated.
   These are no-change guards for the current `filterArguments` timing, not candidates for lazy
   evaluation.

   Add successful and sentinel cases for the real Java-only restrictions: `int`/`float` surplus
   positionals and unknown keywords; and `join` and `default` caps/unknown keys. Pin both ordering
   shapes explicitly: `{{ o|int(raise_exception('wp7-eager-sentinel')) }}` must raise that sentinel
   because `int` evaluates first, whereas
   `{{ 5|join(raise_exception('wp7-eager-sentinel')) }}` must report its receiver error because
   `join` checks first. `{{ o|int(1, foo=2) }}` instead pins keyword permissiveness, not receiver
   ordering.

   Convert—not delete—the existing spread-shaped divergence regressions
   `joinArityCap_isKnownDivergenceFromUpstream`, `defaultArityCap_isKnownDivergenceFromUpstream`,
   `intArityCap_isKnownDivergenceFromUpstream`, `floatArityCap_isKnownDivergenceFromUpstream`, and
   `unknownFilterKeyword_isKnownDivergenceFromUpstream` into named oracle-backed success
   assertions carrying their new corpus IDs. Preserve their spread forms: reintroducing a cap only
   on spread expansion must fail an assertion. Update both existing
   positional-after-keyword wording assertions (`rejectsPositionalArgumentsAfterKeywordsBeforeEvaluation`
   and `filterArgumentsStillRejectOrdinaryPositionalAfterKeyword`) to the pinned wording and retain
   their `SYNTAX` category assertion.

   Add `selectattr`/`rejectattr` cases for a throwing non-string argument, a non-throwing computed
   string argument, a spread argument, a keyword argument, and four string-literal arguments.
   The computed and spread cases prevent Java from silently rendering where Node rejects the AST.
   Add the exact anchored `^arguments of \`(?<name>selectattr|rejectattr)\` must be strings$`
   `TYPE` classifier pattern and match/near-miss tests, alongside the existing sibling
   non-object-array pattern. Assert the Node's AST-level error/result; do not try to express a raw
   TypeError as a corpus category. Document `{{ items|selectattr() }}` on a non-empty object array
   as a raw upstream TypeError and keep it Java-only, while retaining `{{ []|selectattr() }}` →
   `[]` as an oracle-backed success case.

   Add filter/member `replace` pairs for one positional, zero positional, keyword-only, normal two
   positional values plus keyword `count`, and four positional values. Include the member false-
   accept `{{ 'abc'.replace('a', count=1) }}` as well as unknown-keyword variants. They must
   demonstrate that both forms expose a present bag as a raw slot, that only the filter form creates
   an empty bag, and that the third positional count wins while later values are ignored after eager
   evaluation.

   Add object filter and member `get` pairs for positional, keyword-only/mixed keyword, present and
   missing keys, and a throwing unused keyword. Include `{{ o.get(foo=1) }}` to pin the upstream
   `KeywordArgumentsValue` rather than Java's `UndefinedValue` diagnostic. Add an anchored
   classifier pattern and match/near-miss Node unit tests only for the reviewed
   `Cannot convert to JSON: KeywordArgumentsValue` outcome; `JsFormat` already has the required
   Java `TYPE` message once the valid value flow can reach it. Record unclassifiable `o|get()` as
   Java-only rather than weakening the classifier.

   Add `split` records for `{{ 'a b c'.split(' ', maxsplit=1) }}`, `{{ 'a b'.split(sep=' ') }}`,
   `{{ 'a b'.split(zz=1) }}`, and the already-matching positional-second-slot plus unknown-keyword
   shape. Add anchored patterns and match/near-miss Node unit tests for
   `^sep argument must be a string or null$` and `^maxsplit argument must be a number$`, both
   `TYPE`.

   Keep the already oracle-matched keyword-only filter call
   `{{ 'ab'|replace(old='a', new='x') }}` as a named regression: its bag remains the sole raw slot,
   so both runtimes correctly report `replace() requires at least two arguments`. It is not a
   casualty of the raw-slot change.

   Add the positional-after-keyword vector through all three execution paths: a filter such as
   `map(attribute='a', 99)`, a macro call, and `raise_exception(x=1, 2)`. Add the exact anchored
   `^Positional arguments must come before keyword arguments$` classifier pattern as `SYNTAX`, with
   a near miss. These regressions must prove that the shared evaluator, not just one call site,
   uses the pinned wording and category.

   Retain one corpus case for the survivable assigned empty `first`/`last` result. Keep direct,
   chained, boolean, and empty-produced-tuple crash cases in named Java-only documentation/tests.

   Add a compact filter-block counterpart set: `{% filter int(1, 2) %}x{% endfilter %}` for
   permissiveness, `{% filter replace('a') %}abc{% endfilter %}` for raw-slot behavior, and
   `{% filter int(1, raise_exception('wp7-eager-sentinel')) %}x{% endfilter %}` for eager
   evaluation. Retain
   Slice 2's `{% filter safe() %}` unsupported-call regression as a no-evaluation boundary. These
   records and named Java assertions prove that expression and block filtering share the same
   runtime behavior rather than merely assuming `evaluateFilterStatement` continues to delegate.

2. **Preserve eager evaluation and delete only oracle-proven Java validation.**

   Keep `filterArguments(name, call, env, budget)` as the selected-branch evaluator. Do not add a
   lazy cache, per-slot evaluator, or branch-specific partial argument collection. Preserve
   `evaluateArguments` ordering and spread behavior. Change its positional-after-keyword message to
   the pinned wording and retain the `SYNTAX` category selected above; every filter, macro, and
   host-function caller must inherit it. Make all selected-filter changes at or below
   `applyFilter` and its helpers, never solely in the filter-expression entry seam, so
   `evaluateFilterStatement` inherits them unchanged.

   The permissiveness change removes `unknownFilterKeywordReportsFirstKeyInSourceOrder`'s only
   template-observable ordering error. Do not widen `evaluateArguments` visibility or add a new AST
   construction test seam. Instead keep a source guard scoped to the `evaluateArguments` method
   body: require its `LinkedHashMap<String, Value>` declaration and its
   `Collections.unmodifiableMap(keywords)` return path, and reject `Map.copyOf(keywords)` there.
   This directly protects the two order-preserving choices without banning unrelated map use
   elsewhere in `Interpreter.java`, and catches precisely the production regression formerly
   exposed by the `z`, `a`, `m` error assertion.

   Remove `filterNumber`'s one-positional cap and `requireNoUnknownKeywords` check. Use its first
   positional value, then `default`, then the pinned numeric fallback; leave later eagerly
   evaluated values unused. Apply the same narrowly evidenced change to `join` and `default` if
   and only if their matrix records establish the present Java caps/unknown-key rejection as a
   divergence. Do not add an invented validation for `int`/`float` `default`.

   Keep the explicit validations named in the compatibility contract and add a regression for each
   consumed bad type, so permissiveness does not become a blanket removal of meaningful checks.

3. **Port the two call-protocol differences precisely.**

   For filter-form `replace`, evaluate all arguments with `filterArguments` and then append
   `new Value.KeywordArgumentsValue(keywords)` even when the map is empty before invoking the
   builtin. Retain conditional bag appending for member calls. Ensure both member and filter paths
   pass an unstripped/raw slot view to string `replace` whenever a bag is present: the general
   `positional()` helper would otherwise discard it and preserve Java's incorrect missing-argument
   error. A one-positional call with a bag must receive that bag as its replacement candidate. For
   a plain two-positional filter call, the raw third slot is the appended empty bag; for either form
   with keywords it is the present keyword bag. Recognize that bag and read `count` from it; only a
   non-bag third raw slot is the positional count. This preserves ordinary `replace('a', 'Z')` and
   `replace('a', 'Z', count=2)` calls while retaining third-positional-wins behavior and eager
   evaluation of later ignored values.

   Audit `filterObjectBuiltin`, `objectBuiltin`, `stringBuiltin`, `positional`, and `keyword`
   together with ordinary member-call evaluation. The reviewed shared-helper blast radius is exactly
   `replace`, `get`, and `split`; preserve the verified behavior of `startswith`, `endswith`, and
   `dictsort`. Make the smallest shared/protocol-specific change that makes the reviewed `get` and
   `split` records match, including bag placement and `KeywordArgumentsValue` escaping as a normal
   value. Replace only the erroneous `unreachableValue` route(s) reached by this valid value flow;
   preserve internal assertions for genuinely impossible shapes. Update the existing
   `documentsAcceptedUpstreamDivergences` member-call `get` assertion when the oracle-backed result
   changes. Also replace its filter-form `{{ 'ab'|replace('a') }}` accepted-divergence assertion:
   after the raw-slot fix it is normal oracle-matched behavior, not a deliberate divergence.

4. **Make `selectattr`/`rejectattr` pre-validation match the AST contract.**

   In `filterSelectAttrCall`, retain the receiver-content check ahead of argument work. Then scan
   `call.args()` directly and reject every node other than `Expression.StringLiteral` with the
   pinned message/category before calling `filterArguments`. This check includes keyword and
   spread expressions. Remove Java's `filterSelectAttr` keyword rejection and its *upper* arity
   bound when they conflict with the upstream AST rule, but retain an explicit zero-argument guard.
   The non-empty object-array zero-argument upstream path is a documented raw TypeError; Java must
   keep its stable, named Java-only outcome rather than leaking `IndexOutOfBoundsException`.
   Evaluate accepted literal lists in order and mirror the pinned behavior for additional literals.

   Explicitly test that the AST rejection suppresses
   `raise_exception('wp7-eager-sentinel')`, that a keyword produces the string-literal error rather
   than `ARITY`, that four literals follow the oracle, and that Slice 2's non-object-array receiver
   check still wins before any argument evaluation. Test the retained non-empty-object-array
   zero-argument Java-only guard explicitly so it remains a `TemplateRenderException`, never an
   accidental index failure.

5. **Falsify the actual changes and verify.**

   Temporarily re-add the `int`/`float`, `join`, and `default` caps/unknown-keyword rejection;
   confirm their converted spread-form corpus-linked Java tests fail. Temporarily replace the
   production evaluator's `new LinkedHashMap<String, Value>()` construction with
   `new java.util.HashMap<>()` in `evaluateArguments`; confirm the method-scoped source guard
   fails. Separately replace that method's `Collections.unmodifiableMap(keywords)` return path with
   `Map.copyOf(keywords)` and confirm the guard fails. Temporarily make filter `replace`
   use a bag-stripped slot view in either form (or append a bag only when non-empty in the filter
   form), revert the `get` bag placement, and reintroduce bag stripping for `split`; confirm their
   distinct regressions fail.
   Temporarily restore Java's old positional-after-keyword wording and confirm each filter, macro,
   and host-function regression fails. Then remove the
   `selectattr`/`rejectattr` AST pre-check; confirm their distinct regressions fail. Separately
   reinstate the old upper arity cap and confirm its four-literal vector fails, while preserving the
   explicit zero-argument Java-only guard. Revert every mutation.

   Confirm each relevant temporary mutation also fails its filter-block counterpart; a change that
   fixes only filter expressions is incomplete.

   Confirm JDK 21 and the lock-pinned Node version, then run:

   ```bash
   ./gradlew spotlessApply
   node --test tools/corpus/corpus.test.mjs
   node tools/corpus/run-node-oracle.mjs --corpus src/test/resources/corpus/v1.jsonl --patterns tools/corpus/error-patterns-0.5.9.json --lock upstream/upstream-lock.json
   ./gradlew test --tests se.alipsa.hfjinja.internal.runtime.InterpreterTest
   ./gradlew corpusCoverage upstreamVerify
   ./gradlew check
   git diff --check
   ```

## Acceptance criteria

- Selected recognized filters stay eagerly evaluated; sentinels prove ignored values are evaluated,
  while Slice 2 unsupported forms still suppress argument evaluation.
- `int`/`float`, and any oracle-proven `join`/`default` restrictions, ignore surplus values only
  after evaluating them and retain type checks for options they consume.
- The five former arity/unknown-keyword divergence tests are retained as corpus-traceable,
  spread-form success regressions, and keyword insertion order remains guarded at the production
  evaluator source seam.
- Filter/member `replace` bag behavior, object `get` keyword-bag flow, and string `split` optional
  second-slot handling match reviewed Node outputs/categories. The `KeywordArgumentsValue` is no
  longer rejected as impossible on the valid JSON-render flow; other escaped-bag operations remain
  explicitly out of scope.
- `evaluateArguments` emits the exact pinned positional-after-keyword message with the declared
  `SYNTAX` category through filter, macro, and host-function calls.
- Expression and `{% filter %}` block forms agree for the reviewed permissive-argument, raw-slot,
  eager-evaluation, and unsupported-call boundaries.
- `selectattr`/`rejectattr` validate their AST arguments before evaluation, use the oracle's
  message/category for keywords and other non-literals, and accept the oracle's four-literal case.
- Empty direct `first`/`last` crashes remain explicitly documented as unclassifiable Java-only
  divergence; only the survivable assignment case is corpus-backed.

## Deliberately deferred

- Macro/call-block `break` and `continue` propagation.
- `tojson` undefined-key ordering, two-key undefined-backed `dictsort`, and undefined-backed
  `lower` behavior.
- Parser diagnostic parity is a separate WP7 error-contract follow-up. It must make Java's
  `Parser.expect` message byte-exact with the pinned `Parser Error: Expected closing expression
  token. OpenParen !== CloseExpression.` family, including its final period; the present generic
  `^Parser Error: (?<detail>.+)$` classifier masks that practical exact-message difference. It
  must also add a named Java-only contract note and regression for unclosed/end-of-input parser
  paths such as `{% if x %}a`: pinned upstream dereferences an absent token and throws an
  unclassifiable raw TypeError, while hfjinja deliberately reports its stable located `Unexpected
  end of template` syntax error. Do not add a catch-all TypeError classifier for that crash.
- Function-value rendering, complete upstream vector inventory/converter coverage, and WP6 release
  work.
- A separate corpus-execution follow-up must run `v1.jsonl` against Java as well as the Node
  oracle, rendering text records and checking thrown `ErrorCategory` values. Until then, each
  corpus record in a parity slice requires an explicit Java regression rather than treating Node
  verification as Java coverage.
