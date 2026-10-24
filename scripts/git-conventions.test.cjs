const test = require('node:test');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const { mkdtempSync, writeFileSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const path = require('node:path');
const { TYPES, APPS, validateBranch, validateTitle, validateIssueMatch, validatePush } = require('./git-conventions.cjs');

const root = path.resolve(__dirname, '..');
const cli = path.join(root, 'node_modules/@commitlint/cli/cli.js');
const sha = 'a'.repeat(40);
const zero = '0'.repeat(40);
const refLine = (local, remote, localSha = sha) => `${local} ${localSha} ${remote} ${zero}\n`;

for (const branch of ['main', 'feat/ACK-12-user-login', 'fix/ACK-35-dispatch-error', 'chore/ACK-42-project-initial-setup', ...TYPES.map((type) => `${type}/ACK-1-task-2`)]) {
  test(`branch accepts ${branch}`, () => assert.doesNotThrow(() => validateBranch(branch)));
}

for (const branch of ['develop', 'feature/ACK-12-user-login', 'hotfix/ACK-1-bug', 'feat/user-login', 'feat/ACK-12', 'feat/ACK-12-UserLogin', 'feat/ack-12-login', 'feat/ACK-x-login', 'feat/ACK-12-user_login', 'feat/ACK-12-user.login', 'feat/ACK-12-user--login', 'feat/ACK-12--login', 'feat/ACK-12-login-', 'feat/ACK-12-로그인', 'feat/ACK-12-login\n']) {
  test(`branch rejects ${JSON.stringify(branch)}`, () => assert.throws(() => validateBranch(branch), /형식:.*ACK-숫자/s));
}

for (const title of [
  'feat(BE): JWT 기반 로그인 API 구현 [ACK-12]',
  'fix(DRIVER): 위치 전송 오류 수정 [ACK-35]',
  'chore(INFRA): GitHub Actions 설정 [ACK-42]',
  ...APPS.map((app) => `docs(${app}): README 작성 [ACK-1]`),
  ...TYPES.map((type) => `${type}(AI): 모델 변경 [ACK-2]`),
]) {
  test(`PR accepts ${title}`, () => assert.doesNotThrow(() => validateTitle(title)));
}

for (const [title, reason] of [
  ['feature(BE): 로그인 [ACK-12]', /허용되지 않는 type/],
  ['build(BE): 빌드 [ACK-12]', /허용되지 않는 type/],
  ['feat(be): 로그인 [ACK-12]', /허용되지 않는 APP/],
  ['feat(WEB): 로그인 [ACK-12]', /허용되지 않는 APP/],
  ['feat: 로그인 [ACK-12]', /type\(APP\)/],
  ['feat(BE):로그인 [ACK-12]', /콜론 뒤 공백/],
  ['feat(BE): 로그인', /마지막/],
  ['feat(BE): 로그인 [OTHER-12]', /잘못된 Jira/],
  ['feat(BE): 로그인 [ACK-x]', /잘못된 Jira/],
  ['feat(BE):  [ACK-12]', /description/],
  ['feat(BE):  로그인 [ACK-12]', /앞뒤 공백/],
  ['feat(BE): 로그인  [ACK-12]', /앞뒤 공백/],
  ['feat(BE): 로그인 [ACK-12] extra', /마지막/],
  ['feat(BE): 로그인 [ACK-12]\n', /한 줄/],
]) {
  test(`PR rejects ${JSON.stringify(title)}`, () => assert.throws(() => validateTitle(title), reason));
}

test('Jira keys must match; main is exempt; invalid inputs cannot bypass matching', () => {
  assert.match(validateIssueMatch('feat/ACK-12-login', 'feat(BE): 로그인 [ACK-12]'), /일치/);
  assert.throws(() => validateIssueMatch('feat/ACK-12-login', 'feat(BE): 로그인 [ACK-35]'), /브랜치=ACK-12, PR 제목=ACK-35/);
  assert.match(validateIssueMatch('main', 'docs(INFRA): 문서 [ACK-42]'), /제외/);
  assert.throws(() => validateIssueMatch('invalid', 'feat(BE): 로그인 [ACK-12]'), /잘못된 브랜치명/);
  assert.throws(() => validateIssueMatch('main', 'invalid'), /잘못된 PR 제목/);
});

test('pre-push checks remote refs, including explicit refspecs, SHA and multiple refs', () => {
  assert.doesNotThrow(() => validatePush(refLine('refs/heads/invalid-local', 'refs/heads/feat/ACK-12-login')));
  assert.throws(() => validatePush(refLine('refs/heads/feat/ACK-12-login', 'refs/heads/invalid')), /잘못된 브랜치명/);
  assert.doesNotThrow(() => validatePush(refLine('HEAD', 'refs/heads/fix/ACK-35-bug')));
  assert.throws(() => validatePush(refLine(sha, 'refs/heads/invalid')), /잘못된 브랜치명/);
  assert.throws(() => validatePush(refLine('HEAD', 'refs/heads/main') + refLine('HEAD', 'refs/heads/invalid')), /잘못된 브랜치명/);
});

test('pre-push allows branch deletions, tags and no-op pushes', () => {
  assert.doesNotThrow(() => validatePush(refLine('(delete)', 'refs/heads/old-invalid', zero)));
  assert.doesNotThrow(() => validatePush(refLine('refs/tags/v1.0.0', 'refs/tags/v1.0.0')));
  assert.doesNotThrow(() => validatePush(''));
  assert.throws(() => validatePush('malformed input'), /ref 입력/);
});

test('pre-push hook forwards stdin and returns failure with actionable guidance', () => {
  const run = (remote) => spawnSync('sh', ['.husky/pre-push'], {
    cwd: root, input: refLine('HEAD', remote), encoding: 'utf8',
  });
  assert.equal(run('refs/heads/feat/ACK-12-login').status, 0);
  const invalid = run('refs/heads/feature/ACK-12-login');
  assert.equal(invalid.status, 1);
  assert.match(invalid.stderr, /예시: feat\/ACK-12-user-login/);
});

test('Commitlint accepts Korean, optional scope, uppercase APP, build/perf and squash titles', () => {
  for (const message of [
    'feat(dispatch): 긴급 출동 요청 API 구현',
    'fix(auth): JWT 검증 오류 수정',
    'docs: README 작성',
    'chore: 프로젝트 초기 설정',
    'build: 빌드 설정',
    'perf(dispatch): 응답 시간 개선',
    'feat(BE): JWT 기반 로그인 API 구현 [ACK-12]',
    'fix(DRIVER): 위치 전송 오류 수정 [ACK-35]',
    'chore(INFRA): GitHub Actions 설정 [ACK-42]',
    'docs(ADMIN): README 작성 [ACK-1]',
    'test(AI): 모델 테스트 [ACK-1]',
  ]) {
    const result = spawnSync(process.execPath, [cli], { cwd: root, input: message, encoding: 'utf8' });
    assert.equal(result.status, 0, `${message}\n${result.stdout}${result.stderr}`);
  }
});

test('Commitlint rejects malformed messages, disallowed types and default ignore bypasses', () => {
  for (const message of ['로그인 구현', 'feature: 로그인 구현', 'hotfix: 오류 수정', 'style: 스타일 수정', 'Feat: 로그인 구현', 'feat:', 'feat(BE) 로그인 구현', 'feat(BE):로그인 구현', 'v1.0.0', 'Merge branch main', '']) {
    const result = spawnSync(process.execPath, [cli], { cwd: root, input: message, encoding: 'utf8' });
    assert.notEqual(result.status, 0, `${message} must fail`);
  }
});

test('commit-msg hook reads its message file, including paths with spaces', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'capstone-hook-'));
  const file = path.join(dir, 'commit message.txt');
  try {
    for (const [message, expected] of [['feat(BE): 로그인 구현 [ACK-12]', 0], ['잘못된 메시지', 1]]) {
      writeFileSync(file, message);
      const result = spawnSync('sh', ['.husky/commit-msg', file], { cwd: root, encoding: 'utf8' });
      assert.equal(result.status, expected, `${result.stdout}${result.stderr}`);
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('CI reads PR metadata from event JSON and treats shell syntax as data', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'capstone-event-'));
  const eventPath = path.join(dir, 'event.json');
  try {
    writeFileSync(eventPath, JSON.stringify({ pull_request: {
      head: { ref: 'feat/ACK-12-login' },
      title: 'feat(BE): "$(exit 99)" `exit 99` 로그인 [ACK-12]',
    } }));
    for (const command of ['branch', 'pr-title', 'jira']) {
      const result = spawnSync(process.execPath, ['scripts/git-conventions.cjs', command], {
        cwd: root, encoding: 'utf8', env: { ...process.env, GITHUB_EVENT_PATH: eventPath },
      });
      assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
