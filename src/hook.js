import fs from 'node:fs';
import path from 'node:path';
import { spawnIngest } from './engine.js';
import { resolveIdentity } from './identity.js';
import { ensureHome, homeDir } from './paths.js';

const INGEST_ON = new Set(['Stop', 'SubagentStop', 'SessionEnd']);

/** A project opts out by carrying this file at, or anywhere above, the directory Claude Code is in. */
function optedOut(cwd) {
  for (let dir = path.resolve(cwd); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, '.ccrec-ignore'))) return true;
    if (dir === path.dirname(dir)) return false;
  }
}

// A file's modification time can trail the clock by a few milliseconds; within this of the last
// ingest's start, it is taken as written after it.
const CLOCK_SLACK_MILLIS = 100;

/** When the last ingest that recorded this session started, or null when none has. */
function recordedAt(done) {
  try {
    const written = Date.parse(fs.readFileSync(done, 'utf8').trim());
    return Number.isNaN(written) ? fs.statSync(done).mtimeMs : written;
  } catch {
    return null;
  }
}

/**
 * Whether the session's transcripts were written to since `recorded`. Claude Code fires more events
 * that ask for an ingest than there are responses (one for a helper it runs after each), and
 * starting the engine for nothing costs most of a second of CPU.
 */
export function writtenSince(transcript, sessionId, recorded) {
  const files = [transcript];
  const subagents = path.join(path.dirname(transcript), sessionId, 'subagents');
  try {
    for (const name of fs.readdirSync(subagents)) {
      if (name.endsWith('.jsonl')) files.push(path.join(subagents, name));
    }
  } catch {
    // No sub-agent has run.
  }
  for (const file of files) {
    let modified;
    try {
      modified = fs.statSync(file).mtimeMs;
    } catch {
      continue;
    }
    // A file system that keeps whole seconds says nothing about the rest of that second.
    const since = Number.isInteger(modified / 1000) ? Math.floor(recorded / 1000) * 1000 : recorded - CLOCK_SLACK_MILLIS;
    if (modified >= since) return true;
  }
  return false;
}

function readStdin() {
  return new Promise((resolve) => {
    let data = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (chunk) => (data += chunk));
    process.stdin.on('end', () => resolve(data));
    process.stdin.on('error', () => resolve(data));
  });
}

/**
 * The Claude Code hook. It only queues: the session's transcript path and the account go into a
 * small spool file, and the database work happens in a detached process. It prints nothing and
 * always succeeds — a recorder must never be the reason a session stalls or fails.
 */
export async function runHook(input) {
  let home;
  try {
    if (process.env.CCREC_DISABLE === '1') return 0;
    const event = JSON.parse(input ?? (await readStdin()));
    const sessionId = event.session_id;
    if (!sessionId || !/^[A-Za-z0-9_-]+$/.test(sessionId) || !event.transcript_path) return 0;
    // Ingesting reads the whole transcript, so an opt-out seen once holds for the rest of the session:
    // the marker keeps a later event from another directory from queueing it after all.
    const spool = path.join(homeDir(), 'spool');
    const file = path.join(spool, `${sessionId}.json`);
    const ignored = path.join(spool, `${sessionId}.ignored`);
    if (fs.existsSync(ignored)) return 0;
    if (event.cwd && optedOut(event.cwd)) {
      home = ensureHome();
      fs.writeFileSync(ignored, '', { mode: 0o600 });
      fs.rmSync(file, { force: true });
      fs.rmSync(path.join(spool, `${sessionId}.done`), { force: true });
      return 0;
    }

    home = ensureHome();
    let queued = {};
    try {
      queued = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
      // First event of this session.
    }
    // The account is fixed at the first event seen for a session and kept for its lifetime.
    const identity = queued.account ? { host_id: queued.host_id, account: queued.account } : resolveIdentity();
    const now = new Date().toISOString();
    // The end of a session always asks: its entry has to leave the queue. Anything else asks only
    // when there is something to record.
    const recorded = recordedAt(path.join(spool, `${sessionId}.done`));
    const asks =
      INGEST_ON.has(event.hook_event_name) &&
      (event.hook_event_name === 'SessionEnd' ||
        recorded === null ||
        writtenSince(event.transcript_path, sessionId, recorded));
    const entry = {
      session_id: sessionId,
      transcript_path: event.transcript_path,
      cwd: event.cwd || queued.cwd || '',
      host_id: identity.host_id,
      account: identity.account,
      ended: event.hook_event_name === 'SessionEnd',
      updated_at: now,
      // For whoever reads the queue: Claude Code fires more events than the ones that get an ingest.
      last_event: event.hook_event_name ?? null,
      // Compared with the engine's <session>.done mark: later than it means not yet recorded.
      ingest_requested_at: asks ? now : (queued.ingest_requested_at ?? null),
    };
    const temporary = `${file}.${process.pid}.tmp`;
    fs.writeFileSync(temporary, JSON.stringify(entry), { mode: 0o600 });
    fs.renameSync(temporary, file);

    if (asks && process.env.CCREC_NO_INGEST !== '1') {
      spawnIngest(home, sessionId, event.hook_event_name);
    }
  } catch (error) {
    try {
      if (home) {
        fs.appendFileSync(path.join(home, 'logs', 'hook.err'), `${new Date().toISOString()} ${error.stack}\n`);
      }
    } catch {
      // Nothing more to do.
    }
  }
  return 0;
}
