# WP5 slice 4: Mistral and Qwen tool-use chat-template goldens

## Context

`req/implementation-plan.md`'s WP5 has 5 numbered items. Item 1 (macros, call/filter
blocks, keyword/spread args, slices, `raise_exception`) shipped across slices 1-3. Item 2
(date/format runtime helpers) was already fully implemented — `Interpreter.strftime()`
matches upstream's `strftime` byte-for-byte. This slice makes progress on item 3: **enable Mistral
and tool-use model goldens, including macro-heavy templates** — i.e. render real,
license-cleared model `chat_template` text end-to-end and lock it into the regression
corpus, rather than only synthetic self-authored templates.

`req/model-fixture-policy.md` pre-approves retaining literal template text (not just a
hash) for two repository/revision pairs under Apache-2.0:

- `mistralai/Mistral-7B-Instruct-v0.3` at `c170c708c41dac9275d15a8fff4eca08d52bab71`
- `Qwen/Qwen2.5-32B-Instruct` at `afb2829595f63efa3548e9d6b13aa66e61aa0f38`

Both templates were fetched from the pinned revisions and rendered against the pinned
Node oracle (`upstream/vendor/dist/index.js` — the file `run-node-oracle.mjs` actually
imports; `dist/index.cjs` also exists but is not what the oracle loads —
`@huggingface/jinja` 0.5.9) during planning to determine real scope and produce verified
expected output (see "Verification already performed" below). Finding: **Qwen renders
correctly today with zero interpreter changes** — every construct it uses (`tojson`,
`loop.first`/`loop.last`/`loop.index0`, plain member/index access, boolean context flags)
is already implemented. **Mistral needs four new primitives**: the
`selectattr`/`rejectattr` filters (and an internal `equalto`/`eq` test-function registry
they depend on), the `list` and `string` filters, and method-call resolution for
`tool.items()` on an `ObjectValue`. The user chose to bundle both models into this one
slice, since Qwen only adds fixture-wiring work, not interpreter risk.
Neither approved template contains `{% macro %}`, so this slice does **not** close WP5
item 3's "including macro-heavy templates" clause. Completing it requires a separately
reviewed fixture-policy row for a pinned model/revision with a macro-heavy template (and
its source record, license notice, and model-card attribution); no such pair is
pre-approved today. `req/implementation-plan.md` must therefore continue to show item 3
as in progress after this slice.

### Mistral constructs that already work (verified, no code needed)

Three further load-bearing constructs in the Mistral template are already implemented.
They need no changes, but two of them constrain the code below, so they are recorded here
rather than left implicit:

- `{%- set ns = namespace() %}` — `Environment.java:21` registers `namespace` as a
  `CallableValue` returning a plain `ObjectValue`.
- `{%- set ns.index = ns.index + 1 %}` — member-target assignment, `Interpreter.java:980`.
- `{%- if tools is not none and (message == user_messages[-1]) %}` — **object identity**
  comparison, and the sole gate on the entire `[AVAILABLE_TOOLS]` block. Upstream compares
  `left.value == right.value`, i.e. two `Map` references (`runtime.ts:847`); hfjinja's
  `looseEquals` falls through to `strictValueEquals`, which ends in `left == right`
  reference identity for objects (`JsOperations.java:144`). These agree **only because
  both sides hold the same instance**. This imposes a hard invariant on `filterSelectAttr`
  below: it must append the original `item` reference, never a copy or a rebuilt
  `ObjectValue`. Note `Value.ObjectValue` also overrides `equals()` structurally, so a
  refactor that reaches for `.equals()` here would silently make every user message with
  identical content compare equal and emit `[AVAILABLE_TOOLS]` more than once.

No public API changes are needed: chat-template inputs (`messages`, `tools`, `bos_token`,
`eos_token`, `add_generation_prompt`) are just ordinary `Map<String,Object>` context
values passed to the existing `Template.render(context, options)`.

## New interpreter primitives (Mistral only)

Java changes are confined to two files, both cross-checked against
`upstream/vendor/src/runtime.ts`:

- `src/main/java/se/alipsa/hfjinja/internal/runtime/Interpreter.java` — the four
  primitives below.
- `src/main/java/se/alipsa/hfjinja/internal/runtime/JsOperations.java` — one added
  package-visible `strictEquals` wrapper (see `equalto` below).

Two non-Java files also change: `tools/corpus/error-patterns-0.5.9.json` (required — see
"Error-pattern table") and `NOTICE`.

### 1. `list` filter

New `applyFilter` case, identity on `ArrayValue` (upstream `runtime.ts:1000-1001`:
`case "list": return operand;`), matching the existing `filterReceiver`/`requireNoArguments`
pattern used by `filterLength` etc.

`TupleValue` must be accepted too: upstream declares `class TupleValue extends ArrayValue`
(`runtime.ts:535`), so the whole `ArrayValue` filter branch — `list` included — applies to
tuples. hfjinja models `TupleValue` as a sibling record rather than a subtype, so every
array-shaped filter has to name it explicitly; `filterLength` (`Interpreter.java:358-359`)
and `filterJoin` (`:396-397`) already do exactly this, and `list` follows them:

```java
case "list" -> filterList(operand, filter, location);
...
private static Value filterList(Value operand, NamedArguments filter, SourceLocation location) {
  requireNoArguments(filter, location);
  if (operand instanceof Value.ArrayValue array) return array;
  if (operand instanceof Value.TupleValue tuple) return tuple;
  throw filterReceiver("list", operand, location);
}
```

(Upstream's `list` lives only in the `ArrayValue` branch, so `"abc"|list` and
`{...}|list` throw there as they do here — the Python-Jinja behaviours of splitting a
string into characters or a dict into keys are not implemented upstream and are not added
here.)

### 2. `string` filter

Upstream implements `string` per operand type, and **not for every type**:

| Operand | Upstream | Reference |
| --- | --- | --- |
| `StringValue` | no-op, returns operand | `runtime.ts:1062-1063` |
| `ArrayValue` | `toJSON(operand, {}, 0, false)` | `runtime.ts:1016-1017` |
| `TupleValue` | throws `Cannot convert to JSON: TupleValue` (its runtime `type` is distinct) | `runtime.ts:386-388` |
| `IntegerValue` / `FloatValue` | `operand.toString()` | `runtime.ts:1085-1086` |
| `BooleanValue` | `"true"` / `"false"` | `runtime.ts:1118-1119` |
| **`ObjectValue`** | **throws** `Unknown ObjectValue filter: string` | `runtime.ts:1090-1108` |
| `NullValue` / `UndefinedValue` / `FunctionValue` | throws `Cannot apply filter …` | `runtime.ts:1123` |

**`ObjectValue` must be rejected.** The ObjectValue filter branch handles only `items` and
`length`, then falls through to `operand.builtins.get(filterName)` — whose keys are
`get`/`items`/`keys`/`values`/`dictsort` — and throws when that misses. `ObjectValue` does
define `toString()` as `toJSON(this, {}, 0, false)` (`runtime.ts:492`), but that is the
`toString` method, not the `string` filter, and nothing routes the filter to it. This is
load-bearing for Mistral, not academic: `content|string` is reached with
`content = message.content` whenever a `tool`/`tool_results` message's `content` is a dict
with no nested `.content` key, so accepting `ObjectValue` would emit JSON where upstream
raises. `KeywordArgumentsValue` is likewise rejected — upstream's extends `ObjectValue`
and so throws for the same reason.

For the accepted types, `renderText()` already implements exactly the right
stringification (`Boolean`→`"true"/"false"`, `Integer`→`plainString`, `Float`→
`renderFloat` = `JsFormat.floatString`, matching upstream's
`value % 1 === 0 ? toFixed(1) : toString()` at `runtime.ts:98-100`, `Array`/`Tuple`→
`renderJson` = `runtimeJson(v, l, false)`, whose `false` is the `convertUndefinedToNull`
argument upstream passes):

```java
case "string" -> filterToString(operand, filter, location);
...
private static Value filterToString(Value operand, NamedArguments filter, SourceLocation location) {
  requireNoArguments(filter, location);
  if (operand instanceof Value.StringValue string) return string;
  if (operand instanceof Value.ArrayValue
      || operand instanceof Value.IntegerValue
      || operand instanceof Value.FloatValue
      || operand instanceof Value.BooleanValue) {
    return new Value.StringValue(renderText(operand, location));
  }
  throw filterReceiver("string", operand, location);
}
```

(`Object`/`KeywordArguments`/`Null`/`Undefined`/`Callable` operands all reach the
`filterReceiver` TYPE error, matching the upstream throws in the table above. The
`Null`/`Undefined` cases additionally must not be routed into `renderText`, which asserts
on them.)

Note the `StringValue` no-op returns undefined-backed strings unchanged, where sibling
filters (`filterString` for `lower`/`upper`/`trim`, `filterJoin`) reject them. That is
deliberate — upstream's `string` is a bare `return operand` with no undefined check — but
call it out in the code comment so it does not read as an oversight.

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
  // Filter arguments have already been evaluated by hfjinja before this method is called.
  // After the keyword/arity checks above, reject a non-array receiver before inspecting
  // the attribute/test arguments, matching upstream's receiver branch.
  List<Value> values;
  if (operand instanceof Value.ArrayValue array) values = array.values();
  else if (operand instanceof Value.TupleValue tuple) values = tuple.values();
  else throw filterReceiver(filter.name(), operand, location);
  // Upstream validates that every element is an object before reading the arguments.
  for (var item : values)
    if (!(item instanceof Value.ObjectValue))
      throw new TemplateRenderException(
          "`" + filter.name() + "` can only be applied to array of objects", ErrorCategory.TYPE, location);
  var attr = requireFilterString(filter, 0, location);
  String testName = filter.positional().size() > 1 ? requireFilterString(filter, 1, location).value() : null;
  // Upstream destructures [attr, testName, value] from the argument list, so an absent
  // third argument reaches the test function as JS `undefined` — not as an error.
  Value comparison =
      filter.positional().size() > 2 ? filter.positional().get(2) : Value.UndefinedValue.INSTANCE;
  var result = new ArrayList<Value>();
  for (var item : values) {
    var object = (Value.ObjectValue) item;
    var attrValue = object.values().get(attr.value());
    boolean matched = attrValue != null
        && (testName == null ? truthy(attrValue) : namedTest(testName, attrValue, comparison, location));
    // Must append `item` itself: see "Mistral constructs that already work" — the template
    // compares message identity, so a copy here would break `message == user_messages[-1]`.
    if (matched == select) result.add(item);
  }
  return new Value.ArrayValue(result);
}

private static boolean namedTest(
    String name, Value value, Value comparison, SourceLocation location) {
  return switch (name) {
    case "equalto", "eq" -> JsOperations.strictEquals(value, comparison);
    case "defined" -> !undefinedLike(value);
    case "undefined" -> undefinedLike(value);
    case "none" -> value instanceof Value.NullValue;
    case "boolean" -> value instanceof Value.BooleanValue;
    case "number" -> JsOperations.numeric(value);
    case "string" -> value instanceof Value.StringValue string && !string.undefinedBacked();
    case "iterable" ->
        value instanceof Value.ArrayValue
            || value instanceof Value.StringValue string && !string.undefinedBacked();
    case "sequence" ->
        value instanceof Value.ArrayValue
            || value instanceof Value.TupleValue
            || value instanceof Value.ObjectValue
            || value instanceof Value.StringValue string && !string.undefinedBacked();
    default -> throw filterType("Unknown test: " + name, location);
  };
}
```

This is one shared registry, not a second `selectattr`-only list: refactor
`Interpreter.test()` to call `namedTest(name, value, comparison, location)` too. Its
existing `defined`/`undefined`/`none`/`boolean`/`number`/`string`/`iterable`/`sequence`
cases move unchanged into that dispatcher; `equalto` and `eq` are the only names that use
the optional second operand. Thus `x is string` and `selectattr("x", "string")` have the
same test semantics and future additions have one home.

`attrValue != null` is the faithful translation of upstream's `a ? testFunction(a, value) : false`
(`runtime.ts:1298`): `a` is a runtime value *object*, so it is JS-truthy whenever the key
exists and `undefined` only when it is missing — this is a key-presence check, not a
truthiness check on the attribute.

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

#### `equalto`/`eq` is **strict** equality, not the `==` operator

These are two different upstream operators and must not share an implementation:

- `==` / `!=` → `left.value == right.value`, JS **loose** equality with coercion
  (`runtime.ts:847-850`). This is what `JsOperations.looseEquals` already models.
- `equalto` / `eq` → `(a, b) => a.value === b.value`, JS **strict** equality
  (`runtime.ts:635-636`).

Using `looseEquals` for `equalto` would diverge wherever coercion applies —
`selectattr("n", "equalto", 1)` against `"1"`, or `true` against `1`, would match here and
not upstream. Not reachable from the Mistral template (which compares string to string),
but it would be a latent parity bug committed under a claim of correctness.

The exact rule is "compare the JS payload with `===`". `JsOperations` already has the two
halves of that; they only need composing and exposing:

```java
// JsOperations — strictValueEquals is currently private; make this wrapper package-visible.
static boolean strictEquals(Value left, Value right) {
  // NullValue, UndefinedValue and undefined-backed StringValue all carry the JS payload
  // `undefined` upstream (RuntimeValue's constructor defaults `value` to undefined, and
  // NullValue is always built as `new NullValue()` — runtime.ts:549-558), so `===` holds
  // across all three.
  if (nilLike(left) || nilLike(right)) return nilLike(left) && nilLike(right);
  return strictValueEquals(left, right);
}
```

`strictValueEquals` (`JsOperations.java:138-145`) already gives the rest exactly: numeric
compares payloads so `IntegerValue(1)`/`FloatValue(1.0)` match as upstream does; boolean
and string compare by value; everything else falls to `left == right` reference identity,
which matches upstream comparing `Map`/array references, and works for `NullValue` /
`UndefinedValue` because hfjinja models both as enum singletons. Note this is the same
reference-identity path the `[AVAILABLE_TOOLS]` gate depends on.

This is deliberately *not* `looseEquals` minus a line: it is `looseEquals` with the
coercion branches removed, which is precisely the `==` vs `===` distinction above.

### 4. `tool.items()` method-call resolution on `ObjectValue`

Upstream's `ObjectValue` carries a `builtins` map (`get`, `items`, `keys`, `values`,
`dictsort`) consulted as a fallback when a plain-value lookup misses
(`runtime.ts:1537`: `object.value.get(property.value) ?? object.builtins.get(property.value)`).
hfjinja's `Value.ObjectValue` has no such mechanism. The Mistral template only calls
`.items()` (`{%- for key, val in tool.items() if key != "return" %}`), so this slice adds
only that one fallback, directly in `member()`'s existing `ObjectValue` branch
(`Interpreter.java:660-665`), rather than building a general builtins registry for methods
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
two-element `[key, value]` `ArrayValue` pairs. The key-coercion ternary follows the
existing precedent at `Interpreter.java:1031-1035`, where `for k in obj` already maps the
`Map<Object, Value>` key domain back to `StringValue` the same way. hfjinja's existing
tuple-unpack `bind()` (`Interpreter.java:1076-1100`) destructures `for key, val in ...`
against any `Value.ArrayValue` element (not just `TupleValue`), and the for-loop `if`
filter binds the tuple names into `filterScope` *before* evaluating the test
(`Interpreter.java:1046-1050`), so `for key, val in tool.items() if key != "return"`
works with no further change. Real value lookups still take precedence over the `items`
fallback, matching upstream's `??` precedence — upstream's object maps hold runtime-value
instances rather than JS `null`/`undefined`, so `??` there is equivalent to key-absence.

**This changes existing behaviour, in the parity-improving direction.** For any object
without an `items` key, `obj.items` goes from `UndefinedValue` to a truthy
`CallableValue`, so `obj.items is defined` flips `false` → `true`. That matches upstream
(`runtime.ts:1537`), so it is a fix rather than a regression, but it is a live behaviour
change and needs a test pinning it deliberately rather than being absorbed silently.

Explicitly out of scope for this slice (no template needs them yet, so not adding them
preempts nothing): the `| items` filter-pipe form, the other `ObjectValue` builtins
(`get`, `keys`, `values`, `dictsort`), and the same fallback on
`Value.KeywordArgumentsValue` — upstream's `KeywordArgumentsValue extends ObjectValue`
(`runtime.ts:500-502`) so it inherits the builtins there, whereas hfjinja's is a separate
record and keeps its current lookup.

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

#### What survived planning, and what must be rebuilt

The planning scratchpad and extracted artefacts are no longer available. **The context
payloads were never written down**, and the rendered outputs above exist only as the
summaries in this section. The implementation session must therefore re-fetch both
templates from Hugging Face and re-derive expected outputs with the oracle. So:

- **Template text** — recoverable via the `curl` commands under "Reproducing the fetch";
  verify the extracted JavaScript-string lengths are exactly **3,959 characters**
  (Mistral) and **2,509 characters** (Qwen) before committing them.
- **Expected outputs** — re-derivable by running the oracle, and in fact `nodeCorpusVerify`
  re-derives and checks them on every `check` (see "Verification"). Nothing is lost here.
- **Contexts** — *not* recoverable. They are authored inputs, not fetchable from
  Hugging Face, and they are what decides whether each fixture exercises the primitive it
  is named for.

Because the oracle will happily bless whatever contexts the implementation invents, the
risk is not a wrong expected value — it is a fixture that passes every gate while testing
nothing. Each Mistral record therefore has an acceptance criterion on the *shape* of its
context, which must hold regardless of the exact wording chosen:

| Record | The context must contain | Otherwise |
| --- | --- | --- |
| tool-use | ≥2 user messages and non-null `tools` | `message == user_messages[-1]` is trivially true and `selectattr`/`list`/identity are untested |
| tool-use | two user messages with **identical content** | a structural-equality regression in `selectattr` would emit `[AVAILABLE_TOOLS]` twice and no test would catch it |
| tool-use | a `tools[n].function` dict containing a `"return"` key | the `if key != "return"` loop filter is never exercised |
| tool-use | that same dict mixing string and non-string values | the `val is string` / `val\|tojson` branch split is never exercised |
| tool-use | `tool_call.id` and `tool_call_id` exactly 9 characters | the template raises instead of rendering |
| tool-use | a chosen, explicit type for the `tool_results` `message.content` | decides whether `content\|string` hits the rejected `ObjectValue` path (see the `string` filter above) — pin this deliberately |
| alternating-role error | two consecutive `user` messages, no `tool_calls` | the `ns.index % 2` check passes and no exception is raised |

Embed the final contexts in the corpus records as literal JSON — once committed they are
the durable artefact, and this table becomes a review checklist rather than a standing
requirement.

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
Each record's `template` field holds the exact fetched text; the schema has no shared/`$ref`
mechanism, so it repeats the Mistral template three times and Qwen template twice (about
17 KB in `v1.jsonl`). Avoid a second five-copy set in Java: retain each fetched template
once as a named test resource (`.jinja`), and have `InterpreterTest` read that resource
instead of embedding template strings. Add a `corpus.test.mjs` assertion that every
`model.mistral-*`/`model.qwen-*` record's `template` equals its corresponding resource
byte-for-byte. This both guards the three Mistral copies and makes the resource the
reviewable canonical fetched byte sequence. Java does not need to parse corpus JSON for
this: the existing test classpath is JUnit-only, while the corpus check can compare native
JSON strings cheaply.

### Error-pattern table (`tools/corpus/error-patterns-0.5.9.json`) — required

**The alternating-role record fails `check` without this change.** The table currently
holds five patterns and none maps to `EXPLICIT_RAISE`. `errorClassifier` throws
`Unmatched upstream error for …` when no pattern matches (`corpus.mjs:106`), which
`run-node-oracle.mjs` reports as a `FAIL` — so the record breaks the build rather than
being skipped. `EXPLICIT_RAISE` *is* in the schema's accepted category set
(`corpus.mjs:4-7`); it simply has no classifier entry yet.

Upstream's `raise_exception` rethrows the template's message verbatim, so the pattern has
to match Mistral's literal string:

```json
{
  "regex": "^After the optional system message, conversation roles must alternate user/assistant/user/assistant/\\.\\.\\.$",
  "category": "EXPLICIT_RAISE"
}
```

This is the first fixture-specific regex in a table that is otherwise version-scoped to
upstream's own error messages, and it will not be the last — every model template raises
its own strings. Decide the convention now rather than at implementation time. The cheaper
alternative, if a per-template regex per fixture is unappealing, is one anchored pattern
covering the shape of raised messages; but that risks masking genuinely unclassified
upstream errors, so the narrow literal is the safer default for this slice. The validator
at `corpus.mjs:93` requires only `regex` and `category` and ignores additional keys;
give this entry a `comment` explaining that it classifies a model-template raise in a file
otherwise scoped to upstream error messages.

### `InterpreterTest.java`

Per this project's established (manual, not mechanically linked) pattern, add one
`@Test` per corpus record with the same template/context/expected-output triple, plus
narrower unit tests for the new primitives in isolation:

- `selectattr`/`rejectattr` with and without a test name, on both `ArrayValue` and
  `TupleValue`; tuple input returns an `ArrayValue` as upstream does. Cover `equalto` and
  a pre-existing interpreter test such as `string` through `selectattr`, proving the
  shared registry rather than a duplicate list.
- **`selectattr` preserves element identity** — two objects with identical content, then
  assert `x == selected[-1]` is true for the last only. This is the regression test for the
  `[AVAILABLE_TOOLS]` gate.
- **`equalto` is strict** — `selectattr("n", "equalto", 1)` must *not* match `"1"`, and
  must not match `true`. Locks the `==` vs `===` distinction.
- **`equalto` with no comparison value** raises `TYPE`, matching upstream's missing-second-
  argument failure; an explicitly evaluated undefined value remains a comparison value.
- `list` filter identity on `ArrayValue` **and `TupleValue`**, plus its type-error case.
- `string` filter across `StringValue`/`ArrayValue`/`TupleValue`/`IntegerValue`/
  `FloatValue`/`BooleanValue`, and the `ObjectValue` type-error case.
- `tool.items()` iterated with `for key, val in ...`, including the `if key != "return"`
  filtered form.
- **`obj.items is defined` is now `true`** for an object with no `items` key — pins the
  behaviour change noted in section 4.

## NOTICE and policy bookkeeping

Replace the placeholder row in `NOTICE`'s `## Model fixtures` table with two rows (one per
model), each recording repository, pinned revision, `tokenizer_config.json` (`chat_template`
field) as the template path, Apache-2.0 license, "full template text retained in
`src/test/resources/corpus/v1.jsonl`" as the retained form, and a **model-card attribution
URL**. Add a `Model-card attribution` column to the NOTICE table explicitly; a reference
to the policy is not a substitute for the actual attribution required by its approval row.

In the same commit, update `NOTICE:6` from "No model chat-template text is vendored yet"
and replace `req/model-fixture-policy.md`'s `## Current fixture set` assertion that no
model template text or model-derived output is committed. The revised policy text must
name these first retained Qwen and Mistral cases, link their source records/notices and
model cards, and confirm that their additions satisfy the policy's same-change rule.
These are factual updates, not optional follow-up bookkeeping. Also add the pinned model
card URLs to the policy's "Sources reviewed" section and use those exact links in NOTICE:
`https://huggingface.co/Qwen/Qwen2.5-32B-Instruct/blob/afb2829595f63efa3548e9d6b13aa66e61aa0f38/README.md`
and
`https://huggingface.co/mistralai/Mistral-7B-Instruct-v0.3/blob/c170c708c41dac9275d15a8fff4eca08d52bab71/README.md`.
No new legal review is needed: both repository/revision pairs are already pre-approved.

`upstream/upstream-lock.json`'s `fixtureRevision` field (currently
`"no-imported-fixtures"`) and its schema have no existing `modelFixtures` section or
precedent for one — leave it untouched; provenance lives in `NOTICE` and each corpus
record's `source` field instead, consistent with how the schema is actually structured
today.

## Reproducing the fetch (for the user, on a machine with normal network access)

The planning artefacts are gone, so the implementation session must fetch the text again.
Network access to `huggingface.co` is a prerequisite; it works from this machine without
an `--interface` workaround. Fetch it as follows, then confirm the extracted string
lengths (Mistral 3,959; Qwen 2,509 characters) before use:

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
3. `./gradlew nodeCorpusVerify` — this **is** the automated oracle parity check for the
   five new records, not merely a schema check. The task is described as "Checks
   text-bearing corpus cases against the pinned Node oracle" (`build.gradle:107-121`); it
   runs each text-bearing record through `@huggingface/jinja` 0.5.9 and diffs the result
   against `expected.text`, or classifies the thrown message against `expected.errorCategory`
   (`run-node-oracle.mjs:38-70`). Only `templateSha256` records are skipped. It is already
   a `check` dependency, so these records are covered by CI from the moment they land.
4. `./gradlew build` full build green.

The `InterpreterTest` cases remain the check that *hfjinja* matches the same expected
values — the division of labour is: `nodeCorpusVerify` pins expected-vs-upstream,
`InterpreterTest` pins expected-vs-hfjinja, and the shared literal in the corpus record
joins them. A separate throwaway Java scratch render is redundant with step 2 and is not
part of this plan. Error parity in this slice is **category-level only**: for example,
hfjinja's `ObjectValue|string` currently says `Cannot apply filter \`string\` to type:
ObjectValue`, while the oracle says `Unknown ObjectValue filter: string`; similarly
`"abc"|list` differs in wording. Do not describe either as a message-parity assertion.
Because the corpus stores only an error category, these cases cannot serve as corpus
records that prove message equality; keep any message comparison out of this slice (or
add a dedicated message-parity mechanism in a later, explicitly scoped change).

## Known gaps this slice leaves open

- `selectattr`/`rejectattr` accept any expression that evaluates to a string where
  upstream requires a literal `StringLiteral` AST node (see above).
- `selectattr`/`rejectattr` enforce a 1-to-3 argument cap; upstream has no arity check at
  all — it destructures `[attr, testName, value]`, silently ignoring a 4th argument and
  failing with a raw JS `TypeError` on zero. The cap follows the house style already
  documented for `filterJoin` ("per-filter arity caps"), and the resulting `ARITY` error is
  not classifiable against upstream's message, so it must not appear in a corpus record.
- `selectattr`/`rejectattr` argument-error *precedence* differs: the array-of-objects check
  runs after hfjinja's keyword/arity checks but before attribute/test validation (filter
  arguments were already eagerly evaluated before dispatch); a non-string argument still surfaces hfjinja's
  `TYPE` error from `requireFilterString` rather than upstream's
  ``arguments of `selectattr` must be strings``.
- The shared dispatcher covers the tests already supported by `Interpreter.test()` plus
  `equalto`/`eq`; other upstream tests (`callable`, `odd`, `even`, `mapping`, `lower`,
  `upper`, etc.) remain unimplemented until mapped work requires them.
- Only `.items()` is added as an `ObjectValue` method-call fallback; `get`/`keys`/`values`/
  `dictsort`, the `| items` filter-pipe form, and the same fallback on
  `KeywordArgumentsValue` are not added.
- The `string` filter's `StringValue` branch passes undefined-backed strings through
  unchanged, where `filterString` and `filterJoin` reject them. This follows upstream's
  bare `return operand`, but it is an inconsistency within hfjinja's own filter set.
- AST allowlist exemptions for already-implemented M3 nodes remain untouched (WP5 item 5).
