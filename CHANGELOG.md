# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- Report `SourceLocation`s in terms of the caller's original template string: preprocessing
  removals (`trim_blocks`, `lstrip_blocks`, the trailing newline strip, and `{% generation %}`
  tag stripping) no longer shift the locations of later diagnostics
  ([#27](https://github.com/Alipsa/hfjinja/issues/27)).
- Propagate macro and call-block `break`/`continue` control to an enclosing loop like the pinned
  upstream runtime.
- Align call-form filter argument evaluation, error categories, and diagnostics with the pinned
  upstream runtime.
- Match upstream keyword-bag handling for `replace`, `get`, and `split`, including the
  positional-after-keyword diagnostic.

## [0.5.0] - TBD

### Added

- Initial release.
