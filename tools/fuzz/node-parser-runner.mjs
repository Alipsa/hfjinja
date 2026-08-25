#!/usr/bin/env node
import readline from 'node:readline';
import { readFile } from 'node:fs/promises';
import { tokenize, parse } from '../../upstream/vendor/dist/index.js';
import { emit } from '../ast-snapshot/ast-serialize.mjs';

const lock = JSON.parse(await readFile('upstream/upstream-lock.json', 'utf8'));
if (process.version !== lock.nodeVersion) throw new Error(`Node oracle version ${process.version} does not match lock ${lock.nodeVersion}`);
const reply = result => process.stdout.write(`${JSON.stringify(result)}\n`);
for await (const line of readline.createInterface({ input: process.stdin, crlfDelay: Infinity })) {
  try {
    const candidate = JSON.parse(line);
    const source = Buffer.from(candidate.source, 'base64').toString('utf16le');
    if (source.length !== candidate.sourceCodeUnits) throw new Error('HARNESS source length differs');
    try {
      const ast = parse(tokenize(source, { trim_blocks: candidate.trimBlocks, lstrip_blocks: candidate.lstripBlocks }));
      reply({ id: candidate.id, result: 'PARSED', ast: candidate.family === 'grammar' ? Buffer.from(emit(ast)).toString('base64') : undefined });
    } catch (error) {
      const result = error instanceof RangeError ? 'LIMIT' : error instanceof SyntaxError ? 'SYNTAX' : 'OTHER_ERROR';
      reply({ id: candidate.id, result, detail: result === 'OTHER_ERROR' ? String(error) : undefined });
    }
  } catch (error) {
    reply({ id: null, result: 'HARNESS', detail: String(error) });
  }
}
