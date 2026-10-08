import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { runHook } from '../src/hook.js';
import { resolveIdentity } from '../src/identity.js';
import { HOOK_EVENTS, hooksInstalled, installHooks, uninstallHooks } from '../src/install.js';
import { queueStatus } from '../src/queue.js';

function scratch() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'ccrec-test-'));
}

function withEnv(values, body) {
  const saved = {};
  for (const [key, value] of Object.entries(values)) {
    saved[key] = process.env[key];
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  }
  const restore = () => {
    for (const [key, value] of Object.entries(saved)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  };
  return Promise.resolve(body()).finally(restore);
}

const noAdmin = {
  CCREC_ACCOUNT_ID: undefined,
  CCREC_ACCOUNT_EMAIL: undefined,
  CCREC_ORG_ID: undefined,
  CCREC_DISABLE: undefined,
};

test('the Claude login identifies the account', () => {
  const dir = scratch();
  fs.writeFileSync(
    path.join(dir, '.claude.json'),
    JSON.stringify({ oauthAccount: { accountUuid: 'acct-1', emailAddress: 'a@example.com', organizationUuid: 'org-1' } }),
  );
  return withEnv({ ...noAdmin, CLAUDE_CONFIG_DIR: dir }, () => {
    const { account } = resolveIdentity();
    assert.equal(account.account_id, 'acct-1');
    assert.equal(account.email, 'a@example.com');
    assert.equal(account.org_id, 'org-1');
    assert.equal(account.auth_method, 'oauth');
  });
});

test('an administrator-set account id wins over the login', () => {
  const dir = scratch();
  fs.writeFileSync(path.join(dir, '.claude.json'), JSON.stringify({ oauthAccount: { accountUuid: 'acct-1' } }));
  return withEnv({ ...noAdmin, CLAUDE_CONFIG_DIR: dir, CCREC_ACCOUNT_ID: 'emp-42', CCREC_ORG_ID: 'corp' }, () => {
    const { account } = resolveIdentity();
    assert.equal(account.account_id, 'emp-42');
    assert.equal(account.org_id, 'corp');
    assert.equal(account.auth_method, 'env');
  });
});

test('without a login the OS user owns the recording', () => {
  return withEnv({ ...noAdmin, CLAUDE_CONFIG_DIR: scratch() }, () => {
    const { account } = resolveIdentity();
    assert.equal(account.account_id, `local-${os.userInfo().username}`);
    assert.equal(account.auth_method, 'local');
  });
});

test('the hook queues the session and keeps the account it started with', async () => {
  const home = scratch();
  const config = scratch();
  fs.writeFileSync(path.join(config, '.claude.json'), JSON.stringify({ oauthAccount: { accountUuid: 'acct-1' } }));
  const event = (name) =>
    JSON.stringify({ session_id: 'sess-1', transcript_path: '/tmp/sess-1.jsonl', cwd: home, hook_event_name: name });
  const queued = () => JSON.parse(fs.readFileSync(path.join(home, 'spool', 'sess-1.json'), 'utf8'));

  await withEnv({ ...noAdmin, CCREC_HOME: home, CLAUDE_CONFIG_DIR: config, CCREC_NO_INGEST: '1' }, async () => {
    assert.equal(await runHook(event('SessionStart')), 0);
    assert.equal(queued().account.account_id, 'acct-1');
    assert.equal(queued().ended, false);

    // The login changes mid-session; the session stays with the account that started it.
    fs.writeFileSync(path.join(config, '.claude.json'), JSON.stringify({ oauthAccount: { accountUuid: 'acct-2' } }));
    assert.equal(await runHook(event('SessionEnd')), 0);
    assert.equal(queued().account.account_id, 'acct-1');
    assert.equal(queued().ended, true);
  });
  assert.equal(fs.statSync(path.join(home, 'database.properties')).mode & 0o077, 0);
  assert.equal(fs.statSync(home).mode & 0o077, 0);
});

test('the hook records nothing when disabled, opted out, or handed garbage', async () => {
  const home = scratch();
  const project = scratch();
  fs.writeFileSync(path.join(project, '.ccrec-ignore'), '');
  const spool = path.join(home, 'spool');
  const event = { session_id: 'sess-2', transcript_path: '/tmp/x.jsonl', hook_event_name: 'Stop' };

  await withEnv({ ...noAdmin, CCREC_HOME: home, CCREC_NO_INGEST: '1' }, async () => {
    assert.equal(await runHook('not json'), 0);
    assert.equal(await runHook(JSON.stringify({ ...event, session_id: '../escape' })), 0);
    assert.equal(fs.existsSync(spool) ? fs.readdirSync(spool).length : 0, 0);
    assert.equal(await runHook(JSON.stringify({ ...event, cwd: project })), 0);
    assert.deepEqual(fs.readdirSync(spool), ['sess-2.ignored']);
  });
  await withEnv({ ...noAdmin, CCREC_HOME: home, CCREC_NO_INGEST: '1', CCREC_DISABLE: '1' }, async () => {
    assert.equal(await runHook(JSON.stringify({ ...event, session_id: 'sess-3' })), 0);
    assert.deepEqual(fs.readdirSync(spool), ['sess-2.ignored']);
  });
});

test('an opt-out covers the whole project and the whole session', async () => {
  const home = scratch();
  const project = scratch();
  const elsewhere = scratch();
  fs.writeFileSync(path.join(project, '.ccrec-ignore'), '');
  fs.mkdirSync(path.join(project, 'src', 'deep'), { recursive: true });
  const spool = path.join(home, 'spool');
  const event = (session, cwd) =>
    JSON.stringify({ session_id: session, transcript_path: '/tmp/x.jsonl', cwd, hook_event_name: 'Stop' });

  await withEnv({ ...noAdmin, CCREC_HOME: home, CCREC_NO_INGEST: '1' }, async () => {
    // Below the directory that carries the file.
    await runHook(event('sess-a', path.join(project, 'src', 'deep')));
    // The session then moves out of the project: its transcript still holds the project's exchanges.
    await runHook(event('sess-a', elsewhere));
    assert.deepEqual(fs.readdirSync(spool), ['sess-a.ignored']);

    // A session queued elsewhere that enters the project is taken back off the queue.
    await runHook(event('sess-b', elsewhere));
    assert.ok(fs.existsSync(path.join(spool, 'sess-b.json')));
    await runHook(event('sess-b', project));
    assert.deepEqual(fs.readdirSync(spool).sort(), ['sess-a.ignored', 'sess-b.ignored']);
  });
});

test('a session waits in the queue from the hook that asks for it until an ingest records it', async () => {
  const home = scratch();
  const spool = path.join(home, 'spool');
  const event = (session, name) =>
    JSON.stringify({ session_id: session, transcript_path: '/tmp/x.jsonl', cwd: home, hook_event_name: name });
  assert.deepEqual(queueStatus(home), { waiting: 0, oldest: null, unreadable: 0 });

  await withEnv({ ...noAdmin, CCREC_HOME: home, CCREC_NO_INGEST: '1' }, async () => {
    // Starting a session asks for nothing yet.
    await runHook(event('sess-a', 'SessionStart'));
    assert.equal(queueStatus(home).waiting, 0);

    const before = Date.now();
    await runHook(event('sess-a', 'Stop'));
    await runHook(event('sess-b', 'Stop'));
    const status = queueStatus(home);
    assert.equal(status.waiting, 2);
    assert.ok(status.oldest >= before && status.oldest <= Date.now());

    // The engine marks a recorded session; an event that does not ask again keeps it recorded.
    const mark = path.join(spool, 'sess-a.done');
    fs.writeFileSync(mark, '');
    fs.utimesSync(mark, new Date(Date.now() + 1000), new Date(Date.now() + 1000));
    await runHook(event('sess-a', 'SessionStart'));
    assert.equal(queueStatus(home).waiting, 1);

    // The time the engine writes into the mark counts, not what the file system makes of its age.
    fs.writeFileSync(path.join(spool, 'sess-b.done'), new Date(Date.now() + 1000).toISOString());
    fs.utimesSync(path.join(spool, 'sess-b.done'), new Date(0), new Date(0));
    assert.equal(queueStatus(home).waiting, 0);
    fs.writeFileSync(path.join(spool, 'sess-b.done'), new Date(0).toISOString());
    assert.equal(queueStatus(home).waiting, 1);

    fs.writeFileSync(path.join(spool, 'broken.json'), '{broken');
    fs.writeFileSync(path.join(spool, 'older.json.bad'), '{broken');
    assert.equal(queueStatus(home).unreadable, 2);
  });
});

test('installing hooks is repeatable and leaves other settings alone', () => {
  const file = path.join(scratch(), 'settings.json');
  const other = { hooks: [{ type: 'command', command: 'other-tool notify' }] };
  fs.writeFileSync(file, JSON.stringify({ model: 'opus', hooks: { Stop: [other], PreToolUse: [other] } }));

  installHooks(file, '"/usr/bin/node" "/opt/ccrec/bin/ccrec.js" hook');
  installHooks(file, '"/usr/bin/node" "/opt/ccrec/bin/ccrec.js" hook');
  const settings = JSON.parse(fs.readFileSync(file, 'utf8'));
  assert.equal(settings.model, 'opus');
  assert.equal(settings.hooks.Stop.length, 2);
  assert.deepEqual(settings.hooks.PreToolUse, [other]);
  for (const event of HOOK_EVENTS) assert.equal(settings.hooks[event].filter((g) => g.hooks[0].command.endsWith(' hook')).length, 1);
  assert.equal(hooksInstalled(file), true);
  assert.ok(fs.existsSync(`${file}.ccrec-bak`));

  uninstallHooks(file);
  const after = JSON.parse(fs.readFileSync(file, 'utf8'));
  assert.deepEqual(after.hooks, { Stop: [other], PreToolUse: [other] });
  assert.equal(hooksInstalled(file), false);
});

test('installing hooks keeps a symlinked settings file a symlink, with its mode', () => {
  const dir = scratch();
  const real = path.join(dir, 'dotfiles', 'settings.json');
  const link = path.join(dir, 'settings.json');
  fs.mkdirSync(path.dirname(real));
  fs.writeFileSync(real, JSON.stringify({ model: 'opus' }), { mode: 0o600 });
  fs.symlinkSync(real, link);

  installHooks(link, '"/usr/bin/node" "/opt/ccrec/bin/ccrec.js" hook');
  assert.ok(fs.lstatSync(link).isSymbolicLink());
  assert.equal(hooksInstalled(real), true);
  assert.equal(fs.statSync(real).mode & 0o777, 0o600);
  assert.deepEqual(fs.readdirSync(path.dirname(real)), ['settings.json'], 'no temporary file is left');
});
