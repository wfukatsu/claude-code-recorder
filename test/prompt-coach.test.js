import assert from 'node:assert/strict';
import { test } from 'node:test';
import { analyzeSession, patterns, promptFeatures, selectSessions, typedText } from '../skills/prompt-coach/scripts/lib.mjs';

// Records as `ccrec show --json --full` lists them, with only what the analysis reads.
let line = 0;
function rec(kind, fields = {}) {
  line++;
  return { sessionId: 's1', agentId: 'main', lineNo: line, kind, ts: Date.parse('2026-10-21T01:00:00Z') + line * 1000, ...fields };
}
const prompt = (content, fields) => rec('user_prompt', { content, ...fields });
const answer = (content, fields) => rec('assistant_text', { content, ...fields });
const call = (toolName, input, toolUseId, fields) => rec('tool_use', { toolName, toolUseId, content: JSON.stringify(input), ...fields });
const result = (toolUseId, content, fields) => rec('tool_result', { toolUseId, content, contentBytes: content.length, ...fields });
const spent = (model, input, cacheRead, output, fields = {}) =>
  rec('assistant_text', { model, inputTokens: input, cacheReadTokens: cacheRead, cacheCreationTokens: 0, outputTokens: output, thinkingTokens: 0, content: 'ok', ...fields });

const types = (analysis) => analysis.flags.map((f) => f.type);

test('what Claude Code wraps around a prompt is not what was typed', () => {
  assert.equal(typedText('<command-name>/clear</command-name><command-args></command-args>'), null);
  assert.equal(typedText('<system-reminder>be brief</system-reminder>\nfix the form'), 'fix the form');
  // What was pasted is kept out, cookies and all; that something was pasted is not.
  const text = typedText('cookie は、\n<pasted_content id="f1">\n{"csrftoken":"secret"}\n</pasted_content id="f1">\n です');
  assert.equal(text, 'cookie は、\n [pasted: 22 chars] \n です');
  assert.equal(promptFeatures(text).pasted, true);
});

test('a prompt is read for what it gives to work with', () => {
  const vague = promptFeatures('ボタンがずれてる。いい感じに直して');
  assert.equal(vague.vague, true);
  assert.equal(vague.trouble, true);
  assert.equal(vague.path, false);
  const anchored = promptFeatures('src/form.js の送信が失敗する。テストが通るまで直して。API は変えないで\n```\nTypeError: x is undefined\n```');
  assert.deepEqual(
    [anchored.path, anchored.code, anchored.criteria, anchored.constraint, anchored.vague],
    [true, true, true, true, false],
  );
  assert.equal(promptFeatures('その構成で進めてください').approval, true);
  assert.equal(promptFeatures('違う、そのファイルじゃなくて').correction, true);
});

test('a correction, a vague request and a report without evidence are flagged with the prompts they rest on', () => {
  const analysis = analyzeSession({
    session: null,
    summary: { sessionId: 's1', title: 't', tools: [] },
    records: [
      prompt('ログイン画面をいい感じにして'),
      answer('ヘッダーを作り直しました。'),
      prompt('違う、フォームの方です'),
      prompt('文字のずれを直して'),
    ],
  });
  assert.deepEqual(types(analysis).sort(), ['trouble-without-evidence', 'user-correction', 'vague-wording']);
  assert.deepEqual(analysis.metrics.promptProfile, { work: 3, criteria: 0, constraint: 0, anchored: 0 });
  const correction = analysis.flags.find((f) => f.type === 'user-correction');
  assert.equal(correction.severity, 'high');
  assert.deepEqual(
    correction.evidence.map((e) => e.text),
    ['ログイン画面をいい感じにして', 'ヘッダーを作り直しました。', '違う、フォームの方です'],
  );
});

test('a short prompt that sets off a long run is flagged, unless it only approves a plan', () => {
  const busy = (text) => [prompt(text), ...Array.from({ length: 15 }, (_, i) => call('Grep', { pattern: `p${i}` }, `g${i}`))];
  const flagged = analyzeSession({ session: null, summary: { tools: [] }, records: busy('ライブラリを作って') });
  assert.ok(types(flagged).includes('short-prompt-big-task'));
  const approved = analyzeSession({ session: null, summary: { tools: [] }, records: busy('その構成で進めてください') });
  assert.ok(!types(approved).includes('short-prompt-big-task'));
});

test('reading the same file again counts as a repeat only when nothing changed it in between', () => {
  const read = (id) => call('Read', { file_path: '/w/a.js' }, id);
  const repeated = analyzeSession({ session: null, summary: { tools: [] }, records: [read('r1'), read('r2'), read('r3')] });
  assert.ok(types(repeated).includes('repeated-call'));
  const edited = analyzeSession({
    session: null,
    summary: { tools: [] },
    records: [read('r1'), call('Edit', { file_path: '/w/a.js' }, 'e1'), read('r2'), call('Bash', { command: 'npm run build' }, 'b1'), read('r3')],
  });
  assert.ok(!types(edited).includes('repeated-call'));
});

test('the same command run again right after it failed is flagged', () => {
  const analysis = analyzeSession({
    session: null,
    summary: { tools: [{ tool: 'Bash', calls: 2, errors: 2 }] },
    records: [
      call('Bash', { command: 'npm test' }, 'b1'),
      result('b1', 'Error: missing module', { subtype: 'error' }),
      call('Bash', { command: 'npm test' }, 'b2'),
      result('b2', 'Error: missing module', { subtype: 'error' }),
    ],
  });
  const retry = analysis.flags.find((f) => f.type === 'retry-after-error');
  assert.ok(retry, JSON.stringify(analysis.flags));
  assert.match(retry.evidence[1].text, /missing module/);
});

test('a top-tier model that only looked things up, and a cache that hardly worked, are flagged', () => {
  const analysis = analyzeSession({
    session: null,
    summary: { tools: [] },
    records: [
      call('Agent', { description: 'Find the config files' }, 'a1'),
      result('a1', 'found', { attributes: '{"agent_id":"sub1"}' }),
      spent('claude-opus-5-5', 300_000, 10_000, 500),
      call('Grep', { pattern: 'config' }, 'g1', { agentId: 'sub1' }),
      call('Read', { file_path: '/w/c.json' }, 'r1', { agentId: 'sub1' }),
      spent('claude-opus-5-5', 1000, 0, 200, { agentId: 'sub1' }),
      spent('<synthetic>', 0, 0, 0),
    ],
  });
  const heavy = analysis.flags.find((f) => f.type === 'heavy-model-for-lookup');
  assert.equal(heavy.agentId, 'sub1');
  assert.equal(heavy.evidence[0].text, 'Find the config files');
  assert.ok(types(analysis).includes('low-cache-hit'));
  assert.deepEqual(Object.keys(analysis.metrics.byModel), ['claude-opus-5-5'], 'a stand-in for no model is not a model');
});

test('an answer that takes something back is flagged with what it said', () => {
  const analysis = analyzeSession({
    session: null,
    summary: { tools: [] },
    records: [prompt('設定を読んで'), answer('先ほどの説明は間違いでした。正しくは 3 です。')],
  });
  const retraction = analysis.flags.find((f) => f.type === 'retraction');
  assert.match(retraction.evidence[0].text, /間違いでした/);
});

test('flags that come back across sessions are counted once per session', () => {
  const vague = () =>
    analyzeSession({ session: null, summary: { tools: [] }, records: [prompt('いい感じにして'), prompt('適当に直して')] });
  const rows = patterns([vague(), vague()]);
  const row = rows.find((r) => r.type === 'vague-wording');
  assert.equal(row.sessions, 2);
  assert.equal(row.occurrences, 4);
});

// ---- what a review of the first version found ----

test('markup the user typed stays in the prompt; only what Claude Code wraps around it comes out', () => {
  assert.equal(typedText('<button>送信</button> の色を青にして'), '<button>送信</button> の色を青にして');
  assert.equal(typedText('<div class="x">a</div>'), '<div class="x">a</div>');
  assert.equal(typedText('<local-command-stdout>done</local-command-stdout>'), null);
  assert.equal(typedText('[Request interrupted by user for tool use]'), null);
});

test('a go-ahead is a prompt that is nothing but one', () => {
  for (const text of ['はい、お願いします', 'その構成で進めてください', 'go ahead', 'OK!', '続けて']) {
    assert.equal(promptFeatures(text).approval, true, text);
  }
  for (const text of ['yaml の設定を直して', 'okta の連携を調べて', 'それでは次にログイン画面を作って', 'continue with the migration of the orders table']) {
    assert.equal(promptFeatures(text).approval, false, text);
  }
});

test('asking for a check is not a completion criterion; saying what has to pass is', () => {
  for (const text of ['追加したスライドが正しいかを確認してください', 'この機能の目的を説明して', '金曜までに資料を作って', '処理が終わったら、スライドにしてください']) {
    assert.equal(promptFeatures(text).criteria, false, text);
  }
  for (const text of [
    'テストが通ることを確認して',
    'npm test が通るまで直して',
    '一覧が日付順になればOK',
    '未実装のものがわかるようにしてください',
    'サンフランシスコは右にあるべきです',
    'fix it so that the build passes',
  ]) {
    assert.equal(promptFeatures(text).criteria, true, text);
  }
});

test('"not A but B" in a request is not a correction, and a correction answers something Claude said', () => {
  for (const text of ['React ではなくて Vue で書いて', 'use fetch instead of axios', 'いやー助かりました', 'no need to hurry']) {
    assert.equal(promptFeatures(text).correction, false, text);
  }
  for (const text of ['違う、フォームの方です', 'いや、そっちじゃない', 'no, the other file', 'I said the second one']) {
    assert.equal(promptFeatures(text).correction, true, text);
  }
  // Nothing was answered between the two prompts: the second only adds to the first.
  const unanswered = analyzeSession({ session: null, summary: {}, records: [prompt('ログインを直して'), prompt('違う、登録画面のほうも')] });
  assert.ok(!types(unanswered).includes('user-correction'));
});

test('only what happened in the period is counted, however long ago the session began', () => {
  const records = [
    prompt('いい感じにして'),
    rec('system', { subtype: 'compact_boundary', content: '{}' }),
    prompt('src/a.js のテストが通るまで直して'),
    rec('system', { subtype: 'compact_boundary', content: '{}' }),
  ];
  const analysis = analyzeSession({ session: null, summary: { compactions: 5, interrupted: 3 }, records, since: records[2].ts });
  assert.deepEqual(analysis.metrics.promptProfile, { work: 1, criteria: 1, constraint: 0, anchored: 1 });
  assert.equal(analysis.metrics.compactions, 1, 'the summary counts the whole session; the records in the period are counted');
  assert.ok(!types(analysis).includes('vague-wording'), 'the vague prompt came before the period');
  assert.ok(!types(analysis).includes('interrupted'));
});

test('a listing that came back full says it may have left sessions out', () => {
  const rows = [{ startedAt: 10, endedAt: 50 }, { startedAt: 5, endedAt: 8 }];
  assert.deepEqual(selectSessions(rows, 20, 2), { listed: [rows[0]], truncated: true });
  assert.equal(selectSessions(rows, 20, 3).truncated, false);
});

test('questions the user asked for are not counted as Claude having to ask', () => {
  const asked = (text) =>
    analyzeSession({
      session: null,
      summary: {},
      records: [prompt(text), call('AskUserQuestion', { questions: [{ question: 'どちらにしますか？' }] }, 'q1')],
    });
  assert.ok(!types(asked('方針を AskUserQuestion で確認してから進めて')).includes('clarification-asked'));
  assert.ok(!types(asked('日本語でAskUserQuesitonで確認してください')).includes('clarification-asked'));
  assert.ok(types(asked('スライドを作って')).includes('clarification-asked'));
});
