import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const HOOK_EVENTS = ['SessionStart', 'Stop', 'SubagentStop', 'SessionEnd'];

const bin = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'bin', 'ccrec.js');

/** Absolute paths, because a hook runs with whatever PATH Claude Code was started with. */
export function hookCommand() {
  return `"${process.execPath}" "${bin}" hook`;
}

export function userSettingsPath() {
  const dir = process.env.CLAUDE_CONFIG_DIR || path.join(os.homedir(), '.claude');
  return path.join(dir, 'settings.json');
}

const isOurs = (hook) => typeof hook.command === 'string' && /ccrec(\.js)?"? hook$/.test(hook.command);

function withoutOurs(settings) {
  for (const event of Object.keys(settings.hooks ?? {})) {
    const groups = settings.hooks[event]
      .map((group) => ({ ...group, hooks: (group.hooks ?? []).filter((hook) => !isOurs(hook)) }))
      .filter((group) => group.hooks.length > 0);
    if (groups.length > 0) settings.hooks[event] = groups;
    else delete settings.hooks[event];
  }
  if (settings.hooks && Object.keys(settings.hooks).length === 0) delete settings.hooks;
  return settings;
}

function load(file) {
  return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : {};
}

function save(file, settings, before) {
  if (before !== null && !fs.existsSync(`${file}.ccrec-bak`)) {
    fs.writeFileSync(`${file}.ccrec-bak`, before);
  }
  // Written beside the file and moved over it, so that a crash leaves Claude Code's settings either
  // as they were or as they should be. A symlinked settings file (a dotfiles checkout) stays one.
  const target = before === null ? file : fs.realpathSync(file);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const temporary = `${target}.${process.pid}.tmp`;
  const mode = before === null ? 0o644 : fs.statSync(target).mode & 0o777;
  try {
    fs.writeFileSync(temporary, JSON.stringify(settings, null, 2) + '\n', { mode });
    fs.chmodSync(temporary, mode);
    fs.renameSync(temporary, target);
  } catch (error) {
    fs.rmSync(temporary, { force: true });
    throw error;
  }
}

/** Adds the recorder's hooks, leaving every other hook and setting as it was. Safe to repeat. */
export function installHooks(file, command = hookCommand()) {
  const before = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
  const settings = withoutOurs(load(file));
  settings.hooks ??= {};
  for (const event of HOOK_EVENTS) {
    (settings.hooks[event] ??= []).push({ hooks: [{ type: 'command', command, timeout: 10 }] });
  }
  save(file, settings, before);
}

export function uninstallHooks(file) {
  if (!fs.existsSync(file)) return;
  const before = fs.readFileSync(file, 'utf8');
  save(file, withoutOurs(load(file)), before);
}

export function hooksInstalled(file) {
  const hooks = load(file).hooks ?? {};
  return HOOK_EVENTS.every((event) => (hooks[event] ?? []).some((group) => (group.hooks ?? []).some(isOurs)));
}
