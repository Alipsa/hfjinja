#!/usr/bin/env node
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { errorClassifier, readCorpus, sha256Utf8, validateCorpus } from './corpus.mjs';

const argumentsByName = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  const option = process.argv[index];
  if (!option.startsWith('--') || process.argv[index + 1] === undefined) {
    throw new Error('Usage: run-node-oracle.mjs --corpus <jsonl> --patterns <json>');
  }
  argumentsByName.set(option.slice(2), process.argv[index + 1]);
}
const corpusPath = argumentsByName.get('corpus');
const patternsPath = argumentsByName.get('patterns');
if (!corpusPath || !patternsPath) {
  throw new Error('Usage: run-node-oracle.mjs --corpus <jsonl> --patterns <json>');
}

const { Template } = await import(pathToFileURL(resolve('upstream/vendor/dist/index.js')).href);
const classifyError = await errorClassifier(patternsPath);
const records = await readCorpus(corpusPath);
validateCorpus(records, corpusPath);
let failures = 0;
for (const [index, record] of records.entries()) {
  const label = `${corpusPath}:${index + 1} (${record?.id ?? 'unknown'})`;
  if (record.templateSha256) continue;
  try {
    const output = render(record, Template);
    if (!Object.hasOwn(record.expected, 'text') || output !== record.expected.text) {
      throw new Error(`output mismatch; expected=${JSON.stringify(record.expected.text)}, actual=${JSON.stringify(output)}`);
    }
    console.log(`PASS ${record.id} sha256=${sha256Utf8(output)}`);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (record.expected?.errorCategory && classifyError(message) === record.expected.errorCategory) {
      console.log(`PASS ${record.id} error=${record.expected.errorCategory}`);
    } else {
      failures++;
      console.error(`FAIL ${label}: ${message}`);
    }
  }
}
if (failures) process.exitCode = 1;

function render(record, TemplateClass) {
  const nativeDate = globalThis.Date;
  const nativeZone = process.env.TZ;
  try {
    if (record.instant !== undefined) {
      const instant = new nativeDate(record.instant).valueOf();
      globalThis.Date = class extends nativeDate {
        constructor(...arguments_) { super(arguments_.length ? arguments_[0] : instant); }
        static now() { return instant; }
      };
    }
    if (record.zone !== undefined) process.env.TZ = record.zone;
    return new TemplateClass(record.template).render(record.context);
  } finally {
    globalThis.Date = nativeDate;
    if (nativeZone === undefined) delete process.env.TZ;
    else process.env.TZ = nativeZone;
  }
}
