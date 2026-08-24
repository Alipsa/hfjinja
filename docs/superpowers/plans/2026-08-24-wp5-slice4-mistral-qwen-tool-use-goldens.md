# WP5 slice 4: Mistral and Qwen tool-use chat-template goldens

## Context

`req/implementation-plan.md`'s WP5 has 5 numbered items. Item 1 (macros, call/filter
blocks, keyword/spread args, slices, `raise_exception`) shipped across slices 1-3. Item 2
(date/format runtime helpers) was already fully implemented — `Interpreter.strftime()`
matches upstream's `strftime` byte-for-byte. This slice tackles item 3: **enable Mistral
and tool-use model goldens, including macro-heavy templates** — i.e. render real,
license-cleared model `chat_template` text end-to-end and lock it into the regression
corpus, rather than only synthetic self-authored templates.

`req/model-fixture-policy.md` pre-approves retaining literal template text (not just a
hash) for two repository/revision pairs under Apache-2.0:

- `mistralai/Mistral-7B-Instruct-v0.3` at `c170c708c41dac9275d15a8fff4eca08d52bab71`
- `Qwen/Qwen2.5-32B-Instruct` at `afb2829595f63efa3548e9d6b13aa66e61aa0f38`

Both templates were fetched from the pinned revisions and rendered against the pinned
Node oracle (`upstream/vendor/dist/index.cjs`, `@huggingface/jinja` 0.5.9) during planning
to determine real scope and produce verified expected output (see "Verification already
performed" below). Finding: **Qwen renders correctly today with zero interpreter
changes** — every construct it uses (`tojson`, `loop.first`/`loop.last`/`loop.index0`,
plain member/index access, boolean context flags) is already implemented. **Mistral needs
four new primitives**: the `selectattr`/`rejectattr` filters (and an internal `equalto`/`eq`
test-function registry they depend on), the `list` and `string` filters, and method-call
resolution for `tool.items()` on an `ObjectValue`. The user chose to bundle both models
into this one slice, since Qwen only adds fixture-wiring work, not interpreter risk.

No public API changes are needed: chat-template inputs (`messages`, `tools`, `bos_token`,
`eos_token`, `add_generation_prompt`) are just ordinary `Map<String,Object>` context
values passed to the existing `Template.render(context, options)`.

## New interpreter primitives (Mistral only)

All changes are in `src/main/java/se/alipsa/hfjinja/internal/runtime/Interpreter.java`.
Cross-checked against `upstream/vendor/src/runtime.ts`.

### 1. `list` filter

New `applyFilter` case, identity on `ArrayValue` (upstream `runtime.ts:1000-1001`:
`case "list": return operand;`), matching the existing `filterReceiver`/`requireNoArguments`
pattern used by `filterLength` etc.:

```java
case "list" -> filterList(operand, filter, location);
...
private static Value filterList(Value operand, NamedArguments filter, SourceLocation location) {
  requireNoArguments(filter, location);
  if (operand instanceof Value.ArrayValue array) return array;
  throw filterReceiver("list", operand, location);
}
```

### 2. `string` filter

Upstream (`runtime.ts:1016-1017,1062-1063,1085-1086`) is a no-op on `StringValue`, and for
Array/Object/Integer/Float/Boolean stringifies with `toJSON(operand, {}, 0, false)` — the
`false` is `convertUndefinedToNull`, which is exactly the 3-arg overload
`JsFormat.runtimeJson(value, location, false)` hfjinja already has (used internally by
`renderText`'s `renderJson` helper). Reuse `renderText()`, which already implements this
exact per-type stringification (`Boolean`→`"true"/"false"`, `Integer`→`plainString`,
`Float`→`renderFloat`, `Array`/`Object`→`renderJson` = `runtimeJson(v, l, false)`):

```java
case "string" -> filterToString(operand, filter, location);
...
private static Value filterToString(Value operand, NamedArguments filter, SourceLocation location) {
  requireNoArguments(filter, location);
  if (operand instanceof Value.StringValue string) return string;
  if (operand instanceof Value.ArrayValue
      || operand instanceof Value.ObjectValue
      || operand instanceof Value.IntegerValue
      || operand instanceof Value.FloatValue
      || operand instanceof Value.BooleanValue) {
    return new Value.StringValue(renderText(operand, location));
  }
  throw filterReceiver("string", operand, location);
}
```

(`Null`/`Undefined`/`Callable` operands are rejected with a TYPE error rather than routed
into `renderText`, which asserts on those cases — upstream has no `string` case for them
either.)

### 3. `selectattr` / `rejectattr` filters + internal `equalto`/`eq` test dispatch

Upstream (`runtime.ts:1271-1304`) filters an `ArrayValue` of `ObjectValue`s by a flat
attribute name, an optional named test, and an optional comparison value; the test
function is looked up directly from an internal test registry — **it does not go through
the parser's `is` grammar at all**. This means implementing `selectattr` does *not*
require the parser/AST extension for `is equalto(x)` syntax that
`docs/superpowers/plans/2026-08-23-wp4-slice3-filters-and-tests.md` explicitly deferred;
only a small internal name→test-function dispatcher is needed, used solely by these two
filters.

```java
case "selectattr" -> filterSelectAttr(operand, filter, location, true);
case "rejectattr" -> filterSelectAttr(operand, filter, location, false);
...
private static Value filterSelectAttr(
    Value operand, NamedArguments filter, SourceLocation location, boolean select) {
  if (!filter.keywords().isEmpty())
    throw new TemplateRenderException(
        "`" + filter.name() + "` filter does not accept keyword arguments",
        ErrorCategory.ARITY, location);
  if (filter.positional().isEmpty() || filter.positional().size() > 3)
    throw new TemplateRenderException(
        "`" + filter.name() + "` filter requires 1 to 3 arguments", ErrorCategory.ARITY, location);
  if (!(operand instanceof Value.ArrayValue array)) throw filterReceiver(filter.name(), operand, location);
  var attr = requireFilterString(filter, 0, location);
  String testName = filter.positional().size() > 1 ? requireFilterString(filter, 1, location).value() : null;
  Value comparison = filter.positional().size() > 2 ? filter.positional().get(2) : null;
  var result = new ArrayList<Value>();
  for (var item : array.values()) {
    if (!(item instanceof Value.ObjectValue object))
      throw new TemplateRenderException(
          "`" + filter.name() + "` can only be applied to array of objects", ErrorCategory.TYPE, location);
    var attrValue = object.values().get(attr.value());
    boolean matched = attrValue != null
        && (testName == null ? truthy(attrValue) : invokeNamedTest(testName, attrValue, comparison, location));
    if (matched == select) result.add(item);
  }
  return new Value.ArrayValue(result);
}

private static boolean invokeNamedTest(String name, Value value, Value comparison, SourceLocation location) {
  return switch (name) {
    case "equalto", "eq" -> {
      if (comparison == null)
        throw new TemplateRenderException(
            "`" + name + "` test requires a comparison value", ErrorCategory.ARITY, location);
      yield JsOperations.looseEquals(value, comparison);
    }
    default -> throw filterType("Unknown test: " + name, location);
  };
}
```

(`requireFilterString` is a small new helper mirroring the existing inline checks in
`filterJoin`/`filterDefault`: `filter.positional().get(i)` must be a non-undefined-backed
`Value.StringValue`, else throw `ErrorCategory.TYPE`.)

Known, documented gap vs. upstream (same style as the existing "Known gaps" comments from
slices 2-3): upstream additionally requires the filter's *AST arguments* to literally be
`StringLiteral` nodes, rejecting any non-literal expression even if it evaluates to a
string. hfjinja's filter-argument pipeline already evaluates all arguments eagerly before
dispatch (see the existing comment atop `applyFilter`), so this slice enforces "evaluates
to a `StringValue`" instead of "is syntactically a string literal." Not observable for the
Mistral/Qwen templates in this slice, which only ever pass literal strings.

`equalto`/`eq` reuse `JsOperations.looseEquals`, the same helper the `==`/`!=` binary
operator already uses (aligned with upstream equality semantics per the prior "Align mixed
nil equality" work) — not a new equality implementation.

### 4. `tool.items()` method-call resolution on `ObjectValue`

Upstream's `ObjectValue` carries a `builtins` map (`get`, `items`, `keys`, `values`,
`dictsort`) consulted as a fallback when a plain-value lookup misses
(`runtime.ts:1537`: `object.value.get(property.value) ?? object.builtins.get(property.value)`).
hfjinja's `Value.ObjectValue` has no such mechanism. The Mistral template only calls
`.items()` (`{%- for key, val in tool.items() if key != "return" %}`), so this slice adds
only that one fallback, directly in `member()`'s existing `ObjectValue` branch
(`Interpreter.java:661-665`), rather than building a general builtins registry for methods
nothing yet needs:

```java
if (target instanceof Value.ObjectValue x) {
  if (!(p instanceof Value.StringValue s))
    throw access("Cannot access property with non-string: got " + type(p), n.location());
  var key = objectKey(s);
  if (x.values().containsKey(key)) return x.values().get(key);
  if (!s.undefinedBacked() && "items".equals(s.value())) return objectItemsBuiltin(x);
  return Value.UndefinedValue.INSTANCE;
}
```

```java
private static Value objectItemsBuiltin(Value.ObjectValue object) {
  return new Value.CallableValue(
      (arguments, hasKeywords, location, environment) -> {
        var pairs = new ArrayList<Value>();
        for (var entry : object.values().entrySet()) {
          var key =
              entry.getKey() instanceof Value.StringValue string
                  ? string
                  : new Value.StringValue((String) entry.getKey());
          pairs.add(new Value.ArrayValue(List.of(key, entry.getValue())));
        }
        return new Value.ArrayValue(pairs);
      });
}
```

This mirrors upstream's `items(): ArrayValue` (`runtime.ts:481-485`): an `ArrayValue` of
two-element `[key, value]` `ArrayValue` pairs. hfjinja's existing tuple-unpack `bind()`
(`Interpreter.java:1076-1100`) already destructures `for key, val in ...` against any
`Value.ArrayValue` element (not just `TupleValue`), so `for key, val in tool.items()`
destructures correctly with no further change. Real value lookups still take precedence
over the `items` fallback, matching upstream's `??` precedence.

Explicitly out of scope for this slice (no template needs them yet, so not adding them
preempts nothing): the `| items` filter-pipe form, and the other `ObjectValue` builtins
(`get`, `keys`, `values`, `dictsort`).

## Corpus and test additions

### Verification already performed

Both pinned templates were fetched (`tokenizer_config.json`'s `chat_template` field) and
rendered through the pinned Node oracle during planning to get real, verified expected
output — not guessed. Representative cases and their oracle-confirmed output:

- Mistral, plain conversation (no tools): `<s>[INST] Hello![/INST] Hi there, how can I help?</s>`
- Mistral, tool-use conversation (exercises `selectattr`+`list`, `tool.items()`, `is string`,
  `|tojson`, `[:-1]` slicing, `|length`, the new `string` filter): full transcript with
  `[AVAILABLE_TOOLS]`/`[TOOL_CALLS]`/`[TOOL_RESULTS]` sections, captured verbatim during
  planning.
- Mistral, alternating-role violation: `raise_exception("After the optional system
  message, conversation roles must alternate user/assistant/user/assistant/...")`
  (`ErrorCategory.EXPLICIT_RAISE`) — exercises the existing `raise_exception` path.
- Qwen, plain conversation with `add_generation_prompt: true`, and Qwen tool-use
  conversation: both confirmed rendering correctly today with **no interpreter changes**.

These exact contexts/outputs are what get embedded in the new corpus records and the
matching `InterpreterTest` assertions — no re-deriving expected values during
implementation.

### Corpus records (`src/test/resources/corpus/v1.jsonl`)

The schema (`tools/corpus/corpus.mjs`) forbids `modelRepo`/`modelRevision`/`templatePath`
on any record that carries literal `template` text (those fields are reserved for
hash-only records, which `run-node-oracle.mjs` skips entirely and never renders). So
provenance for these two retained-text records goes in the free-text `source` field,
following the existing `"self-authored; verified against @huggingface/jinja 0.5.9"`
convention:

```json
{"id":"model.mistral-7b-instruct-v0.3-plain","source":"mistralai/Mistral-7B-Instruct-v0.3 chat_template at revision c170c708c41dac9275d15a8fff4eca08d52bab71 (Apache-2.0); verified against @huggingface/jinja 0.5.9; see req/model-fixture-policy.md","template":"<full retained template text>","context":{"bos_token":"<s>","eos_token":"</s>","messages":[...]},"expected":{"text":"<s>[INST] Hello![/INST] Hi there, how can I help?</s>"}}
```

Five new records total: 3 Mistral (plain, tool-use, alternating-role error) + 2 Qwen
(plain with `add_generation_prompt`, tool-use). `id`s prefixed `model.` to distinguish
from the existing `self.` records (not schema-enforced, just a readability convention).
Each record's `template` field holds the exact fetched text; each of the two models needs
the full ~2.5-4KB template repeated across its records (the schema has no shared/`$ref`
mechanism), matching how existing multi-record corpus entries already duplicate template
text per record.

### `InterpreterTest.java`

Per this project's established (manual, not mechanically linked) pattern, add one
`@Test` per corpus record with the same template/context/expected-output triple, plus
narrower unit tests for the new primitives in isolation: `selectattr`/`rejectattr` with
and without a test name, `equalto` via `selectattr`, `list` filter identity and its
type-error case, `string` filter across `StringValue`/`ArrayValue`/`IntegerValue`, and
`tool.items()` iterated with `for key, val in ...`.

## NOTICE and policy bookkeeping

Replace the placeholder row in `NOTICE`'s `## Model fixtures` table with two rows (one per
model), each recording repository, pinned revision, `tokenizer_config.json` (`chat_template`
field) as the template path, Apache-2.0 license, "full template text retained in
`src/test/resources/corpus/v1.jsonl`" as the retained form, and a reference to
`req/model-fixture-policy.md` for the notice/attribution basis — matching the table's
existing column format and the sources already reviewed and cited in
`req/model-fixture-policy.md`'s "Sources reviewed" section (no new legal review needed;
both repositories/revisions are already pre-approved there).

`upstream/upstream-lock.json`'s `fixtureRevision` field (currently
`"no-imported-fixtures"`) and its schema have no existing `modelFixtures` section or
precedent for one — leave it untouched; provenance lives in `NOTICE` and each corpus
record's `source` field instead, consistent with how the schema is actually structured
today.

## Reproducing the fetch (for the user, on a machine with normal network access)

The template text above was already fetched and verified during planning. To reproduce or
re-verify independently:

```bash
curl -L -o /tmp/mistral_tokenizer_config.json \
  https://huggingface.co/mistralai/Mistral-7B-Instruct-v0.3/raw/c170c708c41dac9275d15a8fff4eca08d52bab71/tokenizer_config.json
curl -L -o /tmp/qwen_tokenizer_config.json \
  https://huggingface.co/Qwen/Qwen2.5-32B-Instruct/raw/afb2829595f63efa3548e9d6b13aa66e61aa0f38/tokenizer_config.json
```

Then extract the `chat_template` field from each (a plain JSON string field) — this is the
exact text to paste into the corpus records and `NOTICE`. No special network configuration
is assumed here; the `--interface en7` workaround used only in this planning sandbox is
not part of the implementation.

## AST allowlist

No change. Per the precedent already established in the slice3 plan ("Macro,
FilterStatement, CallStatement intentionally left at M3, not modified"), removing AST
allowlist exemptions is WP5 item 5's job at the end of the work package, not something
each slice does incrementally.

## Verification

1. `./gradlew spotlessApply` after each Java change.
2. `./gradlew test` — all new `InterpreterTest` cases green, no regressions.
3. `./gradlew nodeCorpusVerify` (or the equivalent Gradle task that runs
   `tools/corpus/run-node-oracle.mjs`) — confirms schema validity of the 5 new records
   (hash-only-only checks don't apply here since these are text records) and, for any
   future hash-only record, oracle parity; for these text records the real parity check is
   the manually-synchronized `InterpreterTest` assertions per the project's established
   pattern.
4. `./gradlew build` full build green.
5. Manually re-run the two representative Node-oracle renders captured during planning
   against `Template.render()` in a throwaway Java scratch check (or via the new
   `InterpreterTest` cases directly) to confirm byte-identical output.

## Known gaps this slice leaves open

- `selectattr`/`rejectattr` accept any expression that evaluates to a string where
  upstream requires a literal `StringLiteral` AST node (see above).
- Only the `equalto`/`eq` named tests are wired into `selectattr`/`rejectattr`; other
  upstream tests (`callable`, `odd`, `even`, `mapping`, `lower`, `upper`, etc.) remain
  unimplemented until a template needs them there.
- Only `.items()` is added as an `ObjectValue` method-call fallback; `get`/`keys`/`values`/
  `dictsort` and the `| items` filter-pipe form are not added.
- AST allowlist exemptions for already-implemented M3 nodes remain untouched (WP5 item 5).
