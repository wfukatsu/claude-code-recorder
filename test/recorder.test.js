import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { runHook } from '../src/hook.js';
import { resolveIdentity } from '../src/identity.js';
import { HOOK_EVENTS, hooksInstalled, installHooks, uninstallHooks } from '../src/install.js';

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
    assert.equal(await runHook(JSON.stringify({ ...event, cwd: project })), 0);
    assert.equal(await runHook('not json'), 0);
    assert.equal(await runHook(JSON.stringify({ ...event, session_id: '../escape' })), 0);
    assert.equal(fs.existsSync(spool) ? fs.readdirSync(spool).length : 0, 0);
  });
  await withEnv({ ...noAdmin, CCREC_HOME: home, CCREC_NO_INGEST: '1', CCREC_DISABLE: '1' }, async () => {
    assert.equal(await runHook(JSON.stringify(event)), 0);
    assert.equal(fs.existsSync(spool) ? fs.readdirSync(spool).length : 0, 0);
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
