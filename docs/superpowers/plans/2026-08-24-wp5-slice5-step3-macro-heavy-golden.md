# WP5 Slice 5 — Step3 Macro-heavy Model Golden

## Objective and boundary

Complete the still-open **macro-heavy-template** clause of WP5 item 3 with one real, pinned,
license-reviewed chat template. This is not another general macro implementation: macros, calls,
filter blocks, keyword arguments, spread arguments, slicing, `string`, `items()`, and the shared
named-test dispatcher already landed in slices 1–4. The fixture below uses those paths in a
model-shaped render and adds the two runtime behaviours it exposes:

- the upstream `mapping` test; and
- `tojson` call-form keyword handling, specifically `ensure_ascii`.

The latter deliberately ports the complete four-keyword `tojson` contract, including its
collation, indentation, and separator semantics, rather than adding a fixture-only keyword shim.

This slice deliberately keeps the model template in **one** test resource, rather than duplicating
it inline in `v1.jsonl` and Java. Node and Java tests will each load that resource and compare a
shared, byte-exact golden. It consequently closes the macro-heavy part of WP5.3 without changing
the existing corpus JSONL schema or falsely claiming that the previously deferred Mistral/Qwen
corpus-import follow-up has happened.

The selected candidate is:

| Field | Value |
| --- | --- |
| Model repository | `stepfun-ai/step3` |
| Immutable revision | `7bf55112c8b477c47f91ed7c5872a5a80015b099` |
| Template path | `chat_template.json`, `chat_template` field |
| License basis to review | Apache-2.0 |
| Model-card attribution | `https://huggingface.co/stepfun-ai/step3/blob/7bf55112c8b477c47f91ed7c5872a5a80015b099/README.md` |
| Extracted template length | 2,847 Unicode code points / UTF-16 code units |
| Extracted-template UTF-8 SHA-256 | `fc7bfeffd0dcee65d97834d2f0d60fb81c5db9f3e2567d038e3437f2bbdd54ca` |
| Fetched JSON UTF-8 SHA-256 | `26e17e460c676cee0e5020859a27adaad8025191a033a2228069dcdad7e6e3f4` |

The exact source was fetched on 2026-08-24 from
`https://huggingface.co/stepfun-ai/step3/raw/7bf55112c8b477c47f91ed7c5872a5a80015b099/chat_template.json`.
An implementation must re-fetch from that immutable URL, JSON-decode `chat_template`, and reject
the result unless both the character count and template SHA-256 above agree. The fetched JSON is
2,975 UTF-8 bytes. Network access to `huggingface.co` is therefore a prerequisite for the intake
step; do not substitute a template copied from a model card, a moving branch, or an inference
library.

## 0. Required approval before text is committed

`stepfun-ai/step3` is not in `req/model-fixture-policy.md` today. Before adding its template text,
perform the fixture-specific review required by that policy and make these documentation changes
in the **same commit** as the new resource:

1. Confirm the repository/revision is a real immutable commit, that its immutable
   [`LICENSE`](https://huggingface.co/stepfun-ai/step3/blob/7bf55112c8b477c47f91ed7c5872a5a80015b099/LICENSE)
   is Apache-2.0, and that README §License at the same revision says both the code repository and
   model weights are Apache-2.0. `chat_template.json` itself has no license declaration and the
   model card has no license YAML metadata, so neither is evidence for that determination. Confirm
   that retaining the source text and self-authored golden output is permitted; record the
   reviewer/date and immutable source, LICENSE, and model-card URLs in the policy's “Sources
   reviewed” list. Do not cite the README badge's moving `/blob/main/LICENSE` link.
2. Add an exact `stepfun-ai/step3` row to the policy's approved-fixture table. It must say that
   the text may be retained only after the `chat_template.json` path, Apache-2.0 notice, and
   model-card attribution are recorded. Do not weaken the catch-all “not preapproved” row.
3. Add a `NOTICE` model-fixture row with repository, full revision, `chat_template.json`
   `chat_template` path, Apache-2.0, resource path, notice reference, and the model-card URL.
   Preserve the existing attribution column and the Mistral/Qwen rows.
4. Update the policy's “Current fixture set” prose to include Step3 as the macro-heavy approved
   fixture. State that the source record, notice, and attribution were added with the first
   retained copy. This is factual intake bookkeeping, not deferred legal work.

If that review rejects this candidate, stop before retaining text. Select another immutable
macro-bearing template and amend this plan with its exact provenance, licence determination,
source hash, required runtime inventory, and golden contexts; a hash-only fallback cannot satisfy
WP5.3's retained macro-heavy golden requirement.

## Fixture inventory and intended coverage

The source declares `{% macro render_content(content) %}` and calls it for system, user,
tool-description, tool-response, and assistant content. Its branches use `is string`, `is mapping`,
`is iterable`, `is defined`, `not … is defined` precedence, a conditional expression, membership
(`in`) on mappings, nested `for`, `namespace`, member assignment (`{% set ns.data = … %}`),
`tojson(ensure_ascii=False)`, `string`, object member/index access, and
`tool['function']['arguments'].items()`. `False` is the template's spelling; it already evaluates
because `Interpreter` seeds the `True`/`False` globals.

The existing interpreter already covers macro declaration/call scope and output capture, `string`,
`items()`, `defined`, `iterable` for arrays and strings, `namespace`, member/index access, and
the needed loops. The proposed tool-use context must exercise all three `render_content` forms:

- a system string;
- a user array containing text and image objects; and
- a mapping tool response.

Include a tool declaration whose description contains a non-ASCII character and a tool-call
argument map with at least two insertion-ordered keys. That forces the `tojson(ensure_ascii=false)`
branch, macro invocation, `items()` ordering, and nested rendering. Use `LinkedHashMap` throughout
the Java context; `Map.of` is not acceptable where rendered order is observed.

Use an empty/no-tools plain conversation as a second smoke case only if its output materially
differs. It must not replace the tool-use case, since that would leave the macro and the
`tojson` call-form branch unreachable.

## Implementation steps

1. **Add focused differential regressions before changing production code.**

   Add Java tests in `InterpreterTest` and self-authored text records in `v1.jsonl` for the small
   primitives, with expected values generated by the pinned Node oracle:

   - `none is mapping`, `{}` / `namespace()` / `namespace(x=1)` `is mapping`, and
     arrays/tuples/strings `is not mapping`; `namespace(x=1)` is a required corpus vector because
     the keyword call reaches `Environment.namespace` as `KeywordArgumentsValue`;
   - `iterable` remains true for `ArrayValue` and non-undefined strings and false for objects and
     tuples, exactly as `Environment.TESTS` in `runtime.ts` defines it;
   - `tojson(ensure_ascii=False)` preserves non-ASCII JSON text, while the discriminating
     `ensure_ascii=True` vectors escape every UTF-16 code unit at or above `0x7f`—including DEL,
     BMP characters, and both surrogate halves—with upstream's lower-case `\\uXXXX` spelling in
     **both keys and values**. The model fixture itself passes `False`, so accepting and ignoring
     that keyword would otherwise produce the same output;
   - keyword type failures (`ensure_ascii=1`), `indent=0` matching no indent, and `indent=-1`
     raising upstream's `Invalid count value: -1`. Classify the latter as `VALUE` and add its
     `^Invalid count value: (?<count>-?\\d+)$` pattern before keeping it as an error corpus vector;
   - interaction with `indent`, `sort_keys`, and `separators`, including `{a: [], b: {}}` at
     `indent=2` (empty containers retain their interior newline/padding) and indentation combined
     with custom separators. Pin these exact outputs:

     ```text
     {
       "a": [
         
       ],
       "b": {
       }
     }
     ```

     (The otherwise blank line inside the empty array is exactly four spaces after Markdown
     code-fence indentation; preserve those bytes.)

     and, with `indent=2, separators=(';', '=')`:

     ```text
     {
       "a"=1;
       "b"=2
     }
     ```

   - `sort_keys=true` on `z`, `ä`, `a`, `B`, and `_`, which must yield `_, a, ä, B, z` under the
     oracle's fixed `en-US` collation, plus collation-equal NFC/NFD keys inserted as
     `{'\u00e1' (NFC): 1, 'a\u0301' (NFD): 2}`. Write those escapes literally in the JSONL
     source and Java string literals, never as visually indistinguishable source characters; the
     parser/literal evaluation must then produce the two distinct key sequences. The latter must
     retain both entries in insertion order, expressed in expected literals as
     `{"\u00e1": 1, "a\u0301": 2}`.

   Keep message comparisons out of corpus records unless the Node classifier already has the exact
   error pattern; corpus parity is category-level for errors. Add any newly observed, legitimate
   upstream error category to `tools/corpus/error-patterns-0.5.9.json`, including its optional
   `comment` field explaining the fixture path when useful. The validator at `corpus.mjs:93` only
   requires `regex` and `category`, so that comment remains schema-compatible.

2. **Make named tests share the upstream meaning.**

   Extend `Interpreter.namedTest` with `mapping`, returning true for `Value.ObjectValue` **and**
   `Value.KeywordArgumentsValue`. Upstream's latter is an `ObjectValue` subclass; hfjinja models
   them as sealed-union siblings, so both variants must be named explicitly. Do not instead use
   Java `Map` semantics. Keep `iterable` as the upstream
   `ArrayValue`/`StringValue` test—do not broaden it to objects merely because `for` can iterate an
   object. The expression-test route and `selectattr` must continue to call this single dispatcher.

3. **Port `tojson` call-form options as a coherent unit.**

   Replace `filterToJson`'s current `requireNoArguments` shortcut with upstream-compatible
   positional/keyword evaluation. Positional arguments remain ignored as upstream does. Consume
   `indent` (integer or null), `ensure_ascii` (boolean), `sort_keys` (boolean), and `separators`
   (two strings in an array or tuple, or null); unknown keywords are ignored by the upstream
   implementation and must not become a new strict hfjinja-only error.

   Introduce a small internal JSON-options value (or similarly explicit parameters) in `JsFormat`,
   rather than adding booleans ad hoc to every recursive call. Preserve existing `tojson` defaults:
   undefined becomes JSON `null`, no indentation, insertion order unless `sort_keys=true`, and
   `ensure_ascii=false`. Sort a stable list of insertion-ordered entries with a fresh
   `java.text.Collator.getInstance(Locale.US)`, never `String.compareTo`, `TreeMap`, or the JVM
   default locale: the oracle pins `localeCompare` to `en-US`, and collation-equal keys must retain
   their distinct entries and original relative order. For ASCII escaping, transform the already
   JSON-quoted text exactly once, escaping all
   UTF-16 code units `>= 0x7f` in keys and values without double-escaping backslashes/control
   escapes already emitted by `JsFormat.quote`.

   Implement `indent` by the upstream truthiness rule: zero has no indentation; a negative value
   must be caught and rethrown as a `TemplateRenderException` in the corpus-mapped `VALUE`
   category, not leaked as `IllegalArgumentException` from `String.repeat`. Preserve upstream's
   padding for empty arrays/objects and apply separators consistently at every nesting depth. Test
   sorted keys, custom separators, empty/nested containers, undefined, and non-finite numbers to
   ensure this refactor does not alter the existing JSON contract. Keep these options exclusively
   on the `tojson` route: `JsFormat.runtimeJson(value, location)` (the two-argument,
   `convertUndefinedToNull=false` path) continues to back `string`/array `toString`, so
   `{{ [x] }}` remains `[undefined]` while `{{ [x]|tojson }}` is `[null]`.

4. **Retain the source once and build the two CI goldens from it.**

   Add `src/test/resources/model-templates/step3.jinja` containing only the decoded template
   bytes, with no newline normalization, and add
   `src/test/resources/model-templates/step3-tooluse.expected.txt` for the one expected UTF-8
   render. Add a Java resource helper that fails clearly on a missing resource and use it for the
   existing Mistral/Qwen resources as well as the new template/output. Then add
   `rendersStep3MacroHeavyTemplate` to `InterpreterTest`; it must render the representative ordered
   tool-use context and compare to the shared expected resource. Do not inline either template or
   a second copy of the large golden in Java.

   In `tools/corpus/corpus.test.mjs`, read the same resource by repository-relative path and assert
   its UTF-8 SHA-256 and character length against the pinned values above. Refactor the existing
   local `runOracle` helper only as needed so a Node test can pass that loaded resource and the
   identical tool-use context and the shared expected resource to `run-node-oracle.mjs`; assert the
   full expected output. This makes CI detect truncation/corruption and confirms the golden against
   `@huggingface/jinja` 0.5.9 without duplicating model template text or output in JSONL. Contexts
   remain duplicated because the JUnit test classpath has no JSON parser. The expected output is
   self-authored test data, but its retention must still be covered by the approval in section 0.

5. **Update WP5 tracking accurately.**

   In `req/implementation-plan.md`, revise WP5.3 after the tests are green to say that the
   Mistral/Qwen resources plus this Step3 fixture satisfy its macro-heavy-template clause. Keep the
   already documented follow-up visible: importing the retained Mistral/Qwen/Step3 templates as
   `v1.jsonl` corpus cases is not delivered by this slice and first requires an explicit corpus
   schema change, since `validateRecord` currently rejects `modelRepo`, `modelRevision`, and
   `templatePath` on text records. Do not describe G5 as complete—the
   pinned upstream suite, full retained-model corpus set, fuzz/property work (WP5.4), and AST
   ledger cleanup (WP5.5) remain.

6. **Format and verify.**

   Run `./gradlew spotlessApply` after Java edits, then `./gradlew check upstreamVerify`. Confirm
   JDK 21 and the Node version in `upstream/upstream-lock.json` before interpreting failures.
   `check` already executes Java tests, `nodeCorpusTest`, `nodeCorpusVerify`, `corpusCoverage`, and
   `upstreamVerify`; the Node fixture test and Java fixture test must remain on that normal path. A
   manual curl/render is not acceptance evidence.

## Files expected to change

| File | Change |
| --- | --- |
| `src/main/java/se/alipsa/hfjinja/internal/runtime/Interpreter.java` | Add `mapping` to the shared named-test dispatcher and route `tojson` arguments/options. |
| `src/main/java/se/alipsa/hfjinja/internal/JsFormat.java` | Carry explicit JSON options and implement upstream-compatible `ensure_ascii`. |
| `src/test/java/se/alipsa/hfjinja/internal/runtime/InterpreterTest.java` | Differential unit coverage and one resource-backed Step3 macro/tool-use golden. |
| `src/test/resources/model-templates/step3.jinja` | The one retained decoded template resource. |
| `src/test/resources/model-templates/step3-tooluse.expected.txt` | Shared byte-exact expected output for the Java and Node golden tests. |
| `src/test/resources/corpus/v1.jsonl` | Small self-authored primitive vectors only; no duplicate Step3 template. |
| `tools/corpus/corpus.test.mjs` | Resource length/hash assertion and Node-oracle Step3 golden. |
| `tools/corpus/error-patterns-0.5.9.json` | Only if a newly retained error vector exposes a classifier gap. |
| `NOTICE`, `req/model-fixture-policy.md` | Same-change source, license, notice, and attribution approval. |
| `req/implementation-plan.md` | Accurate WP5.3 status and explicit remaining corpus follow-up. |

## Acceptance criteria

- The policy/NOTICE approval is committed with the Step3 resource and records all required
  provenance and model-card attribution.
- The resource exactly matches the pinned length and SHA-256; both Java and Node tests load that
  resource rather than a copied template string.
- The representative Step3 tool-use render is byte-exact under both hfjinja and the pinned Node
  oracle, and it demonstrably executes a macro plus the `mapping`, `tojson(ensure_ascii=False)`,
  and `.items()` paths.
- `mapping` and the full `tojson` option contract have focused Node-backed regressions, so the
  model fixture is not the only coverage of the new primitives.
- `./gradlew check upstreamVerify` passes with the required JDK/Node versions.
- WP5.3 is marked complete only for its macro-heavy clause; the document continues to identify the
  outstanding retained-model corpus import, WP5.4 fuzz/property suites, and WP5.5 AST cleanup.
