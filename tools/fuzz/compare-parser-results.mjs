#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { ALGORITHM, generate, SMOKE_SEEDS } from './generate-parser-cases.mjs';

const REQUEST_TIMEOUT_MS = 15_000;
const REDUCTION_TIMEOUT_MS = 30_000;
const REDUCTION_TRIALS = 200;
const PROTOCOL = 'hfjinja-parser-fuzz-v1';
function option(name, fallback) { const i = process.argv.indexOf(name); return i < 0 ? fallback : process.argv[i + 1]; }
const java = option('--java'), classpath = option('--java-classpath'), report = option('--report', 'build/reports/fuzz-parser.md');
if (!java || !classpath) throw new Error('Usage: compare-parser-results.mjs --java <java21> --java-classpath <classpath> [--report path]');
const count = Number(option('--count', '100'));
const encode = source => Buffer.from(source, 'utf16le').toString('base64');
const decode = candidate => Buffer.from(candidate.source, 'base64').toString('utf16le');

function runner(command, args) {
  const child = spawn(command, args, { stdio: ['pipe', 'pipe', 'pipe'] });
  let pending, stderr = '', buffer = '';
  child.stderr.on('data', data => { stderr += data; }); child.stdout.setEncoding('utf8');
  child.stdout.on('data', data => { buffer += data; let at; while ((at = buffer.indexOf('\n')) >= 0) { const line = buffer.slice(0, at); buffer = buffer.slice(at + 1); if (pending) { const done = pending; pending = null; done.resolve(line); } } });
  child.on('exit', code => { if (pending) { const done = pending; pending = null; done.reject(new Error(`HARNESS runner exited ${code}: ${stderr}`)); } });
  return { async request(candidate) {
    if (pending) throw new Error('HARNESS concurrent request');
    const line = await new Promise((resolve, reject) => { const timer = setTimeout(() => { pending = null; child.kill('SIGKILL'); reject(new Error(`HARNESS timeout id=${candidate.id}`)); }, REQUEST_TIMEOUT_MS); pending = { resolve: value => { clearTimeout(timer); resolve(value); }, reject: error => { clearTimeout(timer); reject(error); } }; child.stdin.write(`${JSON.stringify(candidate)}\n`); });
    let value; try { value = JSON.parse(line); } catch { throw new Error(`HARNESS malformed output id=${candidate.id}`); }
    if (value.id !== candidate.id || !value.result) throw new Error(`HARNESS invalid output id=${candidate.id}: ${line}`);
    if (value.result === 'HARNESS') throw new Error(`HARNESS id=${candidate.id}: ${value.detail ?? '<no detail>'}`);
    return value;
  }, close() { child.kill(); } };
}

function discrepancy(candidate, node, jvm) {
  if (candidate.family === 'grammar') {
    if (node.result !== 'PARSED') return { kind: 'GENERATOR HARNESS', reason: `Node ${node.result}` };
    if (jvm.result !== 'PARSED') return { kind: 'PARITY', reason: `Java ${jvm.result}` };
    return node.ast === jvm.ast ? null : { kind: 'PARITY', reason: 'AST differs' };
  }
  if (node.result === 'LIMIT' || jvm.result === 'LIMIT') return null;
  return (node.result === 'PARSED') === (jvm.result === 'PARSED') ? null : { kind: 'PARITY', reason: `${node.result} versus ${jvm.result}` };
}

const node = runner('node', ['tools/fuzz/node-parser-runner.mjs']);
const jvm = runner(java, ['-cp', classpath, 'se.alipsa.hfjinja.internal.parser.FuzzParserRunner']);
async function evaluate(candidate) { const [nodeResult, javaResult] = await Promise.all([node.request(candidate), jvm.request(candidate)]); return { nodeResult, javaResult, issue: discrepancy(candidate, nodeResult, javaResult) }; }
async function minimize(candidate) {
  const started = Date.now(); let trials = 0, source = decode(candidate), width = Math.max(1, Math.floor(source.length / 2));
  while (width && trials < REDUCTION_TRIALS && Date.now() - started < REDUCTION_TIMEOUT_MS) {
    let reduced = false;
    for (let index = 0; index < source.length && trials < REDUCTION_TRIALS && Date.now() - started < REDUCTION_TIMEOUT_MS; index += width) {
      const next = source.slice(0, index) + source.slice(index + width); if (!next) continue;
      const trial = { ...candidate, source: encode(next), sourceCodeUnits: next.length }; trials++;
      if ((await evaluate(trial)).issue) { source = next; reduced = true; break; }
    }
    if (!reduced) width = Math.floor(width / 2);
  }
  return { source, trials, status: trials >= REDUCTION_TRIALS || Date.now() - started >= REDUCTION_TIMEOUT_MS ? 'budget-exhausted' : 'complete' };
}

let total = 0, limits = 0; const otherErrors = [];
try {
  for (const seed of SMOKE_SEEDS) for (const candidate of generate({ seed, count }).slice(1)) {
    const result = await evaluate(candidate); total++;
    if (candidate.family === 'hostile') {
      if (result.nodeResult.result === 'LIMIT' || result.javaResult.result === 'LIMIT') limits++;
      for (const [runtime, value] of [['node', result.nodeResult], ['java', result.javaResult]]) if (value.result === 'OTHER_ERROR') otherErrors.push(`${candidate.id} ${runtime}${value.detail ? `: ${value.detail}` : ''}`);
    }
    if (result.issue) {
      let minimized;
      try {
        minimized = await minimize(candidate);
      } catch (error) {
        if (String(error).includes('HARNESS timeout'))
          throw new Error(`${result.issue.kind} ${result.issue.reason} id=${candidate.id} seed=0x${seed.toString(16)} trimBlocks=${candidate.trimBlocks} lstripBlocks=${candidate.lstripBlocks} source=${JSON.stringify(decode(candidate))} minimization=timeout replay=node tools/fuzz/compare-parser-results.mjs --java ${java} --java-classpath <classpath> --count ${count}`);
        throw error;
      }
      throw new Error(`${result.issue.kind} ${result.issue.reason} id=${candidate.id} seed=0x${seed.toString(16)} trimBlocks=${candidate.trimBlocks} lstripBlocks=${candidate.lstripBlocks} source=${JSON.stringify(minimized.source)} minimization=${minimized.status} trials=${minimized.trials} replay=node tools/fuzz/compare-parser-results.mjs --java ${java} --java-classpath <classpath> --count ${count}`);
    }
  }
  await mkdir(dirname(report), { recursive: true });
  await writeFile(report, `# Parser fuzz verification\n\nProtocol: ${PROTOCOL}; PRNG: ${ALGORITHM}. Seeds: ${SMOKE_SEEDS.map(seed => `0x${seed.toString(16).toUpperCase()}`).join(', ')}. Grammar and hostile cases per seed: ${count}. Per-request timeout: ${REQUEST_TIMEOUT_MS / 1000} seconds. Task timeout: 120 seconds. Minimization budget: ${REDUCTION_TIMEOUT_MS / 1000} seconds or ${REDUCTION_TRIALS} trials.\n\nVerified ${total} candidates; documented hostile limit outcomes: ${limits}. OTHER_ERROR outcomes (${otherErrors.length}):${otherErrors.length ? `\n${otherErrors.map(value => `- ${value}`).join('\n')}` : ' none'}. Exclusions: none.\n`);
} finally { node.close(); jvm.close(); }
