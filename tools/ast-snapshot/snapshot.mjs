#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { tokenize, parse } from '../../upstream/vendor/dist/index.js';

const args = process.argv.slice(2);
if (args[0] !== '--fixtures' || !args[1]) throw new Error('Usage: snapshot.mjs --fixtures <path> [--check <path>]');
const path = args[1];
const fixtures = JSON.parse(await readFile(path, 'utf8'));

function emit(node, indent = '') {
  if (node == null) return `${indent}-\n`;
  if (Array.isArray(node)) return node.map(value => emit(value, indent)).join('');
  if (typeof node !== 'object') return `${indent}${JSON.stringify(node)}\n`;
  let out = `${indent}${node.type}\n`;
  for (const [key, value] of Object.entries(node)) {
    if (key === 'type') continue;
    out += `${indent}  ${key}\n`;
    out += emit(value, `${indent}    `);
  }
  return out;
}
let output = '';
for (const fixture of fixtures) output += `=== ${fixture.name} ${JSON.stringify(fixture.source)}\n` + emit(parse(tokenize(fixture.source, { lstrip_blocks: true, trim_blocks: true })));
if (args[2] === '--check') {
  if (output !== await readFile(args[3], 'utf8')) throw new Error(`Stale AST snapshot: ${args[3]}`);
} else process.stdout.write(output);
