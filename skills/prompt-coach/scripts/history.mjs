#!/usr/bin/env node
// What each review found, kept so that the next one can say what changed since:
//
//   node history.mjs last --target session:<id>|all
//   node history.mjs report-path
//   node history.mjs save --target session:<id>|all --analysis <a.json> --findings <f.json> --report <md>
//
// Everything is under $CCREC_HOME/prompt-coach (~/.ccrec/prompt-coach), readable by its owner only,
// as the recordings are: a run quotes prompts. Nothing of ccrec's own database is touched.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { FINDINGS } from './lib.mjs';

export function home() {
  return path.join(process.env.CCREC_HOME || path.join(os.homedir(), '.ccrec'), 'prompt-coach');
}

function ensure(dir) {
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  return dir;
}

/** What a review looked at: one session, or all of the account's. */
export function parseTarget(target) {
  if (target === 'all') return { kind: 'all' };
  const match = /^session:(.+)$/.exec(target ?? '');
  if (match) return { kind: 'session', sessionId: match[1] };
  throw new Error(`--target needs session:<id> or all, not "${target}"`);
}

const sameTarget = (a, b) => a.kind === b.kind && (a.kind === 'all' || a.sessionId === b.sessionId);

/** The latest run over the same target, or null. */
export function lastRun(target) {
  const dir = path.join(home(), 'runs');
  if (!fs.existsSync(dir)) return null;
  let latest = null;
  for (const name of fs.readdirSync(dir).filter((n) => n.endsWith('.json'))) {
    const file = path.join(dir, name);
    let run;
    try {
      run = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
      continue;
    }
    if (run.target && sameTarget(run.target, target) && (!latest || run.until > latest.run.until)) latest = { file, run };
  }
  return latest;
}

/** Where today's report goes: a second one the same day gets -2, and so on. */
export function reportPath(now = new Date()) {
  const dir = ensure(path.join(ensure(home()), 'reports'));
  const day = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
  for (let n = 1; ; n++) {
    const file = path.join(dir, `prompt-review-${day}${n === 1 ? '' : `-${n}`}.md`);
    if (!fs.existsSync(file)) return file;
  }
}

/**
 * Keeps a run: what it looked at, its numbers, and the findings the report made of them. A finding
 * whose id is not one of FINDINGS cannot be compared next time and is left out, with a word.
 */
export function saveRun({ target, analysis, findings, report }) {
  const kept = [];
  const dropped = [];
  for (const f of findings) {
    (FINDINGS[f.id] ? kept : dropped).push(f);
  }
  const run = {
    version: 1,
    target,
    since: analysis.scope.since,
    until: analysis.scope.until,
    createdAt: new Date().toISOString(),
    sessions: analysis.sessions.map((s) => s.sessionId),
    promptProfile: analysis.scope.promptProfile,
    rates: analysis.scope.rates,
    tokensByModel: analysis.scope.tokensByModel,
    findings: kept.map((f) => ({ id: f.id, title: f.title, severity: f.severity, sessionId: f.sessionId ?? null, at: f.at ?? null })),
    report,
  };
  const dir = ensure(path.join(ensure(home()), 'runs'));
  const stamp = run.createdAt.replace(/[-:]/g, '').replace(/\..*/, '').replace('T', '-');
  // Two runs in the same second each keep a file of their own.
  let file = path.join(dir, `${stamp}.json`);
  for (let n = 2; fs.existsSync(file); n++) file = path.join(dir, `${stamp}-${n}.json`);
  fs.writeFileSync(file, `${JSON.stringify(run, null, 1)}\n`, { mode: 0o600 });
  return { file, dropped: dropped.map((f) => f.id) };
}

function option(args, name) {
  const index = args.indexOf(name);
  if (index < 0 || !args[index + 1]) throw new Error(`${name} is required`);
  return args[index + 1];
}

function main(argv) {
  const [command, ...args] = argv;
  switch (command) {
    case 'last': {
      const found = lastRun(parseTarget(option(args, '--target')));
      const run = found?.run;
      console.log(
        JSON.stringify(
          found
            ? { found: true, file: found.file, since: run.since, until: run.until, report: run.report, findings: run.findings, promptProfile: run.promptProfile }
            : { found: false },
          null,
          1,
        ),
      );
      return;
    }
    case 'report-path':
      console.log(reportPath());
      return;
    case 'save': {
      const read = (name) => JSON.parse(fs.readFileSync(option(args, name), 'utf8'));
      const report = path.resolve(option(args, '--report'));
      if (!fs.existsSync(report)) throw new Error(`no report at ${report}`);
      fs.chmodSync(report, 0o600);
      const { file, dropped } = saveRun({
        target: parseTarget(option(args, '--target')),
        analysis: read('--analysis'),
        findings: read('--findings'),
        report,
      });
      console.log(`run kept in ${file}${dropped.length ? `; findings not kept, ids unknown: ${dropped.join(', ')}` : ''}`);
      return;
    }
    default:
      throw new Error('usage: history.mjs last --target <t> | report-path | save --target <t> --analysis <f> --findings <f> --report <f>');
  }
}

if (process.argv[1] && fs.realpathSync(process.argv[1]) === fs.realpathSync(fileURLToPath(import.meta.url))) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    console.error(`history: ${error.message}`);
    process.exit(1);
  }
}
