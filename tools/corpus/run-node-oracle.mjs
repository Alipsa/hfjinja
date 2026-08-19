#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { corpusLine, errorClassifier, readCorpus, sha256Utf8, validateCorpus } from './corpus.mjs';

const argumentsByName = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  const option = process.argv[index];
  if (!option.startsWith('--') || process.argv[index + 1] === undefined) {
    throw new Error('Usage: run-node-oracle.mjs --corpus <jsonl> --patterns <json> --lock <json>');
  }
  argumentsByName.set(option.slice(2), process.argv[index + 1]);
}
const corpusPath = argumentsByName.get('corpus');
const patternsPath = argumentsByName.get('patterns');
const lockPath = argumentsByName.get('lock');
if (!corpusPath || !patternsPath || !lockPath) {
  throw new Error('Usage: run-node-oracle.mjs --corpus <jsonl> --patterns <json> --lock <json>');
}

const lock = JSON.parse(await readFile(lockPath, 'utf8'));
if (process.version !== lock.nodeVersion) {
  throw new Error(`Node oracle version ${process.version} does not match lock ${lock.nodeVersion}`);
}
const { Template } = await import(pathToFileURL(resolve('upstream/vendor/dist/index.js')).href);
const classifyError = await errorClassifier(patternsPath, `${lock.package}@${lock.version}`);
const records = await readCorpus(corpusPath);
validateCorpus(records, corpusPath);
let failures = 0;
let executed = 0;
let skipped = 0;
const defaultInstant = '2000-01-02T03:04:05Z';
const defaultZone = 'UTC';
const defaultLocale = 'en-US';
for (const [index, record] of records.entries()) {
  const label = `${corpusPath}:${corpusLine(record, index + 1)} (${record?.id ?? 'unknown'})`;
  if (record.templateSha256) {
    skipped++;
    console.log(`SKIP ${record.id} hash-only fixture`);
    continue;
  }
  executed++;
  try {
    const output = render(record, Template);
    if (Object.hasOwn(record.expected, 'errorCategory')) {
      fail(label, `expected error=${record.expected.errorCategory}, got output=${JSON.stringify(output)}`);
    } else if (output !== record.expected.text) {
      fail(label, `output mismatch; expected=${JSON.stringify(record.expected.text)}, actual=${JSON.stringify(output)}`);
    } else {
      console.log(`PASS ${record.id} sha256=${sha256Utf8(output)}`);
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (!record.expected?.errorCategory) {
      fail(label, `unexpected upstream error: ${message}`);
    } else {
      try {
        const category = classifyError(message);
        if (category === record.expected.errorCategory) {
          console.log(`PASS ${record.id} error=${category}`);
        } else {
          fail(label, `error category mismatch; expected=${record.expected.errorCategory}, actual=${category}; message=${message}`);
        }
      } catch (classificationError) {
        fail(label, classificationError instanceof Error ? classificationError.message : String(classificationError));
      }
    }
  }
}
console.log(`SUMMARY executed=${executed} skipped=${skipped}`);
if (!executed) fail(corpusPath, 'no text-bearing corpus records were executed');
if (failures) process.exitCode = 1;

function fail(label, message) {
  failures++;
  console.error(`FAIL ${label}: ${message}`);
}

function render(record, TemplateClass) {
  const nativeDate = globalThis.Date;
  const nativeDateTimeFormat = Intl.DateTimeFormat;
  const nativeLocaleCompare = String.prototype.localeCompare;
  const nativeZone = process.env.TZ;
  try {
    const instant = new nativeDate(record.instant ?? defaultInstant).valueOf();
    globalThis.Date = class extends nativeDate {
      constructor(...arguments_) { super(...(arguments_.length ? arguments_ : [instant])); }
      static now() { return instant; }
    };
    Intl.DateTimeFormat = class extends nativeDateTimeFormat {
      constructor(locales, options) { super(locales ?? defaultLocale, options); }
    };
    String.prototype.localeCompare = function localeCompare(other, locales, options) {
      return nativeLocaleCompare.call(this, other, locales ?? defaultLocale, options);
    };
    process.env.TZ = record.zone ?? defaultZone;
    return new TemplateClass(record.template).render(record.context);
  } finally {
    globalThis.Date = nativeDate;
    Intl.DateTimeFormat = nativeDateTimeFormat;
    String.prototype.localeCompare = nativeLocaleCompare;
    if (nativeZone === undefined) delete process.env.TZ;
    else process.env.TZ = nativeZone;
  }
}
