# Model fixture licensing policy

This policy satisfies WP1a before the differential corpus imports any model chat-template text.
It is a repository intake policy, not legal advice. Every future fixture change must identify its
exact model repository, immutable revision, template path, and applicable license in `NOTICE`.

## Approved fixture forms

| Exact model repository and revision | Current license basis | Repository policy |
| --- | --- | --- |
| `Qwen/Qwen2.5-32B-Instruct` at `afb2829595f63efa3548e9d6b13aa66e61aa0f38` | Apache-2.0 | Template text may be retained after recording the template path, Apache-2.0 notice, and model-card attribution. |
| `mistralai/Mistral-7B-Instruct-v0.3` at `c170c708c41dac9275d15a8fff4eca08d52bab71` | Apache-2.0 | Template text may be retained after recording the template path, Apache-2.0 notice, and model-card attribution. |
| All other Qwen models, including Qwen2.5 3B and 72B variants | Not preapproved | Do not retain template text or rendered output. A hash-only case is permitted; text or output requires a fixture-specific review. |
| All Llama models | Model-version-specific Llama Community License | Do not retain template text or rendered output by default. A hash-only case stores only the reviewed source revision/path and SHA-256 digests of the template and expected output. Text or output may be added only after a separate review confirms the applicable license, attribution, redistribution, naming, and acceptable-use terms. |
| Any repository-and-revision pair not listed above | Not preapproved | Do not retain template text or rendered output. A hash-only case following the Llama form is permitted; text or output requires a fixture-specific review that adds an exact repository-and-revision row here and records its notice requirements. |

## Current fixture set

No model chat-template text or model-derived output is committed. The model-bearing upstream README
and e2e fixture are intentionally not vendored because their template text and outputs are not
covered by this policy. The first Llama case may retain only the hash-only metadata, self-authored
test context, and an error category where applicable. The first retained Qwen or Mistral case must
add its source record and license notice in the same change; copied templates and generated output
are not implicitly covered by the upstream MIT notice.

## Sources reviewed

Reviewed 2026-08-19:

- [Qwen/Qwen2.5-32B-Instruct license at `afb2829`](https://huggingface.co/Qwen/Qwen2.5-32B-Instruct/blob/afb2829595f63efa3548e9d6b13aa66e61aa0f38/LICENSE)
  — Apache-2.0.
- [mistralai/Mistral-7B-Instruct-v0.3 at `c170c70`](https://huggingface.co/mistralai/Mistral-7B-Instruct-v0.3/tree/c170c708c41dac9275d15a8fff4eca08d52bab71)
  — directly confirmed as a Git commit; its `README.md` metadata declares Apache-2.0.
- [Meta Llama 3.1 Community License](https://www.llama.com/llama3_1/license/)
  — used only to establish the review-required default; no Llama material is retained.
