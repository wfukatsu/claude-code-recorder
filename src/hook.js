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
    const entry = {
      session_id: sessionId,
      transcript_path: event.transcript_path,
      cwd: event.cwd || queued.cwd || '',
      host_id: identity.host_id,
      account: identity.account,
      ended: event.hook_event_name === 'SessionEnd',
      updated_at: new Date().toISOString(),
    };
    const temporary = `${file}.${process.pid}.tmp`;
    fs.writeFileSync(temporary, JSON.stringify(entry), { mode: 0o600 });
    fs.renameSync(temporary, file);

    if (INGEST_ON.has(event.hook_event_name) && process.env.CCREC_NO_INGEST !== '1') {
      spawnIngest(home, sessionId);
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
