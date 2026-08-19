import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { corpusLine, errorClassifier, readCorpus, sha256Utf8, validateCorpus, validateRecord } from './corpus.mjs';

test('accepts text and hash-only record forms', () => {
  validateRecord({
    id: 'text', source: 'test', template: 'hello', context: {}, expected: {text: 'hello'},
  });
  validateRecord({
    id: 'hash', source: 'test', templateSha256: sha256Utf8('hello'), modelRepo: 'example/model',
    modelRevision: 'a'.repeat(40), templatePath: 'tokenizer_config.json', context: {messages: []},
    expected: {sha256: sha256Utf8('world')},
  });
  validateRecord({
    id: 'error', source: 'test', templateSha256: sha256Utf8('hello'), modelRepo: 'example/model',
    modelRevision: 'a'.repeat(40), templatePath: 'tokenizer_config.json', context: {},
    expected: {errorCategory: 'EXPLICIT_RAISE'},
  });
});

test('rejects duplicate ids and malformed deterministic-time fields', () => {
  const record = {id: 'duplicate', source: 'test', template: 'hello', context: {}, expected: {text: 'hello'}};
  assert.throws(() => validateCorpus([record, record]), /duplicate id/);
  assert.throws(() => validateRecord({...record, instant: '2026-08-19', zone: 'UTC'}), /ISO-8601/);
  assert.throws(() => validateRecord({...record, instant: '2026-08-19T00:00:00Z'}), /requires an explicit zone/);
  assert.throws(() => validateRecord({...record, zone: 'not a zone'}), /IANA/);
  assert.throws(() => validateRecord({...record, globals: {strftime_now: {kind: 'strftime_now'}}}), /not supported/);
});

test('rejects mixed fixture forms and invalid expected results', () => {
  assert.throws(() => validateRecord({
    id: 'mixed', source: 'test', template: 'hello', templateSha256: sha256Utf8('hello'),
    context: {}, expected: {text: 'hello'},
  }), /exactly one/);
  assert.throws(() => validateRecord({
    id: 'wrong-outcome', source: 'test', template: 'hello', context: {},
    expected: {sha256: sha256Utf8('hello')},
  }), /does not match/);
  assert.throws(() => validateRecord({
    id: 'bad-hash', source: 'test', template: 'hello', templateSha256: 'bad', context: {},
    expected: {text: 'hello'},
  }), /exactly one/);
  validateRecord({id: 'empty', source: 'test', template: '', context: {}, expected: {text: ''}});
  assert.throws(() => validateRecord({
    id: 'unknown-key', source: 'test', template: 'hello', context: {}, expected: {text: 'hello'}, typoInstant: 'x',
  }), /unknown fields/);
});

test('fails loudly for an unmatched upstream error', async () => {
  const lock = JSON.parse(await readFile('upstream/upstream-lock.json', 'utf8'));
  const classify = await errorClassifier('tools/corpus/error-patterns-0.5.9.json', `${lock.package}@${lock.version}`);
  assert.equal(classify('Unknown variable: absent'), 'UNDEFINED_OR_ACCESS');
  assert.throws(() => classify('unmapped upstream error'), /Unmatched upstream error/);
  await assert.rejects(
    errorClassifier('tools/corpus/error-patterns-0.5.9.json', '@huggingface/jinja@other'), /does not match/,
  );
});

test('preserves physical JSONL line numbers across blank lines', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'hfjinja-corpus-lines-'));
  const path = join(directory, 'corpus.jsonl');
  try {
    await writeFile(path, '{"id":"first"}\n\n{"id":"third"}\n', 'utf8');
    const records = await readCorpus(path);
    assert.equal(corpusLine(records[0]), 1);
    assert.equal(corpusLine(records[1]), 3);
  } finally {
    await rm(directory, {recursive: true, force: true});
  }
});

test('identifies valid non-object JSON separately from invalid JSON', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'hfjinja-corpus-json-'));
  const path = join(directory, 'corpus.jsonl');
  try {
    await writeFile(path, '42\n', 'utf8');
    await assert.rejects(readCorpus(path), /record must be a JSON object/);
  } finally {
    await rm(directory, {recursive: true, force: true});
  }
});

test('reports harness mismatches and unmatched upstream errors per record', async () => {
  const result = await runOracle([
    {id: 'expected-error', source: 'test', template: 'Hello', context: {}, expected: {errorCategory: 'SYNTAX'}},
    {id: 'unmatched-error', source: 'test', template: '{% for %}', context: {}, expected: {errorCategory: 'SYNTAX'}},
    {id: 'after-error', source: 'test', template: 'still runs', context: {}, expected: {text: 'still runs'}},
  ]);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /expected error=SYNTAX, got output="Hello"/);
  assert.doesNotMatch(result.stderr, /FAIL .*expected-error.*output mismatch/);
  assert.match(result.stderr, /Unmatched upstream error.*Unexpected token/);
  assert.match(result.stdout, /PASS after-error/);
});

test('reports skipped hash-only records and rejects an all-hash-only run', async () => {
  const result = await runOracle([{
    id: 'hash-only', source: 'test', templateSha256: sha256Utf8('template'), modelRepo: 'example/model',
    modelRevision: 'a'.repeat(40), templatePath: 'tokenizer_config.json', context: {},
    expected: {sha256: sha256Utf8('output')},
  }]);
  assert.equal(result.status, 1);
  assert.match(result.stdout, /SKIP hash-only hash-only fixture/);
  assert.match(result.stdout, /SUMMARY executed=0 skipped=1/);
  assert.match(result.stderr, /no text-bearing corpus records were executed/);
});

test('uses a fixed UTC clock when a text record omits time fields', async () => {
  const result = await runOracle([{
    id: 'default-time', source: 'test', template: "{{ strftime_now('%Y-%m-%d %H:%M') }}",
    context: {}, expected: {text: '2000-01-02 03:04'},
  }]);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /PASS default-time/);
});

async function runOracle(records) {
  const directory = await mkdtemp(join(tmpdir(), 'hfjinja-corpus-'));
  const corpus = join(directory, 'corpus.jsonl');
  try {
    await writeFile(corpus, `${records.map((record) => JSON.stringify(record)).join('\n')}\n`, 'utf8');
    return spawnSync(process.execPath, [
      'tools/corpus/run-node-oracle.mjs', '--corpus', corpus,
      '--patterns', 'tools/corpus/error-patterns-0.5.9.json', '--lock', 'upstream/upstream-lock.json',
    ], {encoding: 'utf8'});
  } finally {
    await rm(directory, {recursive: true, force: true});
  }
}
