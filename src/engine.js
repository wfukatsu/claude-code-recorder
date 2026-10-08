import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

export function jarPath() {
  return process.env.CCREC_JAR || path.join(root, 'lib', 'ccrec-engine.jar');
}

export function javaCommand() {
  if (process.env.CCREC_JAVA) return process.env.CCREC_JAVA;
  if (process.env.JAVA_HOME) return path.join(process.env.JAVA_HOME, 'bin', 'java');
  return 'java';
}

/** The major version of the JVM that would run the engine, or null when there is none. */
export function javaMajorVersion() {
  const result = spawnSync(javaCommand(), ['-version'], { encoding: 'utf8' });
  if (result.error || result.status !== 0) return null;
  const match = /version "(\d+)(?:\.(\d+))?/.exec(`${result.stderr}${result.stdout}`);
  if (!match) return null;
  return match[1] === '1' ? Number(match[2]) : Number(match[1]);
}

function javaArgs(home, args) {
  // Extra JDBC drivers (MySQL, Oracle, …) are picked up from <home>/drivers without a rebuild.
  const classpath = [jarPath(), path.join(home, 'drivers', '*')].join(path.delimiter);
  return ['-cp', classpath, 'dev.ccrec.cli.Main', ...args, '--home', home];
}

export function runEngine(home, args, identity) {
  if (!fs.existsSync(jarPath())) {
    throw new Error(`engine JAR not found at ${jarPath()} — run "npm run build" in the package`);
  }
  const result = spawnSync(javaCommand(), javaArgs(home, args), {
    stdio: 'inherit',
    env: { ...process.env, CCREC_IDENTITY_JSON: JSON.stringify(identity) },
  });
  if (result.error) {
    throw new Error(`could not start Java (${result.error.code}); Java 17 or later is required`);
  }
  return result.status ?? 1;
}

const LOG_BYTES = 1024 * 1024;

/**
 * Starts an ingest that outlives the hook, so Claude Code never waits on the database. The log says
 * when and for which event each one was started; past a megabyte it is moved aside, once.
 */
export function spawnIngest(home, sessionId, eventName) {
  if (!fs.existsSync(jarPath())) return;
  const file = path.join(home, 'logs', 'ingest.log');
  try {
    if (fs.statSync(file).size > LOG_BYTES) fs.renameSync(file, `${file}.1`);
  } catch {
    // No log yet.
  }
  const log = fs.openSync(file, 'a', 0o600);
  fs.writeSync(log, `${new Date().toISOString()} ${eventName} ${sessionId}\n`);
  const child = spawn(javaCommand(), javaArgs(home, ['ingest', '--session', sessionId]), {
    detached: true,
    stdio: ['ignore', log, log],
  });
  child.on('error', () => {});
  child.unref();
  fs.closeSync(log);
}
