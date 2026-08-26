#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { Template, tokenize, parse } from '../../upstream/vendor/dist/index.js';

const args = process.argv.slice(2);
if (args[0] !== '--vectors' || !args[1]) throw new Error('Usage: format-golden.mjs --vectors <path> [--check <path>]');
const vectors = JSON.parse(await readFile(args[1], 'utf8'));
const coverage = JSON.parse(await readFile('src/test/resources/format/coverage.json', 'utf8'));
const lock = JSON.parse(await readFile('upstream/upstream-lock.json', 'utf8'));
if (process.version !== lock.nodeVersion) throw new Error(`Node oracle version ${process.version} does not match lock ${lock.nodeVersion}`);
const types = new Set();
function walk(value) {
  if (!value || typeof value !== 'object') return;
  if (value.constructor?.name && value.constructor.name !== 'Object' && value.constructor.name !== 'Array') types.add(value.constructor.name);
  if (Array.isArray(value)) value.forEach(walk); else Object.values(value).forEach(walk);
}
const output = vectors.map(vector => {
  const template = new Template(vector.source);
  const indent = vector.indent.default ? undefined : vector.indent.number ?? vector.indent.string;
  const formatted = indent === undefined ? template.format() : template.format({ indent });
  walk(parse(tokenize(vector.source, { lstrip_blocks: true, trim_blocks: true })));
  return { ...vector, formatted };
});
for (const source of coverage) walk(parse(tokenize(source, { lstrip_blocks: true, trim_blocks: true })));
const text = JSON.stringify(output) + '\n';
if (args[2] === '--check') {
  if (text !== await readFile(args[3], 'utf8')) throw new Error(`Stale format golden: ${args[3]}`);
  const allowed = new Set(Object.keys(JSON.parse(await readFile('upstream/ast-allowlist.json', 'utf8'))));
  for (const abstract of ['Statement', 'Expression', 'Literal']) allowed.delete(abstract);
  const missing = [...allowed].filter(type => !types.has(type));
  if (missing.length) throw new Error(`Format vectors miss AST node types: ${missing.join(', ')}`);
} else process.stdout.write(text);
