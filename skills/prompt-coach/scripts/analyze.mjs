#!/usr/bin/env node
// Reads recorded sessions through ccrec, read-only, and prints what lib.mjs counts in them as JSON:
//
//   node analyze.mjs [--days 7] [--session <id>]... [--account <id>] [--limit 200] [--out <file>]
//
// Only the read commands are run (sessions, summary, show), so whatever database ccrec records into
// is read the same way, and nothing in it changes.
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { analyzeSession, patterns, promptProfile, selectSessions, totals } from './lib.mjs';

const KINDS = 'user_prompt,assistant_text,thinking,tool_use,tool_result,system';

function parse(argv) {
  const options = { days: 7, sessions: [], account: null, limit: 200, out: null };
  for (let i = 0; i < argv.length; i++) {
    const value = argv[i + 1];
    switch (argv[i]) {
      case '--days':
        options.days = Number(value);
        i++;
        break;
      case '--session':
        options.sessions.push(value);
        i++;
        break;
      case '--account':
        options.account = value;
        i++;
        break;
      case '--limit':
        options.limit = Number(value);
        i++;
        break;
      case '--out':
        options.out = value;
        i++;
        break;
      default:
        throw new Error(`unknown option ${argv[i]}`);
    }
  }
  if (!Number.isFinite(options.days) || options.days <= 0) throw new Error('--days needs a positive number');
  return options;
}

/** The major version of a java executable, or null when it does not run. */
function javaMajor(java) {
  const result = spawnSync(java, ['-version'], { encoding: 'utf8' });
  if (result.error || result.status !== 0) return null;
  const match = /version "(?:1\.)?(\d+)/.exec(`${result.stderr}${result.stdout}`);
  return match ? Number(match[1]) : null;
}

/** Java 17 or later, looked for where ccrec's own scripts look. */
function findJava() {
  const candidates = [];
  if (process.env.CCREC_JAVA) candidates.push(process.env.CCREC_JAVA);
  if (process.env.JAVA_HOME) candidates.push(path.join(process.env.JAVA_HOME, 'bin', 'java'));
  candidates.push('java');
  candidates.push(path.join(os.homedir(), '.sdkman', 'candidates', 'java', 'current', 'bin', 'java'));
  if (fs.existsSync('/usr/libexec/java_home')) {
    const home = spawnSync('/usr/libexec/java_home', ['-v', '17+'], { encoding: 'utf8' });
    if (home.status === 0 && home.stdout.trim()) candidates.push(path.join(home.stdout.trim(), 'bin', 'java'));
  }
  candidates.push('/opt/homebrew/opt/openjdk/bin/java', '/usr/local/opt/openjdk/bin/java');
  return candidates.find((java) => (javaMajor(java) ?? 0) >= 17) ?? null;
}

/** ccrec from the package this skill came with, or else the one on PATH. */
function ccrecCommand() {
  const here = path.dirname(fs.realpathSync(fileURLToPath(import.meta.url)));
  const bundled = path.resolve(here, '..', '..', '..', 'bin', 'ccrec.js');
  return fs.existsSync(bundled) ? [process.execPath, [bundled]] : ['ccrec', []];
}

function ccrec(java, ...args) {
  const [command, prefix] = ccrecCommand();
  const output = execFileSync(command, [...prefix, ...args], {
    encoding: 'utf8',
    maxBuffer: 1024 * 1024 * 1024,
    env: { ...process.env, CCREC_JAVA: java },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  return JSON.parse(output);
}

function main() {
  const options = parse(process.argv.slice(2));
  const java = findJava();
  if (!java) throw new Error('no Java 17 or later found; set CCREC_JAVA or JAVA_HOME');
  const account = options.account ? ['--account', options.account] : [];

  let listed;
  let since = null;
  let truncated = false;
  if (options.sessions.length > 0) {
    listed = options.sessions.map((sessionId) => ({ sessionId }));
  } else {
    since = Date.now() - options.days * 24 * 60 * 60 * 1000;
    const rows = ccrec(java, 'sessions', '--json', '--limit', String(options.limit), ...account);
    ({ listed, truncated } = selectSessions(rows, since, options.limit));
  }

  const analyses = [];
  const skipped = [];
  for (const session of listed) {
    try {
      const summary = ccrec(java, 'summary', session.sessionId, '--json', ...account);
      const records = ccrec(java, 'show', session.sessionId, '--json', '--full', '--kind', KINDS);
      // The text is read whole once; what is kept of it is cut short.
      for (const r of records) delete r.preview;
      analyses.push(analyzeSession({ session: session.startedAt ? session : null, summary, records, since }));
    } catch (error) {
      skipped.push({ sessionId: session.sessionId, reason: String(error.stderr || error.message).trim().split('\n')[0] });
    }
  }

  const report = {
    scope: {
      generatedAt: new Date().toISOString(),
      since: since ? new Date(since).toISOString() : null,
      sessions: analyses.length,
      skipped,
      // The newest --limit sessions were listed and that many came back: older ones still active
      // in the period may be missing. A larger --limit reads them.
      truncated,
      tokensByModel: totals(analyses),
      promptProfile: promptProfile(analyses),
    },
    patterns: patterns(analyses),
    sessions: analyses,
  };
  const text = JSON.stringify(report, null, 1);
  if (options.out) {
    fs.writeFileSync(options.out, text);
    console.log(`${analyses.length} session(s) analysed into ${options.out} (${Math.round(text.length / 1024)} KB)`);
  } else {
    console.log(text);
  }
}

try {
  main();
} catch (error) {
  console.error(`analyze: ${error.message}`);
  process.exit(1);
}
