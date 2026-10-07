import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export function homeDir() {
  return process.env.CCREC_HOME || path.join(os.homedir(), '.ccrec');
}

/**
 * Creates the recorder's home on first use. Recorded content includes source code and whatever
 * credentials slipped past the redactor, so everything here is readable by the owner only.
 */
export function ensureHome() {
  const home = homeDir();
  for (const dir of [home, path.join(home, 'spool'), path.join(home, 'logs'), path.join(home, 'drivers')]) {
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  }
  // A directory that already existed keeps its old mode, and the engine creates the database file:
  // close the directory and make every file created from here on owner-only.
  fs.chmodSync(home, 0o700);
  process.umask(0o077);
  const properties = path.join(home, 'database.properties');
  if (!fs.existsSync(properties)) {
    const database = path.join(home, 'ccrec.sqlite3');
    fs.writeFileSync(
      properties,
      [
        '# ScalarDB configuration. Replace these lines to record into another database;',
        '# nothing else changes. JDBC drivers that are not bundled go into ./drivers/.',
        'scalar.db.storage=jdbc',
        `scalar.db.contact_points=jdbc:sqlite:${database}?busy_timeout=10000`,
        'scalar.db.username=',
        'scalar.db.password=',
        'scalar.db.transaction_manager=consensus-commit',
        '',
      ].join('\n'),
      { mode: 0o600 },
    );
  }
  const config = path.join(home, 'config.json');
  if (!fs.existsSync(config)) {
    fs.writeFileSync(config, JSON.stringify({ recordThinking: true, redact: true }, null, 2) + '\n', {
      mode: 0o600,
    });
  }
  return home;
}
