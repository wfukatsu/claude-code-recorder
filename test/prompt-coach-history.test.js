import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { lastRun, parseTarget, reportPath, saveRun } from '../skills/prompt-coach/scripts/history.mjs';

function withHome(body) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'prompt-coach-'));
  const saved = process.env.CCREC_HOME;
  process.env.CCREC_HOME = dir;
  try {
    return body(dir);
  } finally {
    if (saved === undefined) delete process.env.CCREC_HOME;
    else process.env.CCREC_HOME = saved;
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

const analysis = (until) => ({
  scope: { since: null, until, promptProfile: { work: 4, criteria: 1, constraint: 0, anchored: 2 }, rates: {}, tokensByModel: {} },
  sessions: [{ sessionId: 's1' }],
});

test('the last run is the latest over the same target, one session or all', () => {
  withHome(() => {
    assert.equal(lastRun(parseTarget('all')), null);
    saveRun({ target: parseTarget('session:s1'), analysis: analysis('2026-10-20T00:00:00Z'), findings: [], report: '/r1.md' });
    saveRun({ target: parseTarget('all'), analysis: analysis('2026-10-21T00:00:00Z'), findings: [], report: '/r2.md' });
    saveRun({ target: parseTarget('session:s1'), analysis: analysis('2026-10-22T00:00:00Z'), findings: [], report: '/r3.md' });
    assert.equal(lastRun(parseTarget('session:s1')).run.report, '/r3.md');
    assert.equal(lastRun(parseTarget('all')).run.report, '/r2.md');
    assert.equal(lastRun(parseTarget('session:s2')), null);
  });
});

test('a run keeps the findings it can compare next time, readable by its owner only', () => {
  withHome((dir) => {
    const { file, dropped } = saveRun({
      target: parseTarget('all'),
      analysis: analysis('2026-10-22T00:00:00Z'),
      findings: [
        { id: 'missing-criteria', title: '完了条件を書く', severity: 'medium', sessionId: 's1', at: '2026-10-21T00:00:00Z' },
        { id: 'made-up', title: '?' },
      ],
      report: '/r.md',
    });
    assert.deepEqual(dropped, ['made-up']);
    const run = JSON.parse(fs.readFileSync(file, 'utf8'));
    assert.deepEqual(run.findings.map((f) => f.id), ['missing-criteria']);
    assert.equal(fs.statSync(file).mode & 0o777, 0o600);
    assert.equal(fs.statSync(path.join(dir, 'prompt-coach')).mode & 0o777, 0o700);
  });
});

test('a second report on the same day gets a number of its own', () => {
  withHome(() => {
    const day = new Date('2026-10-23T10:00:00');
    const first = reportPath(day);
    assert.match(first, /prompt-review-2026-10-23\.md$/);
    fs.writeFileSync(first, '');
    assert.match(reportPath(day), /prompt-review-2026-10-23-2\.md$/);
  });
  assert.throws(() => parseTarget('current'), /--target/);
});
