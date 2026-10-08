'use strict';

// Everything recorded is somebody else's text as far as this page is concerned: prompts, tool
// output, file contents. It is only ever put into the page as text, never as markup.

const TEXT = {
  ja: {
    sessions: 'セッション',
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
    sessions: 'Sessions',
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
    errors: 'errors',
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
  nav.replaceChildren(el('a', { href: '#/sessions', class: 'current' }, t('sessions')));
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
    location.hash = '#/sessions';
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

  const fill = () => {
    const since = listFilters.days === 'all' ? 0 : Date.now() - Number(listFilters.days) * 86400000;
    const needle = listFilters.title.trim().toLowerCase();
    const shown = sessions.filter(
      (s) =>
        (s.endedAt ?? s.startedAt) >= since &&
        (!listFilters.project || s.projectPath === listFilters.project) &&
        (!needle || `${s.title ?? ''} ${s.projectPath ?? ''}`.toLowerCase().includes(needle)),
    );
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
  view.replaceChildren(
    el(
      'div',
      { class: 'filters' },
      el('label', { class: 'control' }, `${t('period')} `, period),
      el('label', { class: 'control' }, `${t('project')} `, project),
      title,
    ),
    stats,
    table,
  );
  fill();
}

function stat(value, label) {
  return el('div', { class: 'stat' }, el('b', {}, value), el('span', {}, label));
}

// ---- one session -------------------------------------------------------------------------------

const sessionView = { sessionId: null, tab: 'conversation', groups: new Set(SHOWN_AT_FIRST), agent: '' };

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
      ...['conversation', 'usage', 'tools', 'details'].map((name) =>
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
    else conversation(panel, sessionId, summary);
  };

  view.replaceChildren(
    el('a', { class: 'back', href: '#/sessions' }, t('back')),
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
      stat(`${whole(calls)}${failures ? ` · ${failures} ${t('errors')}` : ''}`, t('toolCalls')),
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

function conversation(panel, sessionId, summary) {
  const known = new Set(Object.values(GROUPS).flat());
  const kindsOf = (group) =>
    group === 'groupSystem' ? [...GROUPS.groupSystem, ...Object.keys(summary.kinds).filter((kind) => !known.has(kind))] : GROUPS[group];
  const countOf = (group) => kindsOf(group).reduce((total, kind) => total + (summary.kinds[kind] ?? 0), 0);
  const list = el('div');
  const more = el('button', { type: 'button', class: 'more' });
  let loaded = 0;
  let run = 0;
  // Tool calls already on the page, by id, so that a result can be put where its call is.
  let callsShown = new Map();

  const load = async (fresh) => {
    const mine = fresh ? ++run : run;
    if (fresh) {
      loaded = 0;
      callsShown = new Map();
      list.replaceChildren(el('p', { class: 'empty' }, t('loading')));
    }
    more.hidden = true;
    const kinds = [...sessionView.groups].flatMap(kindsOf);
    if (kinds.length === 0) {
      list.replaceChildren(el('p', { class: 'empty' }, t('noRecords')));
      return;
    }
    const query = new URLSearchParams({ kinds: kinds.join(','), offset: String(loaded) });
    if (sessionView.agent) query.set('agent', sessionView.agent);
    const page = await api(`sessions/${encodeURIComponent(sessionId)}/records?${query}`);
    if (mine !== run) return;
    if (fresh) list.replaceChildren();
    for (const record of page.records) {
      const call = record.kind === 'tool_result' || record.kind === 'mcp_meta' ? callsShown.get(record.toolUseId) : null;
      if (call) {
        call.attach(record);
        continue;
      }
      const row = recordRow(record);
      if (record.kind === 'tool_use' && record.toolUseId) callsShown.set(record.toolUseId, row);
      list.append(row.node);
    }
    loaded += page.records.length;
    if (loaded === 0) list.replaceChildren(el('p', { class: 'empty' }, t('noRecords')));
    more.hidden = loaded >= page.total;
    more.textContent = t('loadMore', loaded, page.total);
  };
  more.onclick = () => load(false).catch(showFailure);
  const showFailure = (error) => list.append(el('p', { class: 'error' }, `${t('loadFailed')}: ${error.message}`));

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
            load(true).catch(showFailure);
          },
        }),
        ` ${t(group)} (${whole(countOf(group))})`,
      ),
    ),
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
                load(true).catch(showFailure);
              },
            },
            el('option', { value: '' }, t('allAgents')),
            summary.agents.map((agent) => el('option', { value: agent, selected: sessionView.agent === agent }, agent === 'main' ? t('mainAgent') : agent)),
          ),
        )
      : null,
  );
  if (!summary.agents.includes(sessionView.agent)) sessionView.agent = '';
  panel.replaceChildren(checks, list, more);
  load(true).catch(showFailure);
}

/** A record as a row that opens; a tool call also takes its result, when that arrives. */
function recordRow(record) {
  const failed = record.kind === 'tool_result' && record.subtype === 'error';
  const label = TEXT[state.lang].kinds[record.kind] ?? record.kind;
  const firstLine = record.text.replace(/\s+/g, ' ').trim();
  const prose = record.kind === 'user_prompt' || record.kind === 'assistant_text';
  const kind = el('span', { class: 'kind' }, record.kind === 'tool_use' ? record.toolName || label : label);
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
    { class: `record ${record.kind}${failed ? ' failed' : ''}${record.agentId !== 'main' ? ' agent' : ''}` },
    el(
      'summary',
      {},
      el('span', { class: 'time' }, when(record.ts, true)),
      kind,
      el('span', { class: 'line' }, firstLine),
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
    const children = parts.flatMap((part) => [part.title ? el('h4', {}, part.title) : null, textOf(part.record, prose)]);
    if (record.kind === 'tool_use' && parts.length === 1) children.push(el('h4', {}, t('noResult')));
    body.replaceChildren(...children.filter(Boolean));
  };
  node.addEventListener('toggle', () => {
    if (node.open && !filled) fill();
  });
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

/** The text of a record: what the list already has, then all of it once fetched. */
function textOf(record, prose) {
  const pre = el('pre', { class: prose ? 'prose' : '' }, pretty(record, record.text + (record.whole ? '' : ' …')));
  if (!record.whole && record.contentHash) {
    api(`content/${record.contentHash}`)
      .then((content) => (pre.textContent = pretty(record, content.text)))
      .catch((error) => (pre.textContent = `${record.text} …\n\n[${t('loadFailed')}: ${error.message}]`));
  }
  return pre;
}

/** A tool's input is JSON on one line; it reads better laid out. Anything else is left as it is. */
function pretty(record, text) {
  if (record.kind !== 'tool_use' && record.kind !== 'mcp_meta') return text;
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
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
