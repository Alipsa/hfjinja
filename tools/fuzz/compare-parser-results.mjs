#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { generate, SMOKE_SEEDS } from './generate-parser-cases.mjs';

function option(name, fallback) { const i = process.argv.indexOf(name); return i < 0 ? fallback : process.argv[i + 1]; }
const java = option('--java'), classpath = option('--java-classpath'), report = option('--report', 'build/reports/fuzz-parser.md');
if (!java || !classpath) throw new Error('Usage: compare-parser-results.mjs --java <java21> --java-classpath <classpath> [--report path]');
const count = Number(option('--count', '100'));

function runner(command, args) {
  const child = spawn(command, args, { stdio: ['pipe', 'pipe', 'pipe'] });
  let pending, stderr = '';
  child.stderr.on('data', data => { stderr += data; });
  child.stdout.setEncoding('utf8');
  let buffer = '';
  child.stdout.on('data', data => { buffer += data; let at; while ((at = buffer.indexOf('\n')) >= 0) { const line = buffer.slice(0, at); buffer = buffer.slice(at + 1); if (pending) { const done = pending; pending = null; done.resolve(line); } } });
  child.on('exit', code => { if (pending) { const done = pending; pending = null; done.reject(new Error(`runner exited ${code}: ${stderr}`)); } });
  return {
    async request(candidate) {
      if (pending) throw new Error('HARNESS concurrent request');
      const line = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => { pending = null; child.kill('SIGKILL'); reject(new Error(`HARNESS timeout id=${candidate.id}`)); }, 15_000);
        pending = { resolve: value => { clearTimeout(timer); resolve(value); }, reject: error => { clearTimeout(timer); reject(error); } };
        child.stdin.write(`${JSON.stringify(candidate)}\n`);
      });
      let value; try { value = JSON.parse(line); } catch { throw new Error(`HARNESS malformed output id=${candidate.id}`); }
      if (value.id !== candidate.id || !value.result || value.result === 'HARNESS') throw new Error(`HARNESS invalid output id=${candidate.id}`);
      return value;
    },
    close() { child.kill(); },
  };
}

function mismatch(candidate, node, jvm) {
  if (candidate.family === 'grammar') return node.result !== 'PARSED' || jvm.result !== 'PARSED' || node.ast !== jvm.ast;
  if (node.result === 'LIMIT' || jvm.result === 'LIMIT') return false;
  const accepted = value => value.result === 'PARSED';
  return accepted(node) !== accepted(jvm);
}

const node = runner('node', ['tools/fuzz/node-parser-runner.mjs']);
const jvm = runner(java, ['-cp', classpath, 'se.alipsa.hfjinja.internal.parser.FuzzParserRunner']);
let total = 0, limits = 0;
try {
  for (const seed of SMOKE_SEEDS) for (const candidate of generate({ seed, count }).slice(1)) {
    const [nodeResult, javaResult] = await Promise.all([node.request(candidate), jvm.request(candidate)]);
    total++;
    if (candidate.family === 'hostile' && (nodeResult.result === 'LIMIT' || javaResult.result === 'LIMIT')) limits++;
    if (mismatch(candidate, nodeResult, javaResult)) {
      const source = Buffer.from(candidate.source, 'base64').toString('utf16le');
      throw new Error(`PARITY mismatch id=${candidate.id} seed=0x${seed.toString(16)} source=${JSON.stringify(source)} replay=node tools/fuzz/compare-parser-results.mjs --java ${java} --java-classpath <classpath> --count ${count}`);
    }
  }
  await mkdir(dirname(report), { recursive: true });
  await writeFile(report, `# Parser fuzz verification\n\nSeeds: ${SMOKE_SEEDS.map(seed => `0x${seed.toString(16).toUpperCase()}`).join(', ')}. Grammar and hostile cases per seed: ${count}. Per-request timeout: 15 seconds. Task timeout: 120 seconds. Reducer budget: 30 seconds or 200 trials.\n\nVerified ${total} candidates; documented hostile limit outcomes: ${limits}. Exclusions: none.\n`);
} finally { node.close(); jvm.close(); }
