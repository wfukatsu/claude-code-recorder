// What can be counted in a session's records without judgement: token use per model, how the tools
// were used, where the answers wavered, and what the prompts gave to work with. Each finding is a
// flag with the records it rests on, cut short; whether it really is a problem is for the reader.

// ---- thresholds -----------------------------------------------------------------------------

export const LIMITS = {
  excerptChars: 300,
  promptChars: 300,
  promptsPerSession: 12,
  flagsPerType: 2,
  repeatedCalls: 3,
  errorRateCalls: 5,
  errorRate: 0.2,
  builtinShellCommands: 5,
  largeResultBytes: 50_000,
  lowCacheRatio: 0.5,
  lowCacheMinTokens: 200_000,
  thinkingShare: 0.6,
  thinkingMinOutput: 20_000,
  // A context this large is read again at every answer, compacted or not.
  longContextTokens: 300_000,
  shortPromptChars: 40,
  troublePromptChars: 200,
  busyTurnToolCalls: 15,
  similarPrompts: 0.8,
  injectionChars: 160,
  emailsListed: 5,
  // Comparing with the last run: a change counts once it is this large, relative to before, and
  // only over enough of what it is counted against.
  changeRatio: 0.25,
  minWorkPrompts: 5,
  minSessions: 2,
};

/** The models that cost the most per token: worth it for judgement, not for looking things up. */
const TOP_TIER = /opus|fable/i;
/** Tools that only read or look up. */
const LOOKUP_TOOLS = new Set(['Read', 'Grep', 'Glob', 'LS', 'WebFetch', 'WebSearch', 'ToolSearch', 'NotebookRead']);
/** Tools that can change what a later call reads. */
const WRITING_TOOLS = new Set(['Edit', 'Write', 'NotebookEdit', 'MultiEdit', 'Bash']);
/** Shell commands a dedicated tool does better: it shows the reader what was read, and costs less. */
const BUILTIN_SHELL = /^\s*(cat|head|tail|grep|rg|find|ls|sed\s+-n)\b/;

const RETRACTION = [
  /すみません|申し訳(ありません|ない)|失礼しました/,
  /(私の)?(間違い|誤り)でした|間違えて(い)?ました|勘違い/,
  /訂正します|撤回します|前言を/,
  /\bI was wrong\b|\bmy mistake\b|\bI apologi[sz]e\b|\bsorry\b/i,
  /\bI (mis(read|understood)|made an error)\b/i,
  /\bactually,? (that|this|it) (is|was)n'?t\b/i,
];
/**
 * Taking back what Claude did: a prompt that opens with a "no", or says it was asked for before. A
 * "not A but B" inside a request is how a request is made, and is not counted.
 */
const CORRECTION = [
  /^(いや[、,\s]|いや違|いいえ|違う|ちがう|違います|違いました|そうじゃな|そうではな|そっちじゃな|それじゃな|じゃなくて|ではなくて)/,
  /(って言った|と言った(のに|はず|よね)|言ったよね|指示(と違|を守っ))/,
  /^(no|nope)\s*[,.!]|^(wrong|that'?s (not|wrong)|not what i)\b/i,
  /\bi (already )?(said|asked)\b/i,
];
/**
 * A go-ahead: what it sets off was agreed on before, so its length says nothing. Every piece of the
 * prompt has to be one, so that "yaml の設定を直して" or "それでは次に…" is not taken for one.
 */
const APPROVAL_PIECE =
  /^(はい|ok|okay|yes|y|うん|了解|お願い(します)?|進めて(ください|下さい)?|続けて(ください|下さい)?|それで(いい|いいです|お願いします|進めて(ください)?|OK)|その(構成|方針|計画|案|内容|まま)で(進めて(ください)?|お願いします|いい|OK)|承認(します)?|lgtm|go ahead|proceed|continue|sounds good|do it)$/i;
const APPROVAL_CHARS = 40;

function isApproval(text) {
  const pieces = text.trim().split(/[、,。.!！?？\n]+/).map((piece) => piece.trim()).filter(Boolean);
  return text.trim().length <= APPROVAL_CHARS && pieces.length > 0 && pieces.every((piece) => APPROVAL_PIECE.test(piece));
}
const VAGUE = [
  /いい感じ|いいかんじ|適当に|なんとか|よしなに|うまく(やって|して)|ちゃんと(して|やって)|きれいに(して)?$/,
  /\b(make it (better|nicer|work)|fix it|clean (it|this) up|do (the|your) (magic|thing)|as you see fit)\b/i,
];
const TROUBLE = /エラー|不具合|バグ|動かない|失敗|落ちる|おかしい|ずれ|崩れ|表示されない|うまくいかない|\berror\b|\bbug\b|\bfail(s|ed|ing)?\b|\bbroken\b|\bcrash/i;
/**
 * What says when the work is done: a state to reach ("わかるようにして", "右にあるべき"), a check that
 * has to pass. "確認して" asks for a check without saying what passes it, and "終わったら" only says
 * what comes next: neither is counted.
 */
const CRITERIA =
  /完了条件|受け入れ条件|ゴールは|期待(する|される|値)|(に|く)なれば(いい|よい|OK|完了)|であれば(いい|よい|OK)|が通る(まで|こと|ように)|が通れば|ことを確(認|かめ)|[るく]ように(して|する|なれば)|(ある|なる|する)べき|\bexpected\b|\bdone when\b|\bacceptance\b|\bso that\b|\buntil\b[^.]*\b(pass|passes|green|works)\b|\b(should|must) (pass|return|show|print|match)\b/i;
/** The user asking to be asked: questions that follow are what was wanted. The tool's name is often mistyped. */
const ASK_ME = /AskUser|質問して|聞いて(から|ください)|確認してから進め|ask me|ask (before|first)/i;
const CONSTRAINT = /しないで|しないこと|触らない|変えない|だけ(を|に|で)|のみ|以外は|禁止|\bdon'?t\b|\bdo not\b|\bonly\b|\bwithout\b|\bmust not\b/i;
const PATH = /(^|[\s`'"(])(~|\.{1,2})?\/[\w.@-]+\/[\w./@-]*|\b[\w-]+\.(js|ts|tsx|py|java|go|rs|rb|md|json|ya?ml|sql|sh|css|html|kt|swift|c|cc|cpp|h)\b/;
const URL = /https?:\/\/\S+/;
const CODE = /```|^\s{4}\S|\b(Exception|Traceback|at [\w.$]+\(|line \d+)\b/m;

// ---- secrets ---------------------------------------------------------------------------------

/**
 * Credentials by shape: the ones ccrec masks when it records (engine/…/Redactor.java), for a store
 * that records with masking off, and the ones it does not — a cookie jar's session value, a
 * password said in words. `value` names the group that is the secret; without one, all of it is.
 */
const SECRETS = [
  { kind: 'Anthropic API key', pattern: /sk-ant-[A-Za-z0-9_-]{20,}/g },
  { kind: 'OpenAI API key', pattern: /sk-(?:proj|svcacct|admin)-[A-Za-z0-9_-]{20,}|\bsk-[A-Za-z0-9]{32,}/g },
  { kind: 'AWS access key', pattern: /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/g },
  { kind: 'GitHub token', pattern: /\bgh[pousr]_[A-Za-z0-9]{36,}\b|\bgithub_pat_[A-Za-z0-9_]{40,}\b/g },
  { kind: 'GitLab token', pattern: /\bglpat-[A-Za-z0-9_-]{20,}/g },
  { kind: 'Slack token', pattern: /\bxox[abprs]-[A-Za-z0-9-]{10,}|https:\/\/hooks\.slack\.com\/services\/[A-Za-z0-9/]{20,}/g },
  { kind: 'Google key or token', pattern: /\bAIza[A-Za-z0-9_-]{35}\b|\bya29\.[A-Za-z0-9_-]{20,}/g },
  { kind: 'Stripe key', pattern: /\b[sr]k_(?:live|test)_[A-Za-z0-9]{16,}|\bwhsec_[A-Za-z0-9]{24,}/g },
  { kind: 'npm / Hugging Face / SendGrid token', pattern: /\bnpm_[A-Za-z0-9]{36}\b|\bhf_[A-Za-z0-9]{30,}\b|\bSG\.[A-Za-z0-9_-]{22}\.[A-Za-z0-9_-]{43}\b/g },
  { kind: 'JWT', pattern: /\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}/g },
  { kind: 'private key', pattern: /-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----/g },
  { kind: 'password in a URL', pattern: /\b[A-Za-z][A-Za-z0-9+.-]*:\/\/[^\s/:@"'<>]+:([^\s/@"'<>\\]+)(?=@)/g, value: 1 },
  { kind: 'Authorization header', pattern: /\b(?:authorization["']?\s*[:=]\s*["']?(?:basic|bearer|token)|bearer)\s+([A-Za-z0-9._~+/=-]{20,})/gi, value: 1 },
  { kind: 'secret in an assignment', pattern: /\b(?:[A-Z0-9]+_)*(?:SECRET|PASSWORD|PASSWD|TOKEN|API_KEY|APIKEY|ACCESS_KEY|PRIVATE_KEY|CREDENTIALS)(?:_[A-Z0-9]+)*=["']?(?![$<{])([^\s"']{8,})/g, value: 1 },
  { kind: 'session cookie', pattern: /["']?\b(?:csrftoken|csrf_token|sessionid|session_id|sid|connect\.sid|_session|auth_token|access_token|refresh_token)["']?\s*[:=]\s*["']?([A-Za-z0-9%._~+/=-]{12,})/gi, value: 1 },
  // A value with a digit or a symbol in it: "パスワードは環境変数にある" names no password.
  { kind: 'password', pattern: /(?:\bpassword|\bpasswd|\bpwd|パスワード)\s*(?:は|:|：|=)\s*["'「]?((?=[!-~]*[0-9!@#$%^&*_+=.-])[!-~]{6,})/gi, value: 1 },
];
const RECORDER_MASK = '[REDACTED]';

/** A secret as far as it may be shown: its first four characters. */
const masked = (secret) => `${secret.slice(0, 4)}****`;

/** The secrets in a text, by kind, each masked. */
export function findSecrets(text) {
  const found = [];
  const source = String(text ?? '');
  for (const { kind, pattern, value } of SECRETS) {
    for (const match of source.matchAll(pattern)) {
      const secret = value ? match[value] : match[0];
      if (secret && !secret.includes(RECORDER_MASK)) found.push({ kind, masked: masked(secret) });
    }
  }
  const recorded = source.split(RECORDER_MASK).length - 1;
  if (recorded > 0) found.push({ kind: 'masked by ccrec when recorded', masked: `${RECORDER_MASK} ×${recorded}` });
  return found;
}

/** A text with every secret in it masked: what any excerpt goes through before it is shown. */
export function maskSecrets(text) {
  let result = String(text ?? '');
  for (const { pattern, value } of SECRETS) {
    result = result.replace(pattern, (whole, ...groups) => {
      const secret = value ? groups[value - 1] : whole;
      return typeof secret === 'string' ? whole.replace(secret, masked(secret)) : whole;
    });
  }
  return result;
}

// ---- what should not have been in a prompt, and what tried to steer Claude -------------------

const CONFIDENTIAL = /社外秘|部外秘|極秘|機密|取扱注意|個人情報|マイナンバー|\bconfidential\b|\binternal only\b|\bNDA\b|\bproprietary\b|\bPII\b/i;
const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;
const CARD = /\b(?:\d[ -]?){15,16}\b/g;

function luhn(digits) {
  let sum = 0;
  for (let i = 0; i < digits.length; i++) {
    let d = Number(digits[digits.length - 1 - i]);
    if (i % 2 === 1) {
      d *= 2;
      if (d > 9) d -= 9;
    }
    sum += d;
  }
  return sum % 10 === 0;
}

/** Signs that a prompt carries what is not to be shared: a marking, a card number, a list of people. */
export function findConfidential(text) {
  const found = [];
  const marking = CONFIDENTIAL.exec(text);
  if (marking) found.push({ kind: 'marked confidential', detail: marking[0] });
  // An id in an address (a file, an issue) is not a card, however its digits add up.
  const cards = [...text.replace(/https?:\/\/\S+/g, ' ').matchAll(CARD)]
    .map((m) => m[0].replace(/\D/g, ''))
    .filter((d) => (d.length === 15 || d.length === 16) && luhn(d));
  if (cards.length > 0) found.push({ kind: 'card number', detail: `${cards.length} (${cards.map((d) => `****${d.slice(-4)}`).join(', ')})` });
  const emails = new Set(text.match(EMAIL) ?? []);
  if (emails.size >= LIMITS.emailsListed) found.push({ kind: 'list of email addresses', detail: `${emails.size} addresses` });
  return found;
}

/**
 * Text written to steer a model away from what its user asked: to drop its instructions, to take on
 * another role, to give away its prompt or the user's data, to keep quiet about it.
 */
const INJECTION = [
  /\b(ignore|disregard|forget|override)\b[^.\n]{0,40}\b(previous|prior|above|earlier|all|any|your)\b[^.\n]{0,20}\b(instructions?|prompts?|rules|guidelines)\b/i,
  /\byou are now\b|\bfrom now on,? you (are|will|must)\b|\bnew (system )?instructions?\s*:/i,
  /\b(reveal|print|output|show|repeat|leak)\b[^.\n]{0,30}\b(system prompt|your instructions|hidden instructions)\b/i,
  /\bdo not (tell|inform|mention|reveal)\b[^.\n]{0,30}\b(the )?user\b/i,
  /\b(send|post|upload|exfiltrate|forward)\b[^.\n]{0,60}\b(credentials?|secrets?|tokens?|keys?|passwords?|\.env|ssh)\b[^.\n]{0,40}\bhttps?:\/\//i,
  /(以前|これまで|前|上記|先ほど)の(指示|命令|ルール|プロンプト)(を|は)(すべて|全て)?(無視|忘れ)/,
  /システムプロンプト(を|の内容を)(表示|出力|教え|開示|見せ)/,
  /ユーザー(に|には)(伝え|言わ|知らせ|報告し)(ないで|るな|てはいけない)/,
  /(認証情報|秘密鍵|トークン|パスワード|APIキー)[^。\n]{0,30}(https?:\/\/|外部|次の(URL|アドレス)|サーバー)[^。\n]{0,40}(送信して|送って|アップロードして)/,
];

/**
 * Tools whose results Claude Code itself writes: a sub-agent's report with the harness's notes
 * ("do not mention to user"), a skill's own text, tool descriptions. What a sub-agent read from
 * outside is in its own records, and is checked there.
 */
const HARNESS_TOOLS = new Set(['Agent', 'Task', 'SendMessage', 'TaskOutput', 'Skill', 'ToolSearch']);

/** The first sentence in a text that reads like an injection, cut short and masked. */
export function findInjection(text) {
  const source = String(text ?? '');
  for (const pattern of INJECTION) {
    const match = pattern.exec(source);
    if (match) {
      const from = Math.max(0, match.index - 60);
      return excerpt(source.slice(from, match.index + match[0].length + 60), LIMITS.injectionChars);
    }
  }
  return null;
}

// ---- helpers --------------------------------------------------------------------------------

/** What may be shown of a text: in one line, cut short, with any secret in it masked. */
export function excerpt(text, chars = LIMITS.excerptChars) {
  const flat = maskSecrets(text).replace(/\s+/g, ' ').trim();
  return flat.length <= chars ? flat : `${flat.slice(0, chars)}…`;
}

function bodyOf(record) {
  return record.content ?? record.preview ?? '';
}

function attributesOf(record) {
  if (!record.attributes) return {};
  try {
    return typeof record.attributes === 'string' ? JSON.parse(record.attributes) : record.attributes;
  } catch {
    return {};
  }
}

function inputOf(record) {
  try {
    return JSON.parse(bodyOf(record));
  } catch {
    return {};
  }
}

const iso = (ts) => (ts ? new Date(ts).toISOString() : null);

/** What a tool call was about, in a line. */
function gist(record) {
  const input = inputOf(record);
  for (const field of ['command', 'file_path', 'notebook_path', 'pattern', 'url', 'query', 'description', 'prompt']) {
    if (typeof input[field] === 'string' && input[field].trim()) return excerpt(input[field], 120);
  }
  return '';
}

/**
 * What the user typed, without what Claude Code wraps around it. Null for a prompt that is only a
 * wrapper — a slash command's output, a notification, a reminder — which nobody wrote. What was
 * pasted is the user's too, but it can be anything (a log, a cookie jar): it is said in a few words.
 */
export function typedText(text) {
  const rest = String(text ?? '')
    .replace(/<pasted_content(\s[^<>]*)?>([\s\S]*?)<\/pasted_content(\s[^<>]*)?>/g, (_, _attributes, body) => ` ${pasted(body.trim().length)} `)
    .replace(WRAPPER, '')
    .replace(NOTICE, '')
    .trim();
  return rest || null;
}

/**
 * What Claude Code puts into a prompt that nobody typed. Only these come out: markup the user typed
 * (an HTML snippet to change) is part of what was asked.
 */
/** Claude Code's own one-line notes in the user's turn, such as an interruption. */
const NOTICE = /^\s*\[Request interrupted by user[^\]]*\]\s*$/gm;
const WRAPPER =
  /<(command-[\w-]+|local-command-[\w-]+|bash-[\w-]+|system-reminder|task-notification|user-prompt-submit-hook|ide_[\w-]+|teammate-message)(\s[^<>]*)?>[\s\S]*?<\/\1(\s[^<>]*)?>/g;

const pasted = (chars) => `[pasted: ${chars} chars]`;

/** What was pasted into a prompt, which typedText leaves out of everything else. */
function pastedBlocks(text) {
  return [...String(text ?? '').matchAll(/<pasted_content(\s[^<>]*)?>([\s\S]*?)<\/pasted_content(\s[^<>]*)?>/g)].map((m) => m[2]);
}
const PASTED = /\[pasted: \d+ chars\]/;

function bigrams(text) {
  const flat = text.toLowerCase().replace(/\s+/g, ' ');
  const set = new Set();
  for (let i = 0; i < flat.length - 1; i++) set.add(flat.slice(i, i + 2));
  return set;
}

export function similarity(a, b) {
  const x = bigrams(a);
  const y = bigrams(b);
  if (x.size === 0 || y.size === 0) return 0;
  let shared = 0;
  for (const gram of x) if (y.has(gram)) shared++;
  return shared / (x.size + y.size - shared);
}

/** What a prompt gives to work with. */
export function promptFeatures(text) {
  return {
    chars: text.length,
    path: PATH.test(text),
    url: URL.test(text),
    code: CODE.test(text),
    pasted: PASTED.test(text),
    criteria: CRITERIA.test(text),
    constraint: CONSTRAINT.test(text),
    trouble: TROUBLE.test(text),
    vague: VAGUE.some((pattern) => pattern.test(text)),
    correction: CORRECTION.some((pattern) => pattern.test(text.trim())),
    approval: isApproval(text),
    askMe: ASK_ME.test(text),
  };
}

// ---- one session ----------------------------------------------------------------------------

/**
 * @param session the row `ccrec sessions --json` lists, or null
 * @param summary what `ccrec summary --json` says of it: its title and project, nothing counted
 * @param records what `ccrec show --json --full` lists: the main thread, then each sub-agent
 * @param since only what happened from then on (epoch ms) is counted; null for the whole session.
 *     A session picked because it was active lately may have begun long before.
 */
export function analyzeSession({ session, summary, records: all, since = null }) {
  // A record without a time of its own (rare) goes with the session rather than be lost.
  const records = since === null ? all : all.filter((r) => r.ts == null || r.ts >= since);
  const flags = [];
  const flag = (type, category, severity, fields) => flags.push({ type, category, severity, ...fields });
  const results = new Map();
  const agentOf = new Map();
  for (const r of records) {
    if (r.kind === 'tool_result' && r.toolUseId) {
      results.set(r.toolUseId, r);
      const agent = attributesOf(r).agent_id;
      if (agent) agentOf.set(r.toolUseId, agent);
    }
  }

  // ---- tokens and models ----
  const byModel = {};
  const byAgent = {};
  let maxContext = 0;
  const total = { input: 0, cacheRead: 0, cacheWrite: 0, output: 0, thinking: 0 };
  for (const r of records) {
    const agent = (byAgent[r.agentId] ??= { models: new Set(), tools: {}, outputTokens: 0, task: null });
    if (r.kind === 'tool_use' && r.toolName) agent.tools[r.toolName] = (agent.tools[r.toolName] ?? 0) + 1;
    if (r.inputTokens == null && r.outputTokens == null) continue;
    const model = r.model ?? 'unknown';
    // Claude Code's own stand-ins for a message no model wrote, such as <synthetic>.
    if (model.startsWith('<')) continue;
    agent.models.add(model);
    const row = (byModel[model] ??= { messages: 0, input: 0, cacheRead: 0, cacheWrite: 0, output: 0, thinking: 0 });
    const spent = {
      input: r.inputTokens ?? 0,
      cacheRead: r.cacheReadTokens ?? 0,
      cacheWrite: r.cacheCreationTokens ?? 0,
      output: r.outputTokens ?? 0,
      thinking: r.thinkingTokens ?? 0,
    };
    row.messages++;
    for (const [name, value] of Object.entries(spent)) {
      row[name] += value;
      total[name] += value;
    }
    agent.outputTokens += spent.output;
    maxContext = Math.max(maxContext, spent.input + spent.cacheRead + spent.cacheWrite);
  }
  for (const r of records) {
    if (r.kind === 'tool_use' && agentOf.has(r.toolUseId)) {
      const agent = byAgent[agentOf.get(r.toolUseId)];
      if (agent) agent.task = gist(r);
    }
  }
  const read = total.input + total.cacheRead + total.cacheWrite;
  const cacheRatio = read ? total.cacheRead / read : null;
  const thinkingShare = total.output ? total.thinking / total.output : null;

  for (const [agentId, agent] of Object.entries(byAgent)) {
    const tools = Object.keys(agent.tools);
    const topTier = [...agent.models].filter((model) => TOP_TIER.test(model));
    if (agentId !== 'main' && tools.length > 0 && tools.every((tool) => LOOKUP_TOOLS.has(tool)) && topTier.length > 0) {
      flag('heavy-model-for-lookup', 'model', 'medium', {
        agentId,
        detail: `${topTier.join(', ')} only looked things up (${tools.join(', ')}); output ${agent.outputTokens} tokens`,
        evidence: [{ role: 'task', text: agent.task ?? '' }],
      });
    }
  }
  if (cacheRatio !== null && cacheRatio < LIMITS.lowCacheRatio && read >= LIMITS.lowCacheMinTokens) {
    flag('low-cache-hit', 'model', 'low', { detail: `cache reads were ${Math.round(cacheRatio * 100)}% of the ${read} input tokens` });
  }
  if (thinkingShare !== null && thinkingShare >= LIMITS.thinkingShare && total.output >= LIMITS.thinkingMinOutput) {
    flag('high-thinking-share', 'model', 'low', { detail: `thinking was ${Math.round(thinkingShare * 100)}% of ${total.output} output tokens` });
  }
  // Counted from the records rather than taken from the summary, which covers the whole session.
  let compactions = 0;
  let interrupted = 0;
  const apiErrorCounts = {};
  const denialCounts = {};
  for (const r of records) {
    const said = attributesOf(r);
    if (r.kind === 'system' && r.subtype === 'compact_boundary') compactions++;
    if (said.interrupted) interrupted++;
    if (said.api_error || said.api_error_status) {
      const error = `${said.api_error ?? 'error'}${said.api_error_status ? ` ${said.api_error_status}` : ''}`;
      apiErrorCounts[error] = (apiErrorCounts[error] ?? 0) + 1;
    }
    if (typeof said.tool_denial === 'string') denialCounts[said.tool_denial] = (denialCounts[said.tool_denial] ?? 0) + 1;
  }
  if (compactions > 0) {
    flag('context-compacted', 'model', 'medium', {
      detail: `the context was compacted ${compactions} time(s); the largest context was ${maxContext} tokens`,
    });
  }
  if (maxContext >= LIMITS.longContextTokens) {
    const messages = Object.values(byModel).reduce((sum, row) => sum + row.messages, 0);
    flag('long-context', 'model', 'medium', {
      detail: `the context reached ${maxContext} tokens; ${messages} answers read ${Math.round(read / messages)} tokens each on average, ${total.cacheRead} from the cache in all`,
    });
  }

  // ---- tools ----
  const calls = new Map();
  const builtinShell = [];
  // Reading a file again after it was changed is work, not waste: a repeat counts only when
  // nothing that writes ran in between.
  const changes = {};
  const largeResults = [];
  for (const r of records) {
    if (r.kind === 'tool_use') {
      const key = `${r.agentId}\n${r.toolName}\n${bodyOf(r)}`;
      const before = changes[r.agentId] ?? 0;
      const seen = calls.get(key) ?? { record: r, run: 0, most: 0, afterError: 0, lastErrored: false, changesAfter: -1 };
      // The call itself may write; only the others' writes in between break a run of repeats.
      if (seen.changesAfter === before) {
        seen.run++;
        if (seen.lastErrored) seen.afterError++;
      } else {
        seen.run = 1;
      }
      seen.most = Math.max(seen.most, seen.run);
      if (WRITING_TOOLS.has(r.toolName)) changes[r.agentId] = before + 1;
      seen.changesAfter = changes[r.agentId] ?? 0;
      seen.lastErrored = results.get(r.toolUseId)?.subtype === 'error';
      calls.set(key, seen);
      const command = inputOf(r).command;
      if (r.toolName === 'Bash' && typeof command === 'string' && BUILTIN_SHELL.test(command)) builtinShell.push(r);
    } else if (r.kind === 'tool_result' && r.contentBytes > LIMITS.largeResultBytes) {
      largeResults.push(r);
    }
  }
  for (const { record, most, afterError } of calls.values()) {
    if (afterError > 0) {
      flag('retry-after-error', 'tools', 'medium', {
        agentId: record.agentId,
        at: iso(record.ts),
        detail: `${record.toolName} was called again with the same input after it failed (${afterError}×)`,
        evidence: [{ role: 'call', text: gist(record) }, { role: 'error', text: excerpt(bodyOf(results.get(record.toolUseId) ?? {})) }],
      });
    } else if (most >= LIMITS.repeatedCalls) {
      flag('repeated-call', 'tools', 'medium', {
        agentId: record.agentId,
        at: iso(record.ts),
        detail: `${record.toolName} was called ${most}× with the same input while nothing was changed in between`,
        evidence: [{ role: 'call', text: gist(record) }],
      });
    }
  }
  const toolCounts = {};
  for (const r of records) {
    if (r.kind !== 'tool_use' || !r.toolName) continue;
    const counts = (toolCounts[r.toolName] ??= { tool: r.toolName, calls: 0, errors: 0 });
    counts.calls++;
    if (results.get(r.toolUseId)?.subtype === 'error') counts.errors++;
  }
  for (const tool of Object.values(toolCounts)) {
    if (tool.calls >= LIMITS.errorRateCalls && tool.errors / tool.calls >= LIMITS.errorRate) {
      const failed = records.filter((r) => r.kind === 'tool_use' && r.toolName === tool.tool && results.get(r.toolUseId)?.subtype === 'error');
      flag('tool-error-rate', 'tools', 'medium', {
        detail: `${tool.tool} failed ${tool.errors} of ${tool.calls} calls`,
        evidence: failed.slice(0, 2).flatMap((r) => [
          { role: 'call', at: iso(r.ts), text: gist(r) },
          { role: 'error', text: excerpt(bodyOf(results.get(r.toolUseId))) },
        ]),
      });
    }
  }
  if (builtinShell.length >= LIMITS.builtinShellCommands) {
    flag('shell-for-builtin', 'tools', 'low', {
      detail: `${builtinShell.length} shell commands read or searched files that Read, Grep or Glob could have`,
      evidence: builtinShell.slice(0, 3).map((r) => ({ role: 'call', at: iso(r.ts), text: gist(r) })),
    });
  }
  if (largeResults.length > 0) {
    flag('large-results', 'tools', 'low', {
      detail: `${largeResults.length} tool result(s) over ${LIMITS.largeResultBytes / 1000} KB went into the context`,
      evidence: largeResults.slice(0, 3).map((r) => ({ role: 'result', at: iso(r.ts), text: `${r.contentBytes} bytes: ${excerpt(bodyOf(r), 120)}` })),
    });
  }
  const denials = Object.entries(denialCounts);
  if (denials.length > 0) {
    flag('tool-denials', 'tools', 'low', { detail: `denied: ${denials.map(([kind, n]) => `${kind} ${n}`).join(', ')}` });
  }

  // ---- reasoning ----
  for (const r of records) {
    if (r.kind !== 'assistant_text' && r.kind !== 'thinking') continue;
    const text = bodyOf(r);
    const match = RETRACTION.map((pattern) => pattern.exec(text)).find(Boolean);
    if (match) {
      const from = Math.max(0, match.index - 120);
      flag('retraction', 'reasoning', 'medium', {
        agentId: r.agentId,
        at: iso(r.ts),
        detail: `the ${r.kind === 'thinking' ? 'thinking' : 'answer'} took something back ("${match[0]}")`,
        evidence: [{ role: r.kind, text: excerpt(text.slice(from), LIMITS.excerptChars) }],
      });
    }
  }
  if (interrupted > 0) {
    flag('interrupted', 'reasoning', 'medium', { detail: `the user interrupted ${interrupted} time(s)` });
  }
  const apiErrors = Object.entries(apiErrorCounts);
  if (apiErrors.length > 0) {
    flag('api-errors', 'reasoning', 'low', { detail: apiErrors.map(([kind, n]) => `${kind} ${n}`).join(', ') });
  }

  // ---- security ----
  const modes = new Set(records.map((r) => attributesOf(r).permission_mode).filter(Boolean));
  const unattended = modes.has('bypassPermissions');
  const calledBy = new Map(records.filter((r) => r.kind === 'tool_use' && r.toolUseId).map((r) => [r.toolUseId, r]));
  records.forEach((r, index) => {
    if (r.kind === 'user_prompt' && r.agentId === 'main' && !attributesOf(r).compact_summary) {
      const body = bodyOf(r);
      const secrets = findSecrets(body);
      if (secrets.length > 0) {
        flag('secret-in-prompt', 'security', 'high', {
          at: iso(r.ts),
          detail: `a prompt carried ${secrets.length} credential(s)${pastedBlocks(body).some((b) => findSecrets(b).length) ? ', in what was pasted' : ''}`,
          evidence: secrets.slice(0, 5).map((s) => ({ role: 'secret', text: `${s.kind}: ${s.masked}` })),
        });
      }
      const confidential = findConfidential(typedText(body) ?? '').concat(pastedBlocks(body).flatMap(findConfidential));
      if (confidential.length > 0) {
        flag('confidential-in-prompt', 'security', 'medium', {
          at: iso(r.ts),
          detail: 'a prompt carried what may not be meant to leave its owner',
          evidence: confidential.map((c) => ({ role: 'sign', text: `${c.kind}: ${c.detail}` })),
        });
      }
      // What the user typed is the user's own instruction; what was pasted came from elsewhere.
      for (const block of pastedBlocks(body)) {
        const sentence = findInjection(block);
        if (sentence) {
          flag('injection-suspected', 'security', unattended ? 'critical' : 'high', {
            at: iso(r.ts),
            detail: `pasted text reads like an instruction to the model${unattended ? '; the session ran with permissions bypassed' : ''}`,
            evidence: [{ role: 'source', text: 'pasted into a prompt' }, { role: 'sentence', text: sentence }],
          });
          break;
        }
      }
    } else if (r.kind === 'tool_result') {
      const call = calledBy.get(r.toolUseId);
      if (HARNESS_TOOLS.has(call?.toolName)) return;
      const sentence = findInjection(bodyOf(r));
      if (!sentence) return;
      const next = records.slice(index + 1).find((later) => later.agentId === r.agentId && later.kind === 'assistant_text');
      flag('injection-suspected', 'security', unattended ? 'critical' : 'high', {
        agentId: r.agentId,
        at: iso(r.ts),
        detail: `a ${call?.toolName ?? 'tool'} result reads like an instruction to the model${unattended ? '; the session ran with permissions bypassed' : ''}`,
        evidence: [
          { role: 'source', text: `${call?.toolName ?? 'tool'}: ${call ? gist(call) : ''}` },
          { role: 'sentence', text: sentence },
          { role: 'next answer', text: excerpt(next ? bodyOf(next) : '', 200) },
        ],
      });
    }
  });

  // ---- the prompts ----
  const main = records.filter((r) => r.agentId === 'main');
  const prompts = [];
  let current = null;
  let lastAnswer = null;
  for (const r of main) {
    if (r.kind === 'user_prompt') {
      const attributes = attributesOf(r);
      const text = attributes.compact_summary ? null : typedText(bodyOf(r));
      if (text === null) continue;
      current = { at: iso(r.ts), text, features: promptFeatures(text), toolCalls: 0, questions: [], answerBefore: lastAnswer };
      prompts.push(current);
      lastAnswer = null;
    } else if (r.kind === 'assistant_text') {
      lastAnswer = bodyOf(r);
    } else if (r.kind === 'tool_use' && current) {
      current.toolCalls++;
      if (r.toolName === 'AskUserQuestion') current.questions.push(excerpt(inputOf(r).questions?.map((q) => q.question).join(' / ') ?? bodyOf(r), 200));
    }
  }
  prompts.forEach((prompt, index) => {
    const f = prompt.features;
    const anchored = f.path || f.url || f.code || f.pasted;
    const quote = [{ role: 'prompt', at: prompt.at, text: excerpt(prompt.text, LIMITS.promptChars) }];
    // A correction answers something Claude said since the prompt before.
    if (f.correction && index > 0 && prompt.answerBefore) {
      flag('user-correction', 'instructions', 'high', {
        at: prompt.at,
        detail: 'the user had to correct what Claude did or understood',
        evidence: [
          { role: 'earlier prompt', at: prompts[index - 1].at, text: excerpt(prompts[index - 1].text, LIMITS.promptChars) },
          { role: 'answer', text: excerpt(prompt.answerBefore) },
          ...quote,
        ],
      });
    }
    if (f.vague) {
      flag('vague-wording', 'instructions', 'medium', { at: prompt.at, detail: 'the prompt leaves what "good" means to Claude', evidence: quote });
    }
    if (f.trouble && !anchored && f.chars < LIMITS.troublePromptChars) {
      flag('trouble-without-evidence', 'instructions', 'medium', {
        at: prompt.at,
        detail: 'a problem was reported without the error, log, file or steps that show it',
        evidence: quote,
      });
    }
    if (f.chars < LIMITS.shortPromptChars && !anchored && !f.approval && prompt.toolCalls >= LIMITS.busyTurnToolCalls) {
      flag('short-prompt-big-task', 'instructions', 'medium', {
        at: prompt.at,
        detail: `a ${f.chars}-character prompt set off ${prompt.toolCalls} tool calls`,
        evidence: quote,
      });
    }
    if (prompt.questions.length > 0 && !f.askMe) {
      flag('clarification-asked', 'instructions', 'low', {
        at: prompt.at,
        detail: `Claude had to ask ${prompt.questions.length} time(s) before going on`,
        evidence: [...quote, ...prompt.questions.map((q) => ({ role: 'question', text: q }))],
      });
    }
    for (let earlier = 0; earlier < index; earlier++) {
      if (prompt.text.length >= 20 && similarity(prompts[earlier].text, prompt.text) >= LIMITS.similarPrompts) {
        flag('repeated-prompt', 'instructions', 'medium', {
          at: prompt.at,
          detail: 'much the same prompt was sent again',
          evidence: [{ role: 'earlier prompt', at: prompts[earlier].at, text: excerpt(prompts[earlier].text, 200) }, ...quote],
        });
        break;
      }
    }
  });

  const turns = records.filter((r) => r.kind === 'system' && r.subtype === 'turn_duration').length;
  const work = prompts.filter((p) => !p.features.approval);
  const profile = {
    work: work.length,
    criteria: work.filter((p) => p.features.criteria).length,
    constraint: work.filter((p) => p.features.constraint).length,
    anchored: work.filter((p) => p.features.path || p.features.url || p.features.code || p.features.pasted).length,
  };

  return {
    sessionId: summary?.sessionId ?? session?.sessionId ?? all[0]?.sessionId,
    title: summary?.title ?? session?.title ?? null,
    project: summary?.projectPath ?? session?.projectPath ?? null,
    startedAt: summary?.startedAt ?? iso(session?.startedAt),
    metrics: {
      turns,
      prompts: prompts.length,
      subAgents: Object.keys(byAgent).filter((agent) => agent !== 'main').length,
      toolCalls: Object.values(toolCounts).reduce((sum, tool) => sum + tool.calls, 0),
      toolErrors: Object.values(toolCounts).reduce((sum, tool) => sum + tool.errors, 0),
      tokens: total,
      cacheReadRatio: cacheRatio === null ? null : round(cacheRatio),
      thinkingShare: thinkingShare === null ? null : round(thinkingShare),
      outputPerTurn: turns ? Math.round(total.output / turns) : null,
      maxContextTokens: maxContext,
      compactions,
      promptProfile: profile,
      byModel,
      subAgentModels: Object.entries(byAgent)
        .filter(([id]) => id !== 'main')
        .reduce((counts, [, agent]) => {
          for (const model of agent.models) counts[model] = (counts[model] ?? 0) + 1;
          return counts;
        }, {}),
    },
    prompts: prompts.slice(0, LIMITS.promptsPerSession).map((p) => ({
      at: p.at,
      text: excerpt(p.text, LIMITS.promptChars),
      features: p.features,
      toolCalls: p.toolCalls,
    })),
    flags: trimmed(flags),
  };
}

const round = (n) => Math.round(n * 1000) / 1000;
const SEVERITY = { critical: -1, high: 0, medium: 1, low: 2 };

/** The first few flags of each type, in the order they happened, each with how many there were in all; by severity. */
function trimmed(flags) {
  const byType = new Map();
  for (const f of flags) {
    const list = byType.get(f.type) ?? [];
    list.push(f);
    byType.set(f.type, list);
  }
  const kept = [];
  for (const list of byType.values()) {
    list.slice(0, LIMITS.flagsPerType).forEach((f) => kept.push({ ...f, ofType: list.length }));
  }
  return kept.sort((a, b) => SEVERITY[a.severity] - SEVERITY[b.severity]);
}

// ---- across sessions ------------------------------------------------------------------------

/**
 * The sessions active since a time, from a listing of the newest `limit`. A listing that came back
 * full may have left out older sessions still active since: `truncated` says so.
 */
export function selectSessions(rows, since, limit) {
  return {
    listed: rows.filter((row) => (row.endedAt ?? row.startedAt) >= since),
    truncated: rows.length >= limit,
  };
}

/** The flags that come back session after session: habits, rather than accidents. */
export function patterns(analyses) {
  const counts = new Map();
  for (const analysis of analyses) {
    const seen = new Set();
    for (const f of analysis.flags) {
      const row = counts.get(f.type) ?? { type: f.type, category: f.category, severity: f.severity, sessions: 0, occurrences: 0 };
      if (!seen.has(f.type)) {
        row.sessions++;
        row.occurrences += f.ofType;
        seen.add(f.type);
      }
      counts.set(f.type, row);
    }
  }
  return [...counts.values()].sort((a, b) => SEVERITY[a.severity] - SEVERITY[b.severity] || b.sessions - a.sessions);
}

/**
 * What the prompts that asked for work (not the go-aheads) gave to work with, over all the
 * sessions: a habit shows here even where no single prompt was flagged.
 */
export function promptProfile(analyses) {
  const sum = { work: 0, criteria: 0, constraint: 0, anchored: 0 };
  for (const analysis of analyses) {
    for (const name of Object.keys(sum)) sum[name] += analysis.metrics.promptProfile[name];
  }
  return sum;
}

/** Token use of all the sessions, per model. */
export function totals(analyses) {
  const byModel = {};
  for (const analysis of analyses) {
    for (const [model, row] of Object.entries(analysis.metrics.byModel)) {
      const sum = (byModel[model] ??= { messages: 0, input: 0, cacheRead: 0, cacheWrite: 0, output: 0, thinking: 0 });
      for (const [name, value] of Object.entries(row)) sum[name] += value;
    }
  }
  return byModel;
}

// ---- the last run, and what changed since -----------------------------------------------------

/**
 * What a finding is about, as a run records it: a flag type, or what too few prompts carried. A run
 * is compared with the next by these, so a finding outside them is not kept.
 */
export const FINDINGS = {
  'missing-criteria': 'instructions',
  'missing-constraint': 'instructions',
  'missing-target': 'instructions',
  'user-correction': 'instructions',
  'vague-wording': 'instructions',
  'trouble-without-evidence': 'instructions',
  'short-prompt-big-task': 'instructions',
  'repeated-prompt': 'instructions',
  'clarification-asked': 'instructions',
  'retraction': 'reasoning',
  'interrupted': 'reasoning',
  'api-errors': 'reasoning',
  'repeated-call': 'tools',
  'retry-after-error': 'tools',
  'tool-error-rate': 'tools',
  'shell-for-builtin': 'tools',
  'large-results': 'tools',
  'tool-denials': 'tools',
  'context-compacted': 'model',
  'long-context': 'model',
  'heavy-model-for-lookup': 'model',
  'low-cache-hit': 'model',
  'high-thinking-share': 'model',
  'secret-in-prompt': 'security',
  'confidential-in-prompt': 'security',
  'injection-suspected': 'security',
};

/**
 * How often each finding came up in an analysis, against what it is counted over: what a prompt
 * lacked, as a share of the prompts that asked for work; a flag about prompts, per 10 of them;
 * any other, per session.
 */
export function rates(analysis) {
  const profile = analysis.scope.promptProfile;
  const sessions = analysis.scope.sessions;
  const work = profile.work;
  const byType = Object.fromEntries(analysis.patterns.map((p) => [p.type, p.occurrences]));
  const result = {};
  for (const [id, category] of Object.entries(FINDINGS)) {
    if (id.startsWith('missing-')) {
      const has = { 'missing-criteria': profile.criteria, 'missing-constraint': profile.constraint, 'missing-target': profile.anchored }[id];
      result[id] = { value: work ? (work - has) / work : null, over: work, unit: 'share of work prompts' };
    } else if (category === 'instructions') {
      result[id] = { value: work ? ((byType[id] ?? 0) / work) * 10 : null, over: work, unit: 'per 10 work prompts' };
    } else {
      result[id] = { value: sessions ? (byType[id] ?? 0) / sessions : null, over: sessions, unit: 'per session' };
    }
  }
  return result;
}

/**
 * Each finding of the last run against how often it comes up now: improved, unchanged, worse, or
 * too little to tell. An improvement in what prompts carry comes with a prompt that now carries it.
 */
export function compare(baseline, analysis) {
  const now = rates(analysis);
  const prompts = analysis.sessions.flatMap((s) => s.prompts.map((p) => ({ ...p, sessionId: s.sessionId, title: s.title })));
  const feature = { 'missing-criteria': 'criteria', 'missing-constraint': 'constraint', 'missing-target': 'anchored' };
  return (baseline.findings ?? []).map((finding) => {
    const before = baseline.rates?.[finding.id];
    const after = now[finding.id];
    const row = { id: finding.id, title: finding.title, before: before?.value ?? null, after: after?.value ?? null, unit: after?.unit };
    const enough = FINDINGS[finding.id] === 'instructions' || finding.id.startsWith('missing-') ? LIMITS.minWorkPrompts : LIMITS.minSessions;
    if (!after || after.value === null || after.over < enough || before?.value == null) return { ...row, status: 'too-little' };
    const change = before.value === 0 ? (after.value === 0 ? 0 : Infinity) : (after.value - before.value) / before.value;
    const status = change <= -LIMITS.changeRatio ? 'improved' : change >= LIMITS.changeRatio ? 'worse' : 'unchanged';
    if (status === 'improved' && feature[finding.id]) {
      const name = feature[finding.id];
      const good = prompts.find((p) => !p.features.approval && (name === 'anchored' ? p.features.path || p.features.url || p.features.code || p.features.pasted : p.features[name]));
      if (good) return { ...row, status, example: { sessionId: good.sessionId, title: good.title, at: good.at, text: good.text } };
    }
    return { ...row, status };
  });
}
