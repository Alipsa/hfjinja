# Model fixture licensing policy

This policy satisfies WP1a before the differential corpus imports any model chat-template text.
It is a repository intake policy, not legal advice. Every future fixture change must identify its
exact model repository, immutable revision, template path, and applicable license in `NOTICE`.

## Approved fixture forms

| Family | Current license basis | Repository policy |
| --- | --- | --- |
| Qwen 2.5 | Apache-2.0 | Template text may be retained after recording the model revision, source URL, Apache-2.0 notice, and any model-card attribution. |
| Mistral 7B Instruct | Apache-2.0 | Template text may be retained after recording the model revision, source URL, Apache-2.0 notice, and any model-card attribution. |
| Llama | Model-version-specific Llama Community License | Do not retain template text by default. Store only a reviewed SHA-256, source revision/path, test context, and expected output. Text may be added only after a separate review confirms the applicable license, attribution, redistribution, naming, and acceptable-use terms. |

## Current fixture set

No model chat-template text or model-derived output is committed. The first Llama case must use the
hash-only form above. The first retained Qwen or Mistral case must add its source record and license
notice in the same change; copied templates and generated output are not implicitly covered by the
upstream MIT notice.

## Sources reviewed

- [Qwen/Qwen2.5-32B-Instruct license](https://huggingface.co/Qwen/Qwen2.5-32B-Instruct/blob/main/LICENSE)
  — Apache-2.0.
- [mistralai/Mistral-7B-Instruct-v0.3 model page](https://huggingface.co/mistralai/Mistral-7B-Instruct-v0.3)
  — Apache-2.0.
- [Meta Llama 3.1 Community License](https://huggingface.co/meta-llama/Llama-3.1-405B/blob/main/LICENSE)
  — redistribution and attribution conditions apply to Llama Materials.
