import fs from 'node:fs';
import path from 'node:path';

/**
 * What the hooks asked to have recorded and no ingest has recorded yet. The hook never fails and its
 * ingest runs detached, so this is where a recorder that stopped recording shows.
 *
 * `oldest` is the time (epoch milliseconds) of the earliest request still waiting, or null;
 * `unreadable` counts the entries the engine set aside or will set aside.
 */
export function queueStatus(home) {
  const spool = path.join(home, 'spool');
  let names = [];
  try {
    names = fs.readdirSync(spool);
  } catch {
    // Nothing was ever queued.
  }
  let waiting = 0;
  let oldest = null;
  let unreadable = names.filter((name) => name.endsWith('.json.bad')).length;
  for (const name of names.filter((entry) => entry.endsWith('.json'))) {
    let requested;
    try {
      requested = Date.parse(JSON.parse(fs.readFileSync(path.join(spool, name), 'utf8')).ingest_requested_at);
    } catch {
      unreadable++;
      continue;
    }
    if (Number.isNaN(requested)) continue;
    let done = -Infinity;
    try {
      done = fs.statSync(path.join(spool, name.replace(/\.json$/, '.done'))).mtimeMs;
    } catch {
      // Never ingested.
    }
    if (requested > done) {
      waiting++;
      oldest = oldest === null ? requested : Math.min(oldest, requested);
    }
  }
  return { waiting, oldest, unreadable };
}
