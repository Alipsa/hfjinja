import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';

const categories = new Set([
  'SYNTAX', 'UNDEFINED_OR_ACCESS', 'TYPE', 'ARITY', 'VALUE', 'EXPLICIT_RAISE',
  'HOST_FUNCTION', 'HOST_CONVERSION', 'RESOURCE_LIMIT', 'OUTPUT',
]);
const recordKeys = new Set([
  'id', 'source', 'template', 'templateSha256', 'modelRepo', 'modelRevision', 'templatePath',
  'context', 'instant', 'zone', 'globals', 'expected',
]);

export async function readCorpus(path) {
  const content = await readFile(path, 'utf8');
  return content.split(/\r?\n/).filter(Boolean).map((line, index) => {
    try {
      return JSON.parse(line);
    } catch (error) {
      throw new Error(`${path}:${index + 1}: invalid JSON: ${error.message}`);
    }
  });
}

export function validateCorpus(records, label = 'corpus') {
  const ids = new Set();
  for (const [index, record] of records.entries()) {
    validateRecord(record, `${label}:${index + 1}`);
    if (ids.has(record.id)) throw new Error(`${label}:${index + 1}: duplicate id ${record.id}`);
    ids.add(record.id);
  }
}

export function validateRecord(record, label = 'record') {
  if (!isObject(record)) throw new Error(`${label}: must be an object`);
  const unknownKeys = Object.keys(record).filter((key) => !recordKeys.has(key));
  if (unknownKeys.length) throw new Error(`${label}: unknown fields: ${unknownKeys.join(', ')}`);
  if (!nonBlank(record.id) || !nonBlank(record.source) || !isObject(record.context)) {
    throw new Error(`${label}: id, source, and object context are required`);
  }
  const text = typeof record.template === 'string';
  const hashOnly = record.templateSha256 !== undefined;
  if (text === hashOnly) throw new Error(`${label}: provide exactly one of template or templateSha256`);
  if (hashOnly && !sha256(record.templateSha256)) throw new Error(`${label}: templateSha256 must be 64 lowercase hex characters`);
  if (hashOnly && (!nonBlank(record.modelRepo)
      || !(typeof record.modelRevision === 'string' && /^[0-9a-f]{40}$/.test(record.modelRevision))
      || !nonBlank(record.templatePath))) {
    throw new Error(`${label}: hash-only records require modelRepo, 40-hex modelRevision, and templatePath`);
  }
  if (record.instant !== undefined && (!(typeof record.instant === 'string' && /^\d{4}-\d{2}-\d{2}T.*Z$/.test(record.instant))
      || Number.isNaN(Date.parse(record.instant)))) {
    throw new Error(`${label}: instant must be an ISO-8601 instant`);
  }
  if (record.zone !== undefined) validateZone(record.zone, label);
  validateGlobals(record.globals, label);
  validateExpected(record.expected, hashOnly, label);
}

export function sha256Utf8(value) {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}

export async function errorClassifier(path) {
  const definition = JSON.parse(await readFile(path, 'utf8'));
  if (!isObject(definition) || !Array.isArray(definition.patterns)) {
    throw new Error(`Invalid error pattern table: ${path}`);
  }
  const patterns = definition.patterns.map((pattern, index) => {
    if (!isObject(pattern) || !nonBlank(pattern.regex) || !categories.has(pattern.category)) {
      throw new Error(`Invalid error pattern ${index + 1} in ${path}`);
    }
    try {
      return {category: pattern.category, regex: new RegExp(pattern.regex)};
    } catch (error) {
      throw new Error(`Invalid error pattern ${index + 1} in ${path}: ${error.message}`);
    }
  });
  return (message) => {
    for (const pattern of patterns) {
      if (pattern.regex.test(message)) return pattern.category;
    }
    throw new Error(`Unmatched upstream error for ${definition.version}: ${message}`);
  };
}

function validateGlobals(globals, label) {
  if (globals === undefined) return;
  if (!isObject(globals)) throw new Error(`${label}: globals must be an object`);
  for (const [name, value] of Object.entries(globals)) {
    if (name !== 'strftime_now' || !isObject(value) || value.kind !== 'strftime_now') {
      throw new Error(`${label}: unsupported record global ${name}`);
    }
  }
}

function validateZone(zone, label) {
  if (!nonBlank(zone)) throw new Error(`${label}: zone must be non-blank`);
  try {
    new Intl.DateTimeFormat('en-US', {timeZone: zone});
  } catch {
    throw new Error(`${label}: zone must be an IANA time-zone identifier`);
  }
}

function validateExpected(expected, hashOnly, label) {
  if (!isObject(expected)) throw new Error(`${label}: expected is required`);
  const keys = Object.keys(expected);
  if (keys.length !== 1) throw new Error(`${label}: expected must have exactly one outcome`);
  const [outcome] = keys;
  if (outcome === 'errorCategory') {
    if (!categories.has(expected.errorCategory)) throw new Error(`${label}: invalid error category`);
    return;
  }
  if (hashOnly && outcome === 'sha256' && sha256(expected.sha256)) return;
  if (!hashOnly && outcome === 'text' && typeof expected.text === 'string') return;
  throw new Error(`${label}: expected outcome does not match fixture form`);
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function nonBlank(value) {
  return typeof value === 'string' && value.length > 0;
}

function sha256(value) {
  return typeof value === 'string' && /^[0-9a-f]{64}$/.test(value);
}
