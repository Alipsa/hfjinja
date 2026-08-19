import assert from 'node:assert/strict';
import test from 'node:test';
import { errorClassifier, sha256Utf8, validateCorpus, validateRecord } from './corpus.mjs';

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
  assert.throws(() => validateRecord({...record, instant: '2026-08-19'}), /ISO-8601/);
  assert.throws(() => validateRecord({...record, zone: 'not a zone'}), /IANA/);
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
});

test('fails loudly for an unmatched upstream error', async () => {
  const classify = await errorClassifier('tools/corpus/error-patterns-0.5.9.json');
  assert.equal(classify('Unknown variable: absent'), 'UNDEFINED_OR_ACCESS');
  assert.throws(() => classify('unmapped upstream error'), /Unmatched upstream error/);
});
