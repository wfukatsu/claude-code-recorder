import fs from 'node:fs';
import path from 'node:path';
import { jarPath, javaCommand, javaMajorVersion, runEngine } from './engine.js';
import { runHook } from './hook.js';
import { resolveIdentity } from './identity.js';
import { hooksInstalled, installHooks, uninstallHooks, userSettingsPath } from './install.js';
import { ensureHome, homeDir } from './paths.js';

const HELP = `ccrec — record Claude Code exchanges per account, through ScalarDB

Setup
  ccrec init                     create ~/.ccrec with a SQLite configuration
  ccrec install-hooks            add the recording hooks to ~/.claude/settings.json
      --project                  … to ./.claude/settings.json instead
      --settings <file>          … to a specific settings file
  ccrec uninstall-hooks          remove them (same options)
  ccrec doctor                   check Java, the engine, the configuration and the hooks

Recording
  ccrec import <file|dir>...     record existing transcripts (*.jsonl) under your account
  ccrec ingest [--session <id>]  record what the hooks queued (the hooks do this themselves)

Reading
  ccrec whoami                   the account recordings are filed under
  ccrec sessions [--limit n] [--account <id>] [--day yyyymmdd [--org <id>]] [--json]
  ccrec show <session-id> [--full] [--kind k1,k2] [--json]

Environment
  CCREC_HOME        data directory (default ~/.ccrec)
  CCREC_ACCOUNT_ID  account id set by an administrator; overrides the Claude login
                    (with CCREC_ACCOUNT_EMAIL, CCREC_ACCOUNT_NAME, CCREC_ORG_ID, CCREC_ORG_NAME)
  CCREC_DISABLE=1   record nothing
  CCREC_JAVA        the java executable to use (default: JAVA_HOME, then PATH)
`;

function settingsTarget(args) {
  const explicit = args.indexOf('--settings');
  if (explicit >= 0) {
    if (!args[explicit + 1]) throw new Error('--settings needs a file');
    return path.resolve(args[explicit + 1]);
  }
  return args.includes('--project') ? path.resolve('.claude', 'settings.json') : userSettingsPath();
}

function doctor() {
  const home = homeDir();
  const java = javaMajorVersion();
  const checks = [
    ['Java 17 or later', java !== null && java >= 17, java === null ? `${javaCommand()} not runnable` : `found ${java}`],
    ['engine JAR', fs.existsSync(jarPath()), jarPath()],
    ['ScalarDB configuration', fs.existsSync(path.join(home, 'database.properties')), path.join(home, 'database.properties')],
    ['hooks installed', hooksInstalled(userSettingsPath()), userSettingsPath()],
  ];
  for (const [name, ok, detail] of checks) {
    console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name}  (${detail})`);
  }
  const identity = resolveIdentity();
  console.log(`account  ${identity.account.account_id}  via ${identity.account.auth_method}`);
  return checks.every(([, ok]) => ok) ? 0 : 1;
}

export async function main(args) {
  const [command, ...rest] = args;
  switch (command) {
    case undefined:
    case 'help':
    case '--help':
    case '-h':
      console.log(HELP);
      return 0;
    case 'hook':
      return runHook();
    case 'init':
      console.log(ensureHome());
      return 0;
    case 'install-hooks': {
      ensureHome();
      const file = settingsTarget(rest);
      installHooks(file);
      console.log(`hooks added to ${file}`);
      return 0;
    }
    case 'uninstall-hooks': {
      const file = settingsTarget(rest);
      uninstallHooks(file);
      console.log(`hooks removed from ${file}`);
      return 0;
    }
    case 'whoami':
      console.log(JSON.stringify(resolveIdentity(), null, 2));
      return 0;
    case 'doctor':
      return doctor();
    case 'ingest':
    case 'import':
    case 'sessions':
    case 'show':
      return runEngine(ensureHome(), [command, ...rest], resolveIdentity());
    default:
      console.error(`ccrec: unknown command "${command}"\n`);
      console.error(HELP);
      return 2;
  }
}
