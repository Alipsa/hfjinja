# WP6 Slice 3 — Qwen3.8 MLX fixture and runtime parity

## Outcome

Retain the reviewed Apache-2.0 `mlx-community/Qwen3.8-27B-4bit` chat template at commit
`3e6447f082e89cc7f0bc6e5441afd38dfce760ff`, with policy/NOTICE attribution and byte-pinned
resource-backed Java and Node-oracle goldens for text, vision, and tool-use rendering.

## Runtime additions

The fixture required bare `safe`, `items`, `is true`/`is false`, and string `startswith`/`endswith`.
The accompanying runtime inventory audit completed the remaining ordinary pinned-runtime filters,
tests, string members, and object members. Isolated corpus cases pin the Qwen-required operations
and the `startswith`/`endswith` arity and type categories.

## Known intentional divergences

- `items(1)` is rejected with `ARITY`; the pinned upstream ignores extra filter arguments. This is
  consistent with the existing deliberate per-filter arity-cap policy.
- Call-form `safe(...)` and `items(...)` error messages use hfjinja's common filter wording rather
  than upstream's receiver-type-specific wording. The differential contract normalizes comparable
  errors by category, not exact message.
- Java render budgets and hostile-recursion handling remain safety behavior rather than Node parity
  outcomes.

## Verification

Run `./gradlew spotlessApply`, focused interpreter and Node corpus tests, `./gradlew upstreamVerify`,
`./gradlew check`, and `git diff --check`. `nodeCorpusTest` declares the complete model-template
resource directory as an input so resource golden edits invalidate its marker.
