#!/usr/bin/env node
import { mkdir, readdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import vm from 'node:vm';
import { readCorpus, validateCorpus, validateRecord } from './corpus.mjs';

const sourcePath = 'upstream/vendor/test/templates.test.js';
const corpusPath = 'src/test/resources/corpus/v1.jsonl';
const lockPath = 'upstream/upstream-lock.json';
const selected = new Map([
  ['NO_TEMPLATE', 'templates.no-template'],
  ['FOR_LOOP', 'templates.for-loop'],
  ['FILTER_OPERATOR_2', 'templates.filter-operator-2'],
]);
const options = new Set(process.argv.slice(2));
if (![...options].every((option) => option === '--check' || option.startsWith('--report='))) {
  throw new Error('Usage: convert-upstream-tests.mjs --check [--report=<path>]');
}
if (!options.has('--check')) throw new Error('Usage: convert-upstream-tests.mjs --check [--report=<path>]');

const source = await readFile(sourcePath, 'utf8');
const capture = extractConstants(source);
const upstreamFixtureCount = Object.keys(capture.templates).length;
const generated = [...selected].map(([upstreamName, id]) => ({
  id,
  source: `${sourcePath}:${propertyLine(source, upstreamName)}`,
  template: capture.templates[upstreamName],
  context: capture.contexts[upstreamName],
  expected: {text: capture.outputs[upstreamName]},
}));
for (const record of generated) validateRecord(record, record.id);

if (options.has('--check')) {
  const records = await readCorpus(corpusPath);
  validateCorpus(records, corpusPath);
  const actual = new Map(records.map((record) => [record.id, record]));
  for (const record of generated) {
    const committed = actual.get(record.id);
    if (!committed || !sameFixture(committed, record)) {
      throw new Error(`${corpusPath}: ${record.id} differs from ${record.source}; rerun the reviewed converter`);
    }
  }
  const generatedIds = new Set(generated.map((record) => record.id));
  const staleIds = [...actual.keys()].filter((id) => id.startsWith('templates.') && !generatedIds.has(id));
  if (staleIds.length) throw new Error(`${corpusPath}: stale extracted fixtures: ${staleIds.join(', ')}`);
}

const reportOption = [...options].find((option) => option.startsWith('--report='));
if (reportOption) await writeCoverage(reportOption.slice('--report='.length), generated, upstreamFixtureCount);

function extractConstants(source) {
  const executable = source.replace(/^(?:import[\s\S]*?;\s*)+/, '');
  const context = {describe: () => {}};
  try {
    vm.runInNewContext(`${executable}\nglobalThis.capture = {TEST_STRINGS, TEST_CONTEXT, EXPECTED_OUTPUTS};`, context, {
      filename: sourcePath,
    });
  } catch (error) {
    throw new Error(`Could not extract corpus constants from ${sourcePath}: ${error.message}`);
  }
  const capture = context.capture;
  if (!capture || !capture.TEST_STRINGS || !capture.TEST_CONTEXT || !capture.EXPECTED_OUTPUTS) {
    throw new Error(`Could not extract corpus constants from ${sourcePath}`);
  }
  return {templates: capture.TEST_STRINGS, contexts: capture.TEST_CONTEXT, outputs: capture.EXPECTED_OUTPUTS};
}

function propertyLine(source, name) {
  const match = new RegExp(`^[^\\S\\r\\n]*${name}:`, 'm').exec(source);
  if (!match) throw new Error(`Could not locate ${name} in ${sourcePath}`);
  return source.slice(0, match.index).split('\n').length;
}

function sameFixture(actual, generated) {
  return Object.keys(actual).length === Object.keys(generated).length
    && actual.source === generated.source
    && actual.template === generated.template
    && JSON.stringify(actual.context) === JSON.stringify(generated.context)
    && actual.expected?.text === generated.expected.text;
}

async function writeCoverage(path, generated, upstreamFixtureCount) {
  const lock = JSON.parse(await readFile(lockPath, 'utf8'));
  const testFiles = await testSources('upstream/vendor/test');
  const excludedTestFiles = Object.keys(lock.excludedFiles ?? {})
    .filter((file) => file.startsWith('test/') && file.endsWith('.test.js'));
  const lines = [
    '# Differential corpus coverage', '',
    `Vendored non-model unit sources: ${testFiles.length}`,
    `Vendored template fixture definitions: ${upstreamFixtureCount}`,
    `Automatically extracted fixture definitions: ${generated.length}`,
    `Policy-excluded test sources: ${excludedTestFiles.length}${excludedTestFiles.length ? ` (${excludedTestFiles.join(', ')})` : ''}`, '',
    '## Extracted fixtures', '', '| Corpus id | Upstream source |', '| --- | --- |',
    ...generated.map((record) => `| \`${record.id}\` | \`${record.source}\` |`), '',
    'The remaining vendored unit cases require supported structural extraction or a reviewed manual transcription.', '',
  ];
  const reportPath = resolve(path);
  await mkdir(dirname(reportPath), {recursive: true});
  await writeFile(reportPath, lines.join('\n'), 'utf8');
}

async function testSources(directory, relative = '') {
  const entries = await readdir(directory, {withFileTypes: true});
  const files = await Promise.all(entries.map(async (entry) => {
    const entryRelative = relative ? `${relative}/${entry.name}` : entry.name;
    if (entry.isDirectory()) return testSources(`${directory}/${entry.name}`, entryRelative);
    return entry.isFile() && entry.name.endsWith('.test.js') ? [entryRelative] : [];
  }));
  return files.flat().sort();
}
