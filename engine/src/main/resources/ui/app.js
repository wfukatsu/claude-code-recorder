'use strict';

// Everything recorded is somebody else's text as far as this page is concerned: prompts, tool
// output, file contents. It is only ever put into the page as text, never as markup.

const TEXT = {
  ja: {
    deleteSession: 'セッションを削除',
    deleteSelected: (n) => `選択した ${n} 件を削除`,
    selected: (n) => `${n} 件を選択中`,
    clearSelection: '選択を解除',
    selectSession: 'このセッションを選択',
    confirmDelete: (n) => (n === 1 ? 'このセッションを削除しますか？' : `${n} 件のセッションを削除しますか？`),
    deleteWarning: '記録、使用量、ほかのセッションが使っていない本文を削除します。元に戻せません。',
    deleteNote: '削除したセッションは、フックからは以後記録されません。Claude Code のトランスクリプト（~/.claude/projects 配下）は消えません。',
    andMore: (n) => `ほか ${n} 件`,
    cancel: 'キャンセル',
    doDelete: '削除する',
    deleting: '削除中…',
    deletedNotice: (n) => `${n} 件のセッションを削除しました。`,
    deleteFailed: '削除できませんでした',
    busy: 'ほかの ccrec の処理が書き込み中です。少し待ってからやり直してください。',
    network: '外部通信',
    networkNote:
      'この端末と Claude 以外に届いたツール呼び出しです。送信した内容は呼び出しの入力、返ってきた内容はその結果です。通信そのものを記録したものではなく、ツール名、宛先のアドレス、シェルコマンドで動かしたプログラムから判定しています。',
    noNetwork: 'このセッションには、外部に届いたツール呼び出しがありません。',
    networkCalls: '呼び出し',
    networkTag: 'この端末と Claude 以外への通信',
    destination: '送信先',
    via: '経路',
    via_web: 'Web',
    via_shell: 'シェル',
    via_mcp: 'MCP',
    sent: '送信',
    received: '受信',
    lastAt: '最後',
    order: '並び順',
    newestFirst: '新しい順',
    oldestFirst: '古い順',
    labelCommand: 'コマンド',
    labelNotice: '通知',
    labelOutput: 'コマンド出力',
    labelInserted: '挿入',
    labelSummary: '圧縮後の要約',
    labelReminder: 'システムリマインダー',
    showAll: 'すべて表示',
    showLess: '折りたたむ',
    asRecorded: '原文',
    formatted: '整形',
    sessions: 'セッション',
    usageNav: '使用量',
    statusNav: '状態',
    metric: '指標',
    metricOutput: '出力トークン',
    metricInput: '入力トークン（キャッシュ含む）',
    metricMessages: 'API 応答数',
    byDay: '日ごと（モデル別）',
    byModel: 'モデル別',
    byProject: 'プロジェクト別',
    byStartDay: 'セッションの使用量は、そのセッションを開始した日にまとめて計上しています。',
    noUsage: 'この期間の使用量はありません。',
    noProject: '(プロジェクトなし)',
    refresh: '更新',
    checks: '確認',
    checkHooks: 'フックが入っている',
    checkQueue: 'キューのセッションが記録されている',
    checkReadable: 'キューが読める',
    noneWaiting: '未記録なし',
    waitingSince: (n, since) => `${n} 件が未記録（${since} から）`,
    unreadable: (n) => `読めないキューが ${n} 件`,
    unknown: '不明（ccrec ui から起動していない）',
    environment: '環境',
    version: 'ccrec',
    javaVersion: 'Java',
    homeDir: 'データの場所',
    databaseConfig: 'データベース設定',
    storage: '保存先',
    accountsRecorded: '記録のあるアカウント',
    settings: '設定 (config.json)',
    queue: 'フックが保持しているセッション',
    noQueue: 'フックが保持しているセッションはありません。',
    lastEvent: '最後のイベント',
    updated: '更新',
    requested: '取り込みの要求',
    recorded: '最後の取り込み',
    state: '状態',
    stateWaiting: '未記録',
    stateRecorded: '記録済み',
    stateIdle: '要求なし',
    ingestLog: '取り込みログ（末尾）',
    noLog: 'まだ取り込みは起動していません。',
    account: 'アカウント',
    language: 'English',
    period: '期間',
    days: (n) => `直近 ${n} 日`,
    allTime: 'すべて',
    project: 'プロジェクト',
    allProjects: 'すべて',
    searchTitle: 'タイトルで絞り込み',
    lastActive: '最終更新',
    titleProject: 'タイトル / プロジェクト',
    model: 'モデル',
    responses: 'API 応答',
    output: '出力',
    input: '入力',
    cacheRead: 'キャッシュ読み出し',
    cacheWrite: 'キャッシュ書き込み',
    thinking: 'thinking',
    web: 'Web',
    cost: '金額',
    tokens: 'トークン',
    noSessions: 'このアカウントで記録されたセッションはありません。',
    noMatch: '条件に合うセッションはありません。',
    noAccounts: 'まだ何も記録されていません。フックを入れて Claude Code を使うか、ccrec import で取り込んでください。',
    untitled: '(タイトルなし)',
    back: '← 一覧',
    turns: 'ターン',
    toolCalls: 'ツール呼び出し',
    errors: 'エラー',
    errorCount: (n) => `${n} エラー`,
    compactions: '圧縮',
    pullRequests: 'PR',
    prompts: 'プロンプト',
    subAgents: 'サブエージェント',
    conversation: 'やりとり',
    usage: '使用量',
    tools: 'ツール',
    details: '付帯情報',
    show: '表示',
    groupPrompts: 'プロンプト',
    groupResponses: '応答',
    groupTools: 'ツール',
    groupThinking: 'thinking',
    groupContext: 'コンテキスト',
    groupSystem: 'system など',
    agent: 'エージェント',
    allAgents: 'すべて',
    mainAgent: 'メイン',
    loadMore: (shown, total) => `続きを読み込む（${shown} / ${total}）`,
    noRecords: '表示するレコードがありません。',
    loading: '読み込み中…',
    inputOf: '入力',
    resultOf: '結果',
    noResult: '結果はまだ読み込まれていないか、記録されていません。',
    failed: '失敗',
    tool: 'ツール',
    calls: '呼び出し',
    total: '合計',
    messages: 'API 応答',
    notRecorded: 'このセッションは記録されていません。',
    loadFailed: '読み込めませんでした',
    kinds: {
      user_prompt: 'プロンプト',
      user_meta: 'メタ',
      assistant_text: '応答',
      thinking: 'thinking',
      tool_use: 'ツール',
      tool_result: 'ツール結果',
      system_prompt: 'システムプロンプト',
      tool_definitions: 'ツール定義',
      context: 'コンテキスト',
      system: 'system',
      cost: '金額',
      pr_link: 'PR',
      mcp_meta: 'MCP',
      unknown: '不明',
    },
    facts: {
      accountId: 'アカウント',
      projectPath: 'プロジェクト',
      gitBranch: 'ブランチ',
      entrypoint: '起動元',
      ccVersion: 'Claude Code',
      startedAt: '開始',
      lastActivityAt: '最終更新',
      costUsd: '金額（Claude Code の記録）',
      apiDurationMs: 'API の所要時間',
      toolDurationMs: 'ツールの所要時間',
      linesAdded: '追加行',
      linesRemoved: '削除行',
      records: 'レコード',
      prompts: 'プロンプト',
      promptSources: 'プロンプトの出どころ',
      subAgents: 'サブエージェント',
      turns: 'ターン',
      turnDurationMs: 'ターンの合計時間',
      longestTurnMs: '最長のターン',
      thinkingDurationMs: 'thinking の時間',
      compactions: '圧縮',
      interrupted: '中断',
      hookErrors: 'フックのエラー',
      filesEdited: '編集したファイル',
      stopReasons: '終了理由',
      apiErrors: 'API エラー',
      toolDenials: 'ツールの拒否',
      permissionModes: '権限モード',
      skills: 'スキル',
      plugins: 'プラグイン',
      mcpServers: 'MCP サーバー',
      commands: 'コマンド',
      pullRequests: 'PR',
    },
  },
  en: {
    deleteSession: 'Delete session',
    deleteSelected: (n) => `Delete the ${n} selected`,
    selected: (n) => `${n} selected`,
    clearSelection: 'Clear selection',
    selectSession: 'Select this session',
    confirmDelete: (n) => (n === 1 ? 'Delete this session?' : `Delete ${n} sessions?`),
    deleteWarning: 'Its records, its usage and every content no other session uses are deleted. This cannot be undone.',
    deleteNote: 'The hooks will not record a deleted session again. Claude Code\'s own transcripts (under ~/.claude/projects) are left alone.',
    andMore: (n) => `and ${n} more`,
    cancel: 'Cancel',
    doDelete: 'Delete',
    deleting: 'Deleting…',
    deletedNotice: (n) => (n === 1 ? '1 session was deleted.' : `${n} sessions were deleted.`),
    deleteFailed: 'Could not delete',
    busy: 'Another ccrec process is writing. Wait a moment and try again.',
    network: 'Network',
    networkNote:
      'Tool calls that reached beyond this machine and Claude. What was sent is the call\'s input; what came back is its result. This is not captured traffic: it is told from the tool, the addresses it names and the programs a shell command runs.',
    noNetwork: 'No tool call of this session reached beyond this machine.',
    networkCalls: 'Calls',
    networkTag: 'Reaches beyond this machine and Claude',
    destination: 'Destination',
    via: 'Via',
    via_web: 'Web',
    via_shell: 'Shell',
    via_mcp: 'MCP',
    sent: 'Sent',
    received: 'Received',
    lastAt: 'Last',
    order: 'Order',
    newestFirst: 'Newest first',
    oldestFirst: 'Oldest first',
    labelCommand: 'Command',
    labelNotice: 'Notice',
    labelOutput: 'Command output',
    labelInserted: 'Inserted',
    labelSummary: 'Summary after compaction',
    labelReminder: 'System reminder',
    showAll: 'Show all',
    showLess: 'Show less',
    asRecorded: 'As recorded',
    formatted: 'Formatted',
    sessions: 'Sessions',
    usageNav: 'Usage',
    statusNav: 'Status',
    metric: 'Metric',
    metricOutput: 'Output tokens',
    metricInput: 'Input tokens (with cache)',
    metricMessages: 'API messages',
    byDay: 'Per day, by model',
    byModel: 'By model',
    byProject: 'By project',
    byStartDay: 'A session\'s usage is counted on the day the session started.',
    noUsage: 'No usage in this period.',
    noProject: '(no project)',
    refresh: 'Refresh',
    checks: 'Checks',
    checkHooks: 'Hooks installed',
    checkQueue: 'Queued sessions recorded',
    checkReadable: 'Queue entries readable',
    noneWaiting: 'none waiting',
    waitingSince: (n, since) => `${n} waiting since ${since}`,
    unreadable: (n) => `${n} unreadable`,
    unknown: 'unknown (not started by ccrec ui)',
    environment: 'Environment',
    version: 'ccrec',
    javaVersion: 'Java',
    homeDir: 'Data directory',
    databaseConfig: 'Database configuration',
    storage: 'Storage',
    accountsRecorded: 'Accounts with recordings',
    settings: 'Settings (config.json)',
    queue: 'Sessions the hooks hold',
    noQueue: 'The hooks hold no session.',
    lastEvent: 'Last event',
    updated: 'Updated',
    requested: 'Ingest asked',
    recorded: 'Last ingest',
    state: 'State',
    stateWaiting: 'waiting',
    stateRecorded: 'recorded',
    stateIdle: 'not asked',
    ingestLog: 'Ingest log (tail)',
    noLog: 'No ingest has been started yet.',
    account: 'Account',
    language: '日本語',
    period: 'Period',
    days: (n) => `Last ${n} days`,
    allTime: 'All time',
    project: 'Project',
    allProjects: 'All',
    searchTitle: 'Filter by title',
    lastActive: 'Last active',
    titleProject: 'Title / project',
    model: 'Model',
    responses: 'API messages',
    output: 'Output',
    input: 'Input',
    cacheRead: 'Cache read',
    cacheWrite: 'Cache write',
    thinking: 'Thinking',
    web: 'Web',
    cost: 'Cost',
    tokens: 'tokens',
    noSessions: 'No sessions are recorded under this account.',
    noMatch: 'No session matches the filters.',
    noAccounts: 'Nothing is recorded yet. Install the hooks and use Claude Code, or run ccrec import.',
    untitled: '(untitled)',
    back: '← Sessions',
    turns: 'Turns',
    toolCalls: 'Tool calls',
    errors: 'Errors',
    errorCount: (n) => (n === 1 ? '1 error' : `${n} errors`),
    compactions: 'Compactions',
    pullRequests: 'PRs',
    prompts: 'Prompts',
    subAgents: 'Sub-agents',
    conversation: 'Conversation',
    usage: 'Usage',
    tools: 'Tools',
    details: 'Details',
    show: 'Show',
    groupPrompts: 'Prompts',
    groupResponses: 'Responses',
    groupTools: 'Tools',
    groupThinking: 'Thinking',
    groupContext: 'Context',
    groupSystem: 'System and others',
    agent: 'Agent',
    allAgents: 'All',
    mainAgent: 'main',
    loadMore: (shown, total) => `Load more (${shown} of ${total})`,
    noRecords: 'No records to show.',
    loading: 'Loading…',
    inputOf: 'Input',
    resultOf: 'Result',
    noResult: 'The result is not loaded yet, or was not recorded.',
    failed: 'failed',
    tool: 'Tool',
    calls: 'Calls',
    total: 'Total',
    messages: 'API messages',
    notRecorded: 'This session is not recorded.',
    loadFailed: 'Could not load',
    kinds: {
      user_prompt: 'Prompt',
      user_meta: 'Meta',
      assistant_text: 'Response',
      thinking: 'Thinking',
      tool_use: 'Tool',
      tool_result: 'Tool result',
      system_prompt: 'System prompt',
      tool_definitions: 'Tool definitions',
      context: 'Context',
      system: 'System',
      cost: 'Cost',
      pr_link: 'PR',
      mcp_meta: 'MCP',
      unknown: 'Unknown',
    },
    facts: {
      accountId: 'Account',
      projectPath: 'Project',
      gitBranch: 'Branch',
      entrypoint: 'Entrypoint',
      ccVersion: 'Claude Code',
      startedAt: 'Started',
      lastActivityAt: 'Last active',
      costUsd: 'Cost (as Claude Code wrote it down)',
      apiDurationMs: 'API time',
      toolDurationMs: 'Tool time',
      linesAdded: 'Lines added',
      linesRemoved: 'Lines removed',
      records: 'Records',
      prompts: 'Prompts',
      promptSources: 'Prompt sources',
      subAgents: 'Sub-agents',
      turns: 'Turns',
      turnDurationMs: 'Time in turns',
      longestTurnMs: 'Longest turn',
      thinkingDurationMs: 'Thinking time',
      compactions: 'Compactions',
      interrupted: 'Interrupted',
      hookErrors: 'Hook errors',
      filesEdited: 'Files edited',
      stopReasons: 'Stop reasons',
      apiErrors: 'API errors',
      toolDenials: 'Tool denials',
      permissionModes: 'Permission modes',
      skills: 'Skills',
      plugins: 'Plugins',
      mcpServers: 'MCP servers',
      commands: 'Commands',
      pullRequests: 'PRs',
    },
  },
};

// The kinds each checkbox of the conversation view stands for; a kind in none of them is "system".
const GROUPS = {
  groupPrompts: ['user_prompt'],
  groupResponses: ['assistant_text'],
  groupTools: ['tool_use', 'tool_result', 'mcp_meta'],
  groupThinking: ['thinking'],
  groupContext: ['context', 'user_meta', 'system_prompt', 'tool_definitions'],
  groupSystem: ['system', 'cost', 'pr_link', 'unknown'],
};
const SHOWN_AT_FIRST = ['groupPrompts', 'groupResponses', 'groupTools'];

// ---- what the browser remembers ---------------------------------------------------------------

function remembered(key, otherwise) {
  try {
    return localStorage.getItem(`ccrec.${key}`) ?? otherwise;
  } catch {
    return otherwise;
  }
}

function remember(key, value) {
  try {
    localStorage.setItem(`ccrec.${key}`, value);
  } catch {
    // A private window: the choice lasts as long as the page.
  }
}

const state = {
  lang: remembered('lang', (navigator.language || 'en').startsWith('ja') ? 'ja' : 'en'),
  account: remembered('account', null),
  accounts: [],
  drawn: 0,
};

const t = (key, ...args) => {
  const value = TEXT[state.lang][key];
  return typeof value === 'function' ? value(...args) : value;
};

// ---- small helpers -----------------------------------------------------------------------------

function el(tag, attributes, ...children) {
  const node = document.createElement(tag);
  for (const [name, value] of Object.entries(attributes || {})) {
    if (value === null || value === undefined || value === false) continue;
    if (name === 'class') node.className = value;
    else if (name.startsWith('on')) node.addEventListener(name.slice(2), value);
    else if (name in node && name !== 'list') node[name] = value;
    else node.setAttribute(name, value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

async function api(path) {
  const response = await fetch(`/api/${path}`, { credentials: 'same-origin' });
  if (!response.ok) {
    const error = new Error(`${response.status}`);
    error.status = response.status;
    throw error;
  }
  return response.json();
}

/** The one request that changes anything: marked as this page's own, which no form elsewhere can do. */
async function deleteSessions(sessionIds) {
  const response = await fetch('/api/sessions/delete', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-Ccrec-Ui': '1' },
    body: JSON.stringify({ sessionIds }),
  });
  if (!response.ok) {
    const error = new Error(`${response.status}`);
    error.status = response.status;
    throw error;
  }
  return response.json();
}

/**
 * Asks before deleting, in the page rather than in a browser prompt, with the way out in focus.
 * `sessions` are `{ sessionId, title }`; `done` runs once they are gone.
 */
function confirmDeletion(sessions, done) {
  const failure = el('p', { class: 'error', hidden: true });
  // Closed and taken off the page at once: a dialog left behind would be found again by the next one.
  const dismiss = () => {
    if (dialog.open) dialog.close();
    dialog.remove();
  };
  const cancel = el('button', { type: 'button', onclick: dismiss }, t('cancel'));
  const confirm = el('button', { type: 'button', class: 'danger' }, t('doDelete'));
  const listed = sessions.slice(0, 5);
  const dialog = el(
    'dialog',
    { class: 'confirm' },
    el('h2', {}, t('confirmDelete', sessions.length)),
    el(
      'ul',
      {},
      listed.map((s) => el('li', {}, s.title || t('untitled'))),
      sessions.length > listed.length ? el('li', { class: 'more-items' }, t('andMore', sessions.length - listed.length)) : null,
    ),
    el('p', {}, t('deleteWarning')),
    el('p', { class: 'note' }, t('deleteNote')),
    failure,
    el('div', { class: 'actions' }, cancel, confirm),
  );
  confirm.onclick = async () => {
    confirm.disabled = cancel.disabled = true;
    confirm.textContent = t('deleting');
    try {
      const answer = await deleteSessions(sessions.map((s) => s.sessionId));
      dismiss();
      done(answer.deleted.filter((d) => d.found).length);
    } catch (error) {
      failure.hidden = false;
      failure.textContent = error.status === 409 ? t('busy') : `${t('deleteFailed')}: ${error.message}`;
      confirm.disabled = cancel.disabled = false;
      confirm.textContent = t('doDelete');
    }
  };
  // Escape closes it without going through a button.
  dialog.addEventListener('cancel', dismiss);
  dialog.addEventListener('close', dismiss);
  // One question at a time, whatever became of the last one.
  for (const earlier of document.querySelectorAll('dialog.confirm')) earlier.remove();
  document.body.append(dialog);
  dialog.showModal();
  cancel.focus();
}

const compact = (n) =>
  n === null || n === undefined ? '–' : new Intl.NumberFormat(state.lang, { notation: 'compact', maximumFractionDigits: 1 }).format(n);
const whole = (n) => (n === null || n === undefined ? '–' : new Intl.NumberFormat(state.lang).format(n));
const money = (n) =>
  n === null || n === undefined ? '–' : new Intl.NumberFormat(state.lang, { style: 'currency', currency: 'USD' }).format(n);

function when(millis, withSeconds) {
  if (millis === null || millis === undefined) return '–';
  return new Intl.DateTimeFormat(state.lang, {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: withSeconds ? '2-digit' : undefined,
  }).format(new Date(millis));
}

function duration(millis) {
  const seconds = Math.round(millis / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m${String(seconds % 60).padStart(2, '0')}s`;
  return `${Math.floor(minutes / 60)}h${String(minutes % 60).padStart(2, '0')}m`;
}

function bytes(n) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

const baseName = (path) => (path ? path.replace(/[/\\]+$/, '').split(/[/\\]/).pop() : '');
const shortModel = (model) => model.replace(/^claude-/, '');

// ---- the frame -----------------------------------------------------------------------------------

function drawFrame() {
  document.documentElement.lang = state.lang;
  const nav = document.getElementById('nav');
  const here = location.hash.replace(/^#\/?/, '').split('/')[0] || 'sessions';
  nav.replaceChildren(
    ...[
      ['sessions', 'sessions'],
      ['usage', 'usageNav'],
      ['status', 'statusNav'],
    ].map(([route, label]) => el('a', { href: `#/${route}`, class: here === route ? 'current' : '' }, t(label))),
  );
  document.getElementById('account-label').textContent = t('account');
  const language = document.getElementById('language');
  language.textContent = t('language');
  language.onclick = () => {
    state.lang = state.lang === 'ja' ? 'en' : 'ja';
    remember('lang', state.lang);
    draw();
  };
  const select = document.getElementById('account');
  select.replaceChildren(
    ...state.accounts.map((account) =>
      el(
        'option',
        { value: account.accountId, selected: account.accountId === state.account },
        account.email || account.displayName || account.accountId,
      ),
    ),
  );
  select.onchange = () => {
    state.account = select.value;
    remember('account', state.account);
    // A session belongs to one account: its page makes no sense under another. Other pages stay.
    if (location.hash.startsWith('#/sessions/')) location.hash = '#/sessions';
    draw();
  };
}

async function draw() {
  const turn = ++state.drawn;
  const view = document.getElementById('view');
  drawFrame();
  const route = location.hash.replace(/^#\/?/, '').split('/').map(decodeURIComponent);
  try {
    if (route[0] === 'sessions' && route[1]) await drawSession(view, route[1], turn);
    else if (route[0] === 'usage') await drawUsage(view, turn);
    else if (route[0] === 'status') await drawStatus(view, turn);
    else await drawSessions(view, turn);
  } catch (error) {
    if (turn !== state.drawn) return;
    view.replaceChildren(
      el('p', { class: 'error' }, error.status === 401 ? 'ccrec ui: 401' : `${t('loadFailed')}: ${error.message}`),
    );
  }
}

// ---- the list of sessions --------------------------------------------------------------------

const listFilters = { days: remembered('days', '30'), project: '', title: '' };
// What the list says once, after a deletion took the reader back to it.
let notice = null;

async function drawSessions(view, turn) {
  if (state.accounts.length === 0) {
    view.replaceChildren(el('p', { class: 'empty' }, t('noAccounts')));
    return;
  }
  view.replaceChildren(el('p', { class: 'empty' }, t('loading')));
  const sessions = await api(`sessions?account=${encodeURIComponent(state.account)}&limit=1000`);
  if (turn !== state.drawn) return;

  const projects = [...new Set(sessions.map((s) => s.projectPath).filter(Boolean))].sort();
  if (!projects.includes(listFilters.project)) listFilters.project = '';
  const table = el('div');
  const stats = el('div', { class: 'stats' });
  const selection = new Set();
  const bar = el('div', { class: 'selection', hidden: true });
  const showSelection = () => {
    bar.hidden = selection.size === 0;
    bar.replaceChildren(
      el('span', {}, t('selected', selection.size)),
      el(
        'button',
        {
          type: 'button',
          class: 'danger',
          onclick: () =>
            confirmDeletion(
              sessions.filter((s) => selection.has(s.sessionId)),
              (count) => {
                notice = t('deletedNotice', count);
                draw();
              },
            ),
        },
        t('deleteSelected', selection.size),
      ),
      el(
        'button',
        {
          type: 'button',
          class: 'quiet',
          onclick: () => {
            selection.clear();
            fill();
          },
        },
        t('clearSelection'),
      ),
    );
  };

  const fill = () => {
    const since = listFilters.days === 'all' ? 0 : Date.now() - Number(listFilters.days) * 86400000;
    const needle = listFilters.title.trim().toLowerCase();
    // By when each was last active, which is the time the list shows: a session started long ago
    // and still in use belongs at the top.
    const shown = sessions
      .filter(
        (s) =>
          (s.endedAt ?? s.startedAt) >= since &&
          (!listFilters.project || s.projectPath === listFilters.project) &&
          (!needle || `${s.title ?? ''} ${s.projectPath ?? ''}`.toLowerCase().includes(needle)),
      )
      .sort((a, b) => (b.endedAt ?? b.startedAt) - (a.endedAt ?? a.startedAt));
    // What a filter hides is not deleted along with what it shows.
    for (const id of [...selection]) if (!shown.some((s) => s.sessionId === id)) selection.delete(id);
    showSelection();
    const sum = (pick) => shown.reduce((total, s) => total + (pick(s) ?? 0), 0);
    const costs = shown.filter((s) => s.costUsd !== null);
    stats.replaceChildren(
      stat(whole(shown.length), t('sessions')),
      stat(compact(sum((s) => s.usage.messages)), t('responses')),
      stat(compact(sum((s) => s.usage.outputTokens)), `${t('output')} ${t('tokens')}`),
      stat(compact(sum((s) => s.usage.inputTokens + s.usage.cacheReadTokens + s.usage.cacheCreationTokens)), `${t('input')} ${t('tokens')}`),
      stat(costs.length ? money(costs.reduce((total, s) => total + s.costUsd, 0)) : '–', t('cost')),
    );
    if (shown.length === 0) {
      table.replaceChildren(el('p', { class: 'empty' }, sessions.length === 0 ? t('noSessions') : t('noMatch')));
      return;
    }
    table.replaceChildren(
      el(
        'div',
        { class: 'scroll' },
        el(
          'table',
          {},
          el(
            'thead',
            {},
            el(
              'tr',
              {},
              el('th', {}),
              el('th', {}, t('lastActive')),
              el('th', {}, t('titleProject')),
              el('th', {}, t('model')),
              el('th', { class: 'num' }, t('responses')),
              el('th', { class: 'num' }, t('output')),
              el('th', { class: 'num' }, t('cost')),
            ),
          ),
          el(
            'tbody',
            {},
            shown.map((s) =>
              el(
                'tr',
                { class: 'link' },
                el(
                  'td',
                  { class: 'pick' },
                  el('input', {
                    type: 'checkbox',
                    'aria-label': t('selectSession'),
                    checked: selection.has(s.sessionId),
                    onchange: (event) => {
                      if (event.target.checked) selection.add(s.sessionId);
                      else selection.delete(s.sessionId);
                      showSelection();
                    },
                  }),
                ),
                el('td', { class: 'when' }, when(s.endedAt ?? s.startedAt)),
                el(
                  'td',
                  {},
                  el('a', { class: 'title', href: `#/sessions/${encodeURIComponent(s.sessionId)}` }, s.title || t('untitled')),
                  el('div', { class: 'project' }, [baseName(s.projectPath), s.gitBranch].filter(Boolean).join(' · ')),
                ),
                el('td', {}, (s.models.length ? s.models : s.model ? [s.model] : []).map((m) => el('span', { class: 'tag' }, shortModel(m)))),
                el('td', { class: 'num' }, whole(s.usage.messages)),
                el('td', { class: 'num' }, compact(s.usage.outputTokens)),
                el('td', { class: 'num' }, money(s.costUsd)),
              ),
            ),
          ),
        ),
      ),
    );
  };

  const period = el(
    'select',
    {
      onchange: (event) => {
        listFilters.days = event.target.value;
        remember('days', listFilters.days);
        fill();
      },
    },
    ['7', '30', '90'].map((n) => el('option', { value: n, selected: listFilters.days === n }, t('days', n))),
    el('option', { value: 'all', selected: listFilters.days === 'all' }, t('allTime')),
  );
  const project = el(
    'select',
    {
      onchange: (event) => {
        listFilters.project = event.target.value;
        fill();
      },
    },
    el('option', { value: '' }, t('allProjects')),
    projects.map((p) => el('option', { value: p, selected: listFilters.project === p, title: p }, baseName(p))),
  );
  const title = el('input', {
    type: 'search',
    placeholder: t('searchTitle'),
    value: listFilters.title,
    oninput: (event) => {
      listFilters.title = event.target.value;
      fill();
    },
  });
  // replaceChildren would write a missing notice out as the word "null".
  view.replaceChildren(
    ...[
      el(
        'div',
        { class: 'filters' },
        el('label', { class: 'control' }, `${t('period')} `, period),
        el('label', { class: 'control' }, `${t('project')} `, project),
        title,
      ),
      notice ? el('p', { class: 'notice', role: 'status' }, notice) : null,
      stats,
      bar,
      table,
    ].filter(Boolean),
  );
  notice = null;
  fill();
}

function stat(value, label) {
  return el('div', { class: 'stat' }, el('b', {}, value), el('span', {}, label));
}

// ---- one session -------------------------------------------------------------------------------

const sessionView = {
  sessionId: null,
  tab: 'conversation',
  groups: new Set(SHOWN_AT_FIRST),
  agent: '',
  order: remembered('order', 'desc'),
};

async function drawSession(view, sessionId, turn) {
  view.replaceChildren(el('p', { class: 'empty' }, t('loading')));
  let summary;
  try {
    summary = await api(`sessions/${encodeURIComponent(sessionId)}`);
  } catch (error) {
    if (error.status !== 404) throw error;
    if (turn !== state.drawn) return;
    view.replaceChildren(el('a', { class: 'back', href: '#/sessions' }, t('back')), el('p', { class: 'empty' }, t('notRecorded')));
    return;
  }
  if (turn !== state.drawn) return;
  // Another session opens on its conversation; the same one redrawn (a change of language) stays put.
  if (sessionView.sessionId !== sessionId) {
    sessionView.sessionId = sessionId;
    sessionView.tab = 'conversation';
    sessionView.agent = '';
  }

  const calls = summary.tools.reduce((total, tool) => total + tool.calls, 0);
  const failures = summary.tools.reduce((total, tool) => total + tool.errors, 0);
  const output = summary.usage.reduce((total, u) => total + u.outputTokens, 0);
  const panel = el('div');
  const tabs = el('div', { class: 'tabs' });
  const showTab = () => {
    tabs.replaceChildren(
      ...['conversation', 'network', 'usage', 'tools', 'details'].map((name) =>
        el(
          'button',
          {
            type: 'button',
            class: sessionView.tab === name ? 'current' : '',
            onclick: () => {
              sessionView.tab = name;
              showTab();
            },
          },
          t(name),
        ),
      ),
    );
    if (sessionView.tab === 'usage') panel.replaceChildren(usageTable(summary.usage));
    else if (sessionView.tab === 'tools') panel.replaceChildren(toolsTable(summary.tools));
    else if (sessionView.tab === 'details') panel.replaceChildren(facts(summary));
    else if (sessionView.tab === 'network') {
      network(panel, sessionId, () => turn === state.drawn && sessionView.tab === 'network').catch((error) =>
        panel.replaceChildren(el('p', { class: 'error' }, `${t('loadFailed')}: ${error.message}`)),
      );
    }
    else conversation(panel, sessionId, summary);
  };

  view.replaceChildren(
    el(
      'div',
      { class: 'headline' },
      el('a', { class: 'back', href: '#/sessions' }, t('back')),
      el(
        'button',
        {
          type: 'button',
          class: 'danger quiet',
          onclick: () =>
            confirmDeletion([{ sessionId, title: summary.title }], () => {
              notice = t('deletedNotice', 1);
              location.hash = '#/sessions';
            }),
        },
        t('deleteSession'),
      ),
    ),
    el('h1', {}, summary.title || t('untitled')),
    el(
      'div',
      { class: 'sub' },
      [
        baseName(summary.projectPath),
        summary.gitBranch,
        summary.startedAt && `${when(Date.parse(summary.startedAt))} – ${when(Date.parse(summary.lastActivityAt ?? summary.startedAt))}`,
        summary.entrypoint,
        summary.ccVersion,
      ]
        .filter(Boolean)
        .join(' · '),
    ),
    el(
      'div',
      { class: 'stats' },
      stat(whole(summary.prompts), t('prompts')),
      stat(`${whole(summary.turns)}${summary.turns ? ` · ${duration(summary.turnDurationMs)}` : ''}`, t('turns')),
      stat(compact(output), `${t('output')} ${t('tokens')}`),
      stat(`${whole(calls)}${failures ? ` · ${t('errorCount', failures)}` : ''}`, t('toolCalls')),
      summary.subAgents ? stat(whole(summary.subAgents), t('subAgents')) : null,
      summary.compactions ? stat(whole(summary.compactions), t('compactions')) : null,
      summary.pullRequests.length ? stat(whole(summary.pullRequests.length), t('pullRequests')) : null,
      summary.costUsd !== undefined ? stat(money(summary.costUsd), t('cost')) : null,
    ),
    tabs,
    panel,
  );
  showTab();
}

function usageTable(usage) {
  if (usage.length === 0) return el('p', { class: 'empty' }, t('noRecords'));
  const columns = [
    ['messages', 'messages'],
    ['input', 'inputTokens'],
    ['output', 'outputTokens'],
    ['thinking', 'thinkingTokens'],
    ['cacheRead', 'cacheReadTokens'],
    ['cacheWrite', 'cacheCreationTokens'],
  ];
  const row = (label, of, strong) =>
    el(
      'tr',
      {},
      el(strong ? 'th' : 'td', {}, label),
      columns.map(([, field]) => el('td', { class: 'num' }, whole(of(field)))),
      el('td', { class: 'num' }, whole(of('webSearchRequests') + of('webFetchRequests'))),
    );
  return el(
    'div',
    { class: 'scroll' },
    el(
      'table',
      {},
      el('thead', {}, el('tr', {}, el('th', {}, t('model')), columns.map(([label]) => el('th', { class: 'num' }, t(label))), el('th', { class: 'num' }, t('web')))),
      el(
        'tbody',
        {},
        usage.map((u) => row(u.model, (field) => u[field])),
        usage.length > 1 ? row(t('total'), (field) => usage.reduce((total, u) => total + u[field], 0), true) : null,
      ),
    ),
  );
}

function toolsTable(tools) {
  if (tools.length === 0) return el('p', { class: 'empty' }, t('noRecords'));
  return el(
    'div',
    { class: 'scroll' },
    el(
      'table',
      {},
      el('thead', {}, el('tr', {}, el('th', {}, t('tool')), el('th', { class: 'num' }, t('calls')), el('th', { class: 'num' }, t('errors')))),
      el(
        'tbody',
        {},
        [...tools]
          .sort((a, b) => b.calls - a.calls)
          .map((tool) =>
            el(
              'tr',
              {},
              el('td', {}, tool.tool),
              el('td', { class: 'num' }, whole(tool.calls)),
              el('td', { class: tool.errors ? 'num failed' : 'num' }, whole(tool.errors)),
            ),
          ),
      ),
    ),
  );
}

function facts(summary) {
  const labels = TEXT[state.lang].facts;
  const rows = [];
  for (const [name, label] of Object.entries(labels)) {
    const value = summary[name];
    if (value === undefined || value === null) continue;
    let shown;
    if (Array.isArray(value)) shown = value.join(', ');
    else if (typeof value === 'object') shown = Object.entries(value).map(([key, count]) => `${key} ${count}`).join(', ');
    else if (name.endsWith('Ms')) shown = duration(value);
    else if (name.endsWith('At')) shown = when(Date.parse(value), true);
    else if (name === 'costUsd') shown = money(value);
    else shown = typeof value === 'number' ? whole(value) : value;
    if (shown === '') continue;
    rows.push(el('dt', {}, label), el('dd', {}, shown));
  }
  return el('dl', { class: 'facts' }, rows);
}

// ---- the conversation ------------------------------------------------------------------------

/**
 * A list of a session's records that loads a page at a time. `queryOf` gives the query for the
 * records wanted, without the offset, or null when there is nothing to ask for. A tool call and
 * what came back for it are one row, in whichever order they arrive.
 */
function recordFeed(sessionId, queryOf) {
  const list = el('div');
  const more = el('button', { type: 'button', class: 'more' });
  let loaded = 0;
  let run = 0;
  // Tool calls already on the page, by id, so that a result can be put where its call is.
  let callsShown = new Map();
  // Newest first, a result comes before its call: it waits here for it.
  let resultsWaiting = new Map();
  const showFailure = (error) => list.append(el('p', { class: 'error' }, `${t('loadFailed')}: ${error.message}`));

  const load = async (fresh) => {
    const mine = fresh ? ++run : run;
    if (fresh) {
      loaded = 0;
      callsShown = new Map();
      resultsWaiting = new Map();
      list.replaceChildren(el('p', { class: 'empty' }, t('loading')));
    }
    more.hidden = true;
    const wanted = queryOf();
    if (wanted === null) {
      list.replaceChildren(el('p', { class: 'empty' }, t('noRecords')));
      return;
    }
    const newestFirst = wanted.order === 'desc';
    const query = new URLSearchParams({ ...wanted, offset: String(loaded) });
    const page = await api(`sessions/${encodeURIComponent(sessionId)}/records?${query}`);
    if (mine !== run) return;
    if (fresh) list.replaceChildren();
    for (const record of page.records) {
      const ofCall = (record.kind === 'tool_result' || record.kind === 'mcp_meta') && record.toolUseId;
      const call = ofCall ? callsShown.get(record.toolUseId) : null;
      if (call) {
        call.attach(record);
        continue;
      }
      if (ofCall && newestFirst) {
        resultsWaiting.set(record.toolUseId, [record, ...(resultsWaiting.get(record.toolUseId) ?? [])]);
        continue;
      }
      const row = recordRow(record);
      if (record.kind === 'tool_use' && record.toolUseId) {
        callsShown.set(record.toolUseId, row);
        for (const result of resultsWaiting.get(record.toolUseId) ?? []) row.attach(result);
        resultsWaiting.delete(record.toolUseId);
      }
      list.append(row.node);
    }
    loaded += page.records.length;
    if (loaded >= page.total) {
      // Everything is here: a result whose call is not among what is shown stands by itself.
      for (const results of resultsWaiting.values()) for (const result of results) list.append(recordRow(result).node);
      resultsWaiting = new Map();
    }
    if (loaded === 0) list.replaceChildren(el('p', { class: 'empty' }, t('noRecords')));
    more.hidden = loaded >= page.total;
    more.textContent = t('loadMore', loaded, page.total);
  };
  more.onclick = () => load(false).catch(showFailure);
  return { list, more, reload: () => load(true).catch(showFailure) };
}

/** The selector of the order records are read in, shared by the views that list them. */
function orderSelector(reload) {
  return el(
    'label',
    {},
    `${t('order')} `,
    el(
      'select',
      {
        onchange: (event) => {
          sessionView.order = event.target.value;
          remember('order', sessionView.order);
          reload();
        },
      },
      el('option', { value: 'desc', selected: sessionView.order === 'desc' }, t('newestFirst')),
      el('option', { value: 'asc', selected: sessionView.order === 'asc' }, t('oldestFirst')),
    ),
  );
}

function conversation(panel, sessionId, summary) {
  const known = new Set(Object.values(GROUPS).flat());
  const kindsOf = (group) =>
    group === 'groupSystem' ? [...GROUPS.groupSystem, ...Object.keys(summary.kinds).filter((kind) => !known.has(kind))] : GROUPS[group];
  const countOf = (group) => kindsOf(group).reduce((total, kind) => total + (summary.kinds[kind] ?? 0), 0);
  if (!summary.agents.includes(sessionView.agent)) sessionView.agent = '';
  const feed = recordFeed(sessionId, () => {
    const kinds = [...sessionView.groups].flatMap(kindsOf);
    if (kinds.length === 0) return null;
    return { kinds: kinds.join(','), order: sessionView.order, ...(sessionView.agent ? { agent: sessionView.agent } : {}) };
  });
  const { list, more } = feed;
  const load = () => feed.reload();

  const checks = el(
    'div',
    { class: 'checks' },
    `${t('show')}:`,
    Object.keys(GROUPS).map((group) =>
      el(
        'label',
        {},
        el('input', {
          type: 'checkbox',
          checked: sessionView.groups.has(group),
          onchange: (event) => {
            if (event.target.checked) sessionView.groups.add(group);
            else sessionView.groups.delete(group);
            load();
          },
        }),
        ` ${t(group)} (${whole(countOf(group))})`,
      ),
    ),
    orderSelector(load),
    summary.agents.length > 1
      ? el(
          'label',
          {},
          `${t('agent')} `,
          el(
            'select',
            {
              onchange: (event) => {
                sessionView.agent = event.target.value;
                load();
              },
            },
            el('option', { value: '' }, t('allAgents')),
            summary.agents.map((agent) => el('option', { value: agent, selected: sessionView.agent === agent }, agent === 'main' ? t('mainAgent') : agent)),
          ),
        )
      : null,
  );
  panel.replaceChildren(checks, list, more);
  load();
}


// ---- what left this machine ------------------------------------------------------------------

const networkView = { categories: new Set(['web', 'shell', 'mcp']), host: '' };

/**
 * The tool calls that reached beyond this machine and Claude, gathered: where they went, how much
 * was sent and came back, and each call with its result.
 */
async function network(panel, sessionId, turnOf) {
  panel.replaceChildren(el('p', { class: 'empty' }, t('loading')));
  const summary = await api(`sessions/${encodeURIComponent(sessionId)}/network`);
  if (!turnOf()) return;
  if (summary.calls === 0) {
    panel.replaceChildren(el('p', { class: 'empty' }, t('noNetwork')), el('p', { class: 'note' }, t('networkNote')));
    return;
  }
  if (!summary.hosts.some((row) => row.host === networkView.host)) networkView.host = '';
  const feed = recordFeed(sessionId, () => {
    if (networkView.categories.size === 0) return null;
    return {
      network: '1',
      order: sessionView.order,
      categories: [...networkView.categories].join(','),
      ...(networkView.host ? { host: networkView.host } : {}),
    };
  });
  const table = el('div');
  const showTable = () => {
    const rows = summary.hosts.filter((row) => networkView.categories.has(row.category));
    table.replaceChildren(
      el(
        'div',
        { class: 'scroll' },
        el(
          'table',
          {},
          el(
            'thead',
            {},
            el(
              'tr',
              {},
              el('th', {}, t('destination')),
              el('th', {}, t('via')),
              el('th', { class: 'num' }, t('calls')),
              el('th', { class: 'num' }, t('sent')),
              el('th', { class: 'num' }, t('received')),
              el('th', { class: 'num' }, t('errors')),
              el('th', {}, t('lastAt')),
            ),
          ),
          el(
            'tbody',
            {},
            rows.map((row) =>
              el(
                'tr',
                { class: networkView.host === row.host ? 'link chosen' : 'link' },
                el(
                  'td',
                  {},
                  el(
                    'button',
                    {
                      type: 'button',
                      class: 'plain',
                      'aria-pressed': String(networkView.host === row.host),
                      onclick: () => {
                        networkView.host = networkView.host === row.host ? '' : row.host;
                        showTable();
                        feed.reload();
                      },
                    },
                    row.host,
                  ),
                ),
                el('td', {}, el('span', { class: `tag via-${row.category}` }, t(`via_${row.category}`))),
                el('td', { class: 'num' }, whole(row.calls)),
                el('td', { class: 'num' }, bytes(row.sentBytes)),
                el('td', { class: 'num' }, bytes(row.receivedBytes)),
                el('td', { class: row.errors ? 'num failed' : 'num' }, whole(row.errors)),
                el('td', { class: 'when' }, row.lastAt ? when(row.lastAt) : '–'),
              ),
            ),
          ),
        ),
      ),
    );
  };
  const checks = el(
    'div',
    { class: 'checks' },
    `${t('via')}:`,
    ['web', 'shell', 'mcp'].map((category) =>
      el(
        'label',
        {},
        el('input', {
          type: 'checkbox',
          checked: networkView.categories.has(category),
          onchange: (event) => {
            if (event.target.checked) networkView.categories.add(category);
            else networkView.categories.delete(category);
            showTable();
            feed.reload();
          },
        }),
        ` ${t(`via_${category}`)} (${whole(summary.categories[category] ?? 0)})`,
      ),
    ),
    orderSelector(feed.reload),
  );
  panel.replaceChildren(
    el('p', { class: 'note lead' }, t('networkNote')),
    checks,
    table,
    el('h2', {}, t('networkCalls')),
    feed.list,
    feed.more,
  );
  showTable();
  feed.reload();
}

const PROSE = new Set(['user_prompt', 'assistant_text']);

// Rows of prose are open from the start; each fetches the rest of its text when it comes into view.
const comingIntoView = new IntersectionObserver(
  (entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      comingIntoView.unobserve(entry.target);
      entry.target.dispatchEvent(new Event('inview'));
    }
  },
  { rootMargin: '600px' },
);

/** What a tool call does, in a line: the command, the file, the address — not the JSON around it. */
function toolLine(record) {
  const pick = (input) => {
    for (const name of ['description', 'command', 'file_path', 'url', 'query', 'pattern', 'prompt']) {
      if (typeof input[name] === 'string' && input[name]) return input[name];
    }
    return null;
  };
  try {
    const input = JSON.parse(record.text);
    return (input && typeof input === 'object' && pick(input)) || record.text;
  } catch {
    // Cut short by the list: the first field is usually there all the same.
    const field = /"(?:description|command|file_path|url|query|pattern)":"((?:\\.|[^"\\])*)/.exec(record.text);
    if (!field) return record.text;
    try {
      return JSON.parse(`"${field[1]}"`);
    } catch {
      return field[1];
    }
  }
}

/** A record as a row that opens; a tool call also takes its result, when that arrives. */
function recordRow(record) {
  const failed = record.kind === 'tool_result' && record.subtype === 'error';
  const said = record.attributes ?? {};
  const prose = PROSE.has(record.kind);
  let label = TEXT[state.lang].kinds[record.kind] ?? record.kind;
  let flavour = '';
  let firstLine = record.text;
  if (record.kind === 'user_prompt') {
    // Not everything filed as a prompt was typed by somebody: Claude Code writes some itself.
    const gist = Render.gist(record.text);
    firstLine = gist.line;
    if (said.compact_summary) [label, flavour] = [t('labelSummary'), 'injected'];
    else if (gist.kind === 'command') [label, flavour] = [t('labelCommand'), 'command'];
    else if (gist.kind === 'notice' || said.prompt_source === 'system') [label, flavour] = [t('labelNotice'), 'injected'];
    else if (gist.kind === 'output') [label, flavour] = [t('labelOutput'), 'injected'];
    else if (gist.kind === 'wrapped') [label, flavour] = [t('labelInserted'), 'injected'];
  } else if (record.kind === 'tool_use') {
    label = record.toolName || label;
    firstLine = toolLine(record);
  }
  const meta = el('span', { class: 'meta' });
  const setMeta = (...parts) => (meta.textContent = parts.filter(Boolean).join(' · '));
  setMeta(
    record.subtype && record.kind !== 'tool_result' ? record.subtype : null,
    record.outputTokens !== null ? `${shortModel(record.model ?? '')} ${compact(record.outputTokens)}` : null,
    bytes(record.contentBytes),
  );
  const body = el('div', { class: 'body' });
  const node = el(
    'details',
    {
      class: ['record', record.kind, flavour, failed ? 'failed' : '', record.agentId !== 'main' ? 'agent' : ''].filter(Boolean).join(' '),
      // What was said is read, not opened one by one. A summary after a compaction is long, and nobody's words.
      open: prose && !said.compact_summary,
    },
    el(
      'summary',
      {},
      el('span', { class: 'time' }, when(record.ts, true)),
      el('span', { class: 'kind' }, label),
      el('span', { class: 'line' }, firstLine.replace(/\s+/g, ' ').trim()),
      record.network ? el('span', { class: `tag via-${record.network.category}`, title: t('networkTag') }, `↗ ${record.network.hosts.join(', ')}`) : null,
      record.agentId !== 'main' ? el('span', { class: 'tag' }, record.agentId) : null,
      meta,
    ),
    body,
  );
  const parts = [{ title: record.kind === 'tool_use' ? t('inputOf') : null, record }];
  let filled = false;
  const fill = () => {
    filled = true;
    // replaceChildren would write a missing part out as the word "null".
    const children = parts.flatMap((part) => [part.title ? el('h4', {}, part.title) : null, textOf(part.record)]);
    if (record.kind === 'tool_use' && parts.length === 1) children.push(el('h4', {}, t('noResult')));
    body.replaceChildren(...children.filter(Boolean));
  };
  node.addEventListener('toggle', () => {
    if (node.open && !filled) fill();
  });
  if (node.open) {
    node.addEventListener('inview', () => {
      if (!filled) fill();
    });
    comingIntoView.observe(node);
  }
  return {
    node,
    attach(result) {
      const bad = result.kind === 'tool_result' && result.subtype === 'error';
      parts.push({ title: result.kind === 'mcp_meta' ? 'MCP' : bad ? `${t('resultOf')} (${t('failed')})` : t('resultOf'), record: result });
      if (bad) node.classList.add('failed');
      if (result.kind === 'tool_result') setMeta(bad ? t('failed') : null, `→ ${bytes(result.contentBytes)}`);
      if (filled) fill();
    },
  };
}

/** Recorded text laid out for reading, by what kind of thing it is. */
function laidOut(record, text, complete) {
  const labels = { command: t('labelCommand'), notice: t('labelNotice'), reminder: t('labelReminder') };
  if (record.kind === 'user_prompt') return Render.prompt(text, labels);
  if (record.kind === 'assistant_text' || record.kind === 'thinking') return Render.markdown(text);
  // JSON cut short is not JSON: it waits, as it is, for the rest.
  if (record.kind === 'tool_use' && complete) return Render.toolInput(record.toolName, text);
  if (record.kind === 'mcp_meta' && complete) {
    try {
      return Render.json(JSON.parse(text));
    } catch {
      // Left as it is.
    }
  }
  return el('pre', { class: record.kind === 'tool_result' && record.subtype === 'error' ? 'code bad' : 'code' }, text);
}

/**
 * The text of a record: what the list already has, then all of it once fetched. Long text is held
 * to a screenful until asked for; prose can be switched to the characters as they were recorded.
 */
function textOf(record) {
  const content = el('div', { class: 'content' });
  const holder = el('div', { class: 'clamp' }, content);
  const tools = el('div', { class: 'text-tools' });
  let text = record.text + (record.whole ? '' : ' …');
  let complete = record.whole;
  let raw = false;
  const expand = el('button', {
    type: 'button',
    class: 'quiet small',
    onclick: () => {
      holder.classList.toggle('open');
      expand.textContent = holder.classList.contains('open') ? t('showLess') : t('showAll');
    },
  });
  const asRecorded = PROSE.has(record.kind)
    ? el('button', {
        type: 'button',
        class: 'quiet small',
        onclick: () => {
          raw = !raw;
          show();
        },
      })
    : null;
  const show = () => {
    content.replaceChildren(raw ? el('pre', { class: 'code' }, text) : laidOut(record, text, complete));
    if (asRecorded) asRecorded.textContent = raw ? t('formatted') : t('asRecorded');
    // Measured once it is on the page: only text taller than the clamp gets the button.
    requestAnimationFrame(() => {
      const tall = holder.classList.contains('open') || content.scrollHeight > holder.clientHeight + 4;
      expand.hidden = !tall;
      expand.textContent = holder.classList.contains('open') ? t('showLess') : t('showAll');
      holder.classList.toggle('short', !tall);
    });
  };
  tools.append(expand, ...(asRecorded ? [asRecorded] : []));
  show();
  if (!record.whole && record.contentHash) {
    api(`content/${record.contentHash}`)
      .then((fetched) => {
        text = fetched.text;
        complete = true;
        show();
      })
      .catch((error) => content.append(el('p', { class: 'error' }, `${t('loadFailed')}: ${error.message}`)));
  }
  return el('div', { class: 'text' }, holder, tools);
}

// ---- usage over time ---------------------------------------------------------------------------

const usageView = { days: remembered('usageDays', '30'), metric: remembered('usageMetric', 'metricOutput') };
const METRICS = {
  metricOutput: (u) => u.outputTokens,
  metricInput: (u) => u.inputTokens + u.cacheReadTokens + u.cacheCreationTokens,
  metricMessages: (u) => u.messages,
};
// Told apart by lightness as well as hue, for whoever does not see the hues.
const SERIES = ['#b4532a', '#2f6f8f', '#8a7a1f', '#6b4f9a', '#3f8a5f', '#9a9a9a'];

const dayKey = (millis) => {
  const date = new Date(millis);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
};

async function drawUsage(view, turn) {
  if (state.accounts.length === 0) {
    view.replaceChildren(el('p', { class: 'empty' }, t('noAccounts')));
    return;
  }
  view.replaceChildren(el('p', { class: 'empty' }, t('loading')));
  const sessions = await api(`sessions?account=${encodeURIComponent(state.account)}&limit=1000`);
  if (turn !== state.drawn) return;
  const body = el('div');

  const fill = () => {
    const days = Number(usageView.days);
    const measure = METRICS[usageView.metric];
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const keys = [];
    for (let back = days - 1; back >= 0; back--) keys.push(dayKey(today.getTime() - back * 86400000 + 43200000));
    const shown = sessions.filter((s) => keys.includes(dayKey(s.startedAt)));

    const perModel = new Map();
    const perProject = new Map();
    const perDay = new Map(keys.map((key) => [key, new Map()]));
    for (const s of shown) {
      const project = perProject.get(s.projectPath ?? '') ?? { sessions: 0, messages: 0, output: 0, cost: null };
      project.sessions += 1;
      if (s.costUsd !== null) project.cost = (project.cost ?? 0) + s.costUsd;
      for (const u of s.usageByModel) {
        const model = perModel.get(u.model) ?? { messages: 0, inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheCreationTokens: 0, thinkingTokens: 0, webSearchRequests: 0, webFetchRequests: 0 };
        for (const field of Object.keys(model)) model[field] += u[field];
        perModel.set(u.model, model);
        project.messages += u.messages;
        project.output += u.outputTokens;
        const day = perDay.get(dayKey(s.startedAt));
        day.set(u.model, (day.get(u.model) ?? 0) + measure(u));
      }
      perProject.set(s.projectPath ?? '', project);
    }
    const models = [...perModel.keys()].sort((a, b) => measure(perModel.get(b)) - measure(perModel.get(a)));
    const total = models.reduce((sum, model) => sum + measure(perModel.get(model)), 0);
    const costs = shown.filter((s) => s.costUsd !== null);
    if (shown.length === 0 || models.length === 0) {
      body.replaceChildren(el('p', { class: 'empty' }, t('noUsage')));
      return;
    }
    body.replaceChildren(
      el(
        'div',
        { class: 'stats' },
        stat(whole(shown.length), t('sessions')),
        stat(compact(total), t(usageView.metric)),
        stat(costs.length ? money(costs.reduce((sum, s) => sum + s.costUsd, 0)) : '–', t('cost')),
      ),
      el('h2', {}, t('byDay')),
      chart(keys, perDay, models),
      el('p', { class: 'note' }, t('byStartDay')),
      el('h2', {}, t('byModel')),
      usageTable(models.map((model) => ({ model, ...perModel.get(model) }))),
      el('h2', {}, t('byProject')),
      el(
        'div',
        { class: 'scroll' },
        el(
          'table',
          {},
          el(
            'thead',
            {},
            el('tr', {}, el('th', {}, t('project')), el('th', { class: 'num' }, t('sessions')), el('th', { class: 'num' }, t('responses')), el('th', { class: 'num' }, t('output')), el('th', { class: 'num' }, t('cost'))),
          ),
          el(
            'tbody',
            {},
            [...perProject.entries()]
              .sort((a, b) => b[1].output - a[1].output)
              .map(([path, p]) =>
                el(
                  'tr',
                  {},
                  el('td', { title: path }, baseName(path) || t('noProject')),
                  el('td', { class: 'num' }, whole(p.sessions)),
                  el('td', { class: 'num' }, whole(p.messages)),
                  el('td', { class: 'num' }, compact(p.output)),
                  el('td', { class: 'num' }, money(p.cost)),
                ),
              ),
          ),
        ),
      ),
    );
  };

  const pick = (key, options, label) =>
    el(
      'label',
      { class: 'control' },
      `${label} `,
      el(
        'select',
        {
          onchange: (event) => {
            usageView[key] = event.target.value;
            remember(key === 'days' ? 'usageDays' : 'usageMetric', usageView[key]);
            fill();
          },
        },
        options.map(([value, text]) => el('option', { value, selected: usageView[key] === value }, text)),
      ),
    );
  view.replaceChildren(
    el(
      'div',
      { class: 'filters' },
      pick('days', ['7', '30', '90'].map((n) => [n, t('days', n)]), t('period')),
      pick('metric', Object.keys(METRICS).map((name) => [name, t(name)]), t('metric')),
    ),
    body,
  );
  fill();
}

function svg(tag, attributes, ...children) {
  const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [name, value] of Object.entries(attributes || {})) node.setAttribute(name, value);
  for (const child of children.flat()) if (child) node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  return node;
}

/** Stacked bars, a day each; every segment says what it is to a pointer and to a screen reader. */
function chart(keys, perDay, models) {
  const width = 960;
  const height = 240;
  const left = 56;
  const bottom = 24;
  const top = 8;
  const colorOf = (model) => SERIES[Math.min(models.indexOf(model), SERIES.length - 1)];
  const totals = keys.map((key) => [...perDay.get(key).values()].reduce((sum, value) => sum + value, 0));
  const most = Math.max(1, ...totals);
  const step = (width - left) / keys.length;
  const bar = Math.max(2, Math.min(28, step - 3));
  const scale = (value) => (value / most) * (height - bottom - top);
  const every = Math.ceil(keys.length / 10);
  const parts = [
    svg('line', { x1: left, y1: height - bottom, x2: width, y2: height - bottom, class: 'axis' }),
    svg('line', { x1: left, y1: top, x2: width, y2: top, class: 'grid' }),
    svg('text', { x: left - 6, y: top + 4, class: 'tick end' }, compact(most)),
    svg('text', { x: left - 6, y: height - bottom, class: 'tick end' }, '0'),
  ];
  keys.forEach((key, index) => {
    const x = left + index * step + (step - bar) / 2;
    let y = height - bottom;
    for (const model of models) {
      const value = perDay.get(key).get(model) ?? 0;
      if (value === 0) continue;
      const tall = Math.max(1, scale(value));
      y -= tall;
      parts.push(svg('rect', { x, y, width: bar, height: tall, fill: colorOf(model) }, svg('title', {}, `${key}  ${model}  ${whole(value)}`)));
    }
    if (index % every === 0) parts.push(svg('text', { x: x + bar / 2, y: height - 6, class: 'tick middle' }, key.slice(5).replace('-', '/')));
  });
  return el(
    'figure',
    { class: 'chart' },
    svg('svg', { viewBox: `0 0 ${width} ${height}`, role: 'img' }, parts),
    el(
      'figcaption',
      {},
      models.map((model) => {
        const swatch = svg('svg', { viewBox: '0 0 10 10', class: 'swatch' }, svg('rect', { width: 10, height: 10, fill: colorOf(model) }));
        return el('span', { class: 'key' }, swatch, ` ${model}`);
      }),
    ),
  );
}

// ---- status ----------------------------------------------------------------------------------

async function drawStatus(view, turn) {
  view.replaceChildren(el('p', { class: 'empty' }, t('loading')));
  const status = await api('status');
  if (turn !== state.drawn) return;
  const queue = status.queue ?? { waiting: 0, unreadable: 0, entries: [], oldestWaiting: null };
  const hooks = status.launcher?.hooksInstalled;
  // As `ccrec doctor` has it: an ingest takes seconds, so one asked for ten minutes ago is not coming.
  const stuck = queue.waiting > 0 && Date.now() - Date.parse(queue.oldestWaiting) > 10 * 60 * 1000;
  const check = (ok, name, detail) =>
    el('tr', {}, el('td', { class: ok === null ? 'mark' : ok ? 'mark good' : 'mark failed' }, ok === null ? '?' : ok ? 'ok' : 'FAIL'), el('td', {}, name), el('td', { class: 'project' }, detail));
  const stateOf = (entry) => (entry.waiting ? t('stateWaiting') : entry.recordedAt ? t('stateRecorded') : t('stateIdle'));
  const time = (iso) => (iso ? when(Date.parse(iso), true) : '–');
  const fact = (label, value) => (value === undefined || value === null ? [] : [el('dt', {}, label), el('dd', {}, value)]);

  view.replaceChildren(
    el('div', { class: 'filters' }, el('button', { type: 'button', onclick: () => draw() }, t('refresh'))),
    el('h2', {}, t('checks')),
    el(
      'div',
      { class: 'scroll' },
      el(
        'table',
        {},
        el(
          'tbody',
          {},
          check(hooks === undefined ? null : hooks, t('checkHooks'), hooks === undefined ? t('unknown') : (status.launcher.settingsPath ?? '')),
          check(!stuck, t('checkQueue'), queue.waiting === 0 ? t('noneWaiting') : t('waitingSince', queue.waiting, time(queue.oldestWaiting))),
          check(queue.unreadable === 0, t('checkReadable'), queue.unreadable === 0 ? '' : t('unreadable', queue.unreadable)),
        ),
      ),
    ),
    el('h2', {}, t('environment')),
    el(
      'dl',
      { class: 'facts' },
      fact(t('version'), status.launcher?.version),
      fact(t('javaVersion'), status.java),
      fact(t('homeDir'), status.home),
      fact(t('databaseConfig'), status.database?.config),
      fact(t('storage'), status.database && `${status.database.storage} · ${status.database.contactPoints}`),
      fact(t('accountsRecorded'), whole(status.accounts)),
      fact(t('settings'), status.settings ? JSON.stringify(status.settings) : status.settingsError),
    ),
    el('h2', {}, t('queue')),
    queue.entries.length === 0
      ? el('p', { class: 'empty' }, t('noQueue'))
      : el(
          'div',
          { class: 'scroll' },
          el(
            'table',
            {},
            el('thead', {}, el('tr', {}, [t('sessions'), t('lastEvent'), t('updated'), t('requested'), t('recorded'), t('state')].map((label) => el('th', {}, label)))),
            el(
              'tbody',
              {},
              queue.entries.map((entry) =>
                el(
                  'tr',
                  {},
                  el('td', {}, el('a', { href: `#/sessions/${encodeURIComponent(entry.sessionId)}` }, entry.sessionId)),
                  el('td', {}, entry.lastEvent ?? '–'),
                  el('td', { class: 'when' }, time(entry.updatedAt)),
                  el('td', { class: 'when' }, time(entry.ingestRequestedAt)),
                  el('td', { class: 'when' }, time(entry.recordedAt)),
                  el('td', { class: entry.waiting ? 'failed' : '' }, stateOf(entry)),
                ),
              ),
            ),
          ),
        ),
    el('h2', {}, t('ingestLog')),
    status.ingestLog && status.ingestLog.length ? el('pre', {}, status.ingestLog.join('\n')) : el('p', { class: 'empty' }, t('noLog')),
  );
}

// ---- start -------------------------------------------------------------------------------------

async function start() {
  try {
    const { current, accounts } = await api('accounts');
    state.accounts = accounts;
    const ids = accounts.map((account) => account.accountId);
    if (!ids.includes(state.account)) state.account = ids.includes(current) ? current : (ids[0] ?? null);
  } catch (error) {
    document.getElementById('view').replaceChildren(el('p', { class: 'error' }, `${t('loadFailed')}: ${error.message}`));
    return;
  }
  window.addEventListener('hashchange', draw);
  draw();
}

start();
