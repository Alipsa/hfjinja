# Repository guidance

## Generated documentation

- When generating Javadoc manually, write it only under `build/` or a temporary directory; never
  use the repository root as the output directory.

## Pull requests

- Create pull requests ready for review by default; use drafts only when explicitly requested.
- Prefer work-package-sized pull requests over one pull request per numbered plan step. Keep
  commits focused and verify incrementally, but combine cohesive, low-risk work so review latency
  does not dominate delivery time. Split only when a change is independently reviewable, risky, or
  needs an earlier decision.

## Code review

- Review diffs and pull requests inline, reading the changed code directly. Use the `/code-review`
  skill only as a parallel cross-check, never as the primary review or as a substitute for reading
  the diff yourself.
- Treat a review agent's findings as claims to verify, not results to relay. Confirm a claimed
  missing test by mutating the code and running the suite; confirm a claimed parity gap against the
  pinned Node oracle before reporting it.
