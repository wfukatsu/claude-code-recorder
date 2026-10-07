import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

function claudeConfigFile() {
  return process.env.CLAUDE_CONFIG_DIR
    ? path.join(process.env.CLAUDE_CONFIG_DIR, '.claude.json')
    : path.join(os.homedir(), '.claude.json');
}

/**
 * Who is using Claude Code right now. Resolved per session, because `/login` can change the account
 * on the same machine.
 *
 * 1. CCREC_ACCOUNT_ID — set by an administrator (an employee id, say). Wins over everything, and is
 *    the only source for API-key, Bedrock or Vertex users, who have no Claude account.
 * 2. The OAuth account Claude Code is logged in with.
 * 3. The OS user, so that a recording is never left without an owner.
 *
 * This is the client's own statement. A company-wide collector must authenticate the sender and
 * stamp the account itself rather than trust it.
 */
export function resolveIdentity() {
  const hostId = os.hostname() || 'unknown-host';
  const env = process.env;
  if (env.CCREC_ACCOUNT_ID) {
    return {
      host_id: hostId,
      account: {
        account_id: env.CCREC_ACCOUNT_ID,
        email: env.CCREC_ACCOUNT_EMAIL || '',
        display_name: env.CCREC_ACCOUNT_NAME || '',
        org_id: env.CCREC_ORG_ID || '',
        org_name: env.CCREC_ORG_NAME || '',
        auth_method: 'env',
      },
    };
  }
  try {
    const oauth = JSON.parse(fs.readFileSync(claudeConfigFile(), 'utf8')).oauthAccount;
    if (oauth && oauth.accountUuid) {
      return {
        host_id: hostId,
        account: {
          account_id: oauth.accountUuid,
          email: oauth.emailAddress || '',
          display_name: oauth.displayName || oauth.fullName || '',
          org_id: oauth.organizationUuid || '',
          org_name: oauth.organizationName || '',
          auth_method: 'oauth',
        },
      };
    }
  } catch {
    // No readable Claude Code login: fall through to the OS user.
  }
  return {
    host_id: hostId,
    account: {
      account_id: `local-${os.userInfo().username}`,
      email: '',
      display_name: os.userInfo().username,
      org_id: '',
      org_name: '',
      auth_method: 'local',
    },
  };
}
