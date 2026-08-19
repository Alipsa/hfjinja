#!/usr/bin/env node
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import vm from 'node:vm';
import { readCorpus, validateRecord } from './corpus.mjs';

const sourcePath = 'upstream/vendor/test/templates.test.js';
const corpusPath = 'src/test/resources/corpus/v1.jsonl';
const selected = new Map([
  ['NO_TEMPLATE', 'templates.no-template'],
  ['FOR_LOOP', 'templates.for-loop'],
  ['FILTER_OPERATOR_2', 'templates.filter-operator-2'],
]);
const options = new Set(process.argv.slice(2));
if (![...options].every((option) => option === '--check' || option.startsWith('--report='))) {
  throw new Error('Usage: convert-upstream-tests.mjs --check [--report=<path>]');
}

const source = await readFile(sourcePath, 'utf8');
const capture = extractConstants(source);
const generated = [...selected].map(([upstreamName, id]) => ({
  id,
  source: `${sourcePath}:${propertyLine(source, upstreamName)}`,
  template: capture.templates[upstreamName],
  context: capture.contexts[upstreamName],
  expected: {text: capture.outputs[upstreamName]},
}));
for (const record of generated) validateRecord(record, record.id);

if (options.has('--check')) {
  const actual = new Map((await readCorpus(corpusPath)).map((record) => [record.id, record]));
  for (const record of generated) {
    const committed = actual.get(record.id);
    if (!committed || !sameFixture(committed, record)) {
      throw new Error(`${corpusPath}: ${record.id} differs from ${record.source}; rerun the reviewed converter`);
    }
  }
}

const reportOption = [...options].find((option) => option.startsWith('--report='));
if (reportOption) await writeCoverage(reportOption.slice('--report='.length), generated);

function extractConstants(source) {
  const executable = source.replace(/^import .*;\n/gm, '');
  const context = {describe: () => {}};
  vm.runInNewContext(`${executable}\nglobalThis.capture = {TEST_STRINGS, TEST_CONTEXT, EXPECTED_OUTPUTS};`, context, {
    filename: sourcePath,
  });
  const capture = context.capture;
  if (!capture || !capture.TEST_STRINGS || !capture.TEST_CONTEXT || !capture.EXPECTED_OUTPUTS) {
    throw new Error(`Could not extract corpus constants from ${sourcePath}`);
  }
  return {templates: capture.TEST_STRINGS, contexts: capture.TEST_CONTEXT, outputs: capture.EXPECTED_OUTPUTS};
}

function propertyLine(source, name) {
  const match = new RegExp(`^\\s*${name}:`, 'm').exec(source);
  if (!match) throw new Error(`Could not locate ${name} in ${sourcePath}`);
  return source.slice(0, match.index).split('\n').length;
}

function sameFixture(actual, generated) {
  return actual.source === generated.source
    && actual.template === generated.template
    && JSON.stringify(actual.context) === JSON.stringify(generated.context)
    && actual.expected?.text === generated.expected.text;
}

async function writeCoverage(path, generated) {
  const testFiles = ['format.test.js', 'interpreter.test.js', 'memory.test.js', 'templates.test.js', 'utils.test.js'];
  const lines = [
    '# Differential corpus coverage', '',
    `Vendored non-model unit sources: ${testFiles.length}`,
    `Automatically extracted fixture definitions: ${generated.length}`,
    'Excluded e2e sources: 1 (removed by the model-fixture licensing policy)', '',
    '## Extracted fixtures', '', '| Corpus id | Upstream source |', '| --- | --- |',
    ...generated.map((record) => `| \`${record.id}\` | \`${record.source}\` |`), '',
    'The remaining vendored unit cases require supported structural extraction or a reviewed manual transcription.', '',
  ];
  await writeFile(resolve(path), lines.join('\n'), 'utf8');
}
