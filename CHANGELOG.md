# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Add isolated clean-checkout release verification, publication metadata checks, and release-only
  reproducible archive evidence.

- Add a dependency-free local `tokenizer_config.json` consumer example that parses and renders a
  chat template with the published JPMS module.
- Run every checked-in template-bearing differential-corpus record against hfjinja's public Java
  API as well as the pinned Node oracle.
- Extract all serializable upstream `templates.test.js` rendering and error fixtures into the
  differential corpus, with the four JavaScript-function-context rendering cases explicitly
  reported as schema exclusions.
- Represent parse-time whitespace options in corpus records and extract the upstream interpreter
  whitespace-control vectors through both runtimes.
- Require an explicit coverage or policy decision for every vendored upstream test source.
- Add pinned-oracle vectors for runtime filters and object members, including `reverse`, `bool`,
  `abs`, `keys`, `values`, and `dictsort`.

### Changed

- Change parser diagnostic wording for loop variables and expected tokens; truncated templates
  retain hfjinja's descriptive end-of-input diagnostics rather than upstream's TypeError text.

- Render Java-native callable forms that still have no ported upstream source (namespace, member
  builtins, macros, and call blocks) consistently as `<function>` in coercion and exception paths.

### Fixed

- Preserve the pinned JavaScript source rendering and coercion of converted callables (`range`,
  `raise_exception`, `strftime_now`, and host functions), including bare interpolation, filters,
  concatenation, joining, and explicit exception messages.
- Align `namespace(...)` values with the pinned runtime's object members, filters, containment,
  deferred builtin fallback, and public render-error contract.
- Preserve stable identity for bound string, object, and namespace member builtins.
- Match pinned JavaScript coercion and TypeError behavior for undefined-backed string filters,
  members, and operators.
- Preserve the pinned runtime's deferred undefined behavior for empty `first`/`last`, including
  container reads, boolean and member dereferences, and filter/macro default arguments;
  also align case-sensitive sorting and undefined-backed string tests.
- Classify the pinned runtime's `Unknown FunctionValue filter` diagnostics as `TYPE` in the
  differential oracle.
- Classify the pinned runtime's undefined `value` access diagnostic as `TYPE`, covering the
  `equalto` and `eq` test aliases when invoked without their required comparison value.
- Compare raw upstream macro/call-block `break` and `continue` diagnostics by their documented
  `SYNTAX` category.
- Match upstream `TYPE` failures for empty `first`/`last` and undefined-backed `dictsort` and
  `is lower` inputs.
- Report `SourceLocation`s in terms of the caller's original template string: preprocessing
  removals (`trim_blocks`, `lstrip_blocks`, the trailing newline strip, and `{% generation %}`
  tag stripping) no longer shift the locations of later diagnostics, mapping stays linear for
  templates with many whitespace-control removals, and CRLF-boundary locations remain consistent
  with the scanner
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
