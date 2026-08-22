# hfjinja

`hfjinja` is a dependency-free Java 21+ implementation of the Hugging Face chat-template Jinja
subset. It evaluates a model's `tokenizer_config.json` `chat_template` without a JavaScript engine,
with compatibility pinned to a reviewed version of
[`@huggingface/jinja`](https://github.com/huggingface/huggingface.js/tree/main/packages/jinja).

It is designed for JVM model clients that need reproducible rendering of Llama, Qwen, Mistral, and
tool-use templates—not as a general-purpose or Python-compatible Jinja2 engine.

Building the project requires a locally installed JDK 21 available through `JAVA_HOME` or `PATH`.
Running `./gradlew check` also requires exactly Node.js 26.7.0, the pinned oracle version.

> Status: the repository is under active implementation. The intended v1 behavior and public API
> are described below; see the [implementation plan](req/implementation-plan.md) for progress.

## Features

- Native Java 21 implementation with no runtime dependencies, Node.js, JNI, or JS engine.
- Immutable parsed templates that are safe to render concurrently.
- Hugging Face Jinja syntax and runtime semantics: expressions, control flow, loops, filters,
  tests, macros, call/filter blocks, slices, keyword/spread arguments, `tojson`, and
  `raise_exception`.
- Byte-exact compatibility corpus generated from the pinned Node package, including whitespace and
  Unicode behavior.
- Closed host-value boundary: templates cannot reflect over Java objects, call Java methods, or
  access classes.
- Configurable parsing/rendering limits and location-aware errors.
- Deterministic time support through an explicit `Clock` and `ZoneId`.
- Reproducible upstream provenance: vendored upstream sources, package integrity, source hashes,
  mapping ledger, and offline verification.

## Installation

```kotlin
dependencies {
    implementation("se.alipsa:hfjinja:1.0.0")
}
```

The library is also a JPMS module:

```java
module example.app {
  requires se.alipsa.hfjinja;
}
```

## Quick start

```java
import java.util.List;
import java.util.Map;
import se.alipsa.hfjinja.Template;

var template = Template.parse("""
    {% for message in messages %}
    {{ message.role }}: {{ message.content }}
    {% endfor %}
    """);

var prompt = template.render(Map.of(
    "messages", List.of(
        Map.of("role", "user", "content", "Hello"),
        Map.of("role", "assistant", "content", "Hi!"))));
```

Parse once and reuse the `Template` from multiple threads. Both the `String` and `Appendable`
overloads buffer output; the latter performs one terminal append after a successful render. An
`Appendable` that fails while appending can still contain a partial terminal write.

```java
template.render(context, writer);
```

## Values and safety

Contexts accept strings, booleans, JSON-compatible `Number` values, arrays, `List<?>`, and
`Map<String, ?>`. A missing map key is template `undefined`; a present Java `null` is template
`null`. Unsupported values, non-string map keys, and cyclic graphs fail with
`TemplateRenderException` and `HOST_CONVERSION`.

The number boundary follows JavaScript's number model. It accepts a finite `Number` when its
shortest JavaScript round-trip representation preserves its canonical decimal text, and rejects
unsafe integers, non-finite input, and decimal narrowing. Runtime arithmetic still follows
JavaScript behavior, including non-finite computed results and precision loss.

## Host functions and deterministic time

Use `RenderOptions` to register explicitly named host functions. A template can invoke only these
registered functions and built-ins provided by the pinned upstream package—never arbitrary Java
methods or reflection.

```java
var options = RenderOptions.builder()
    .clock(Clock.fixed(Instant.parse("2025-01-02T03:04:05Z"), ZoneOffset.UTC))
    .zoneId(ZoneOffset.UTC)
    .hostFunction("format_tool", arguments -> "...")
    .build();

var output = template.render(context, options);
```

Time-dependent globals require an explicit clock and zone. If one is absent, rendering fails at
the first such use rather than reading the host wall clock. Date formatting uses fixed C/POSIX
month and weekday names rather than the host locale.

Host-function keyword arguments are not part of v1 and fail with `HOST_FUNCTION`. Exceptions from
host functions and invalid returned values are wrapped as `TemplateRenderException` with the call
site location.

## Errors and limits

All documented failures derive from `HfJinjaException`:

```java
try {
  template.render(context);
} catch (HfJinjaException error) {
  ErrorCategory category = error.category();
  error.location().ifPresent(System.err::println);
}
```

Categories cover syntax, undefined/access, type, arity, value, explicit raises, host functions,
host conversion, resource limits, and output failures. New categories may be added in minor
releases; consumers should include a `default` branch when switching on them.

`TemplateOptions` and `RenderOptions` configure source, token, AST-depth, render-step, loop,
macro-depth, and output-size limits. Resource-limit failures are distinct from compatibility
results. `Appendable` write failures are reported as `OUTPUT` errors.

## Compatibility and upstream updates

hfjinja targets one explicit upstream package revision, currently `@huggingface/jinja` 0.5.9.
Selected, policy-approved package source is committed under `upstream/vendor/`;
`upstream/upstream-lock.json` records the tarball integrity, commit, Node oracle version, global
inventory, source hashes, and policy exclusions.

```bash
./gradlew check
```

The command runs unit tests and `upstreamVerify`, which works without network access and fails on
changed vendor hashes, stale mappings, unaccounted AST nodes, or stale no-impact reviews. Updating
the pin is an explicit, reviewed sync change—not an incidental dependency upgrade.

The test build runs the pinned Node oracle over `src/test/resources/corpus/v1.jsonl`; this is a
test-time tool only, not a library runtime dependency. Hash-only records are intentionally skipped
by the normal build and require supplied external fixture material.
`build/reports/corpus-coverage.md` lists the upstream cases currently represented by the corpus.

Successful compatibility output compares byte-for-byte against the pinned Node package. Error
comparison uses documented categories only. Python `transformers`/Jinja2 output is not an oracle.

## Project layout

```text
src/main/java/se/alipsa/hfjinja/  public API
src/main/java/.../internal/       lexer, parser, AST, values, interpreter
src/test/                         unit, compatibility, safety, and concurrency tests
upstream/vendor/                  selected, policy-approved @huggingface/jinja source
upstream/upstream-lock.json       provenance and global inventory
upstream/mapping.yml              source-to-Java mapping ledger
req/                              requirements and implementation plan
```

## License

hfjinja is released under the [MIT License](LICENSE). It retains attribution for vendored upstream
MIT source in [NOTICE](NOTICE). Retained model-template fixtures include their applicable license
and notice requirements; where those terms make text vendoring unsuitable, the project uses
hash-only fixtures. See the [model fixture licensing policy](req/model-fixture-policy.md) for the
approved fixture forms.
