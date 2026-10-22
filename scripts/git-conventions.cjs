const {readFileSync} = require('node:fs');
const {execFileSync} = require('node:child_process');

// Shared by local hooks, CI, and Commitlint. Keep policy values here.
const TYPES = ['feat', 'fix', 'refactor', 'docs', 'test', 'chore'];
const COMMIT_TYPES = [...TYPES, 'build', 'perf'];
const APPS = ['BE', 'ADMIN', 'DRIVER', 'AI', 'INFRA', 'MULTI', 'COMMON'];
const ISSUE_PATTERN = 'ACK-[0-9]+';
const branchPattern = new RegExp(`^(${TYPES.join('|')})/(${ISSUE_PATTERN})-[a-z0-9]+(?:-[a-z0-9]+)*$`);
const issuePattern = new RegExp(`^${ISSUE_PATTERN}$`);
const branchHelp = `형식: <type>/<ACK-숫자>-<description> (main 허용)
type: ${TYPES.join(', ')}
description: 소문자 영문·숫자, 단어 사이는 하이픈
예시: feat/ACK-12-user-login, fix/ACK-35-dispatch-error, chore/ACK-42-project-initial-setup`;
const titleHelp = `형식: <type>(<APP>): <description> [<ACK-숫자>]
type: ${TYPES.join(', ')}
APP: ${APPS.join(', ')}
예시: feat(BE): JWT 기반 로그인 API 구현 [ACK-12]`;

function validateBranch(branch) {
    if (branch === 'main') return {issue: null};
    const match = typeof branch === 'string' && !/[\r\n]/.test(branch) && branch.match(branchPattern);
    if (!match) throw new Error(`잘못된 브랜치명: ${JSON.stringify(branch)}\n${branchHelp}`);
    return {issue: match[2]};
}

function validateTitle(title) {
    const fail = (reason) => {
        throw new Error(`잘못된 PR 제목: ${JSON.stringify(title)}\n원인: ${reason}\n${titleHelp}`);
    };
    if (typeof title !== 'string' || /[\r\n]/.test(title)) fail('제목은 한 줄이어야 합니다.');
    const match = title.match(/^([^()\s]+)\(([^()\s]+)\): (.*) \[([^\]\r\n]+)\]$/u);
    if (!match) fail('type(APP), 콜론 뒤 공백, description, 마지막 [ACK-숫자]를 확인하세요.');
    const [, type, app, description, issue] = match;
    if (!TYPES.includes(type)) fail(`허용되지 않는 type: ${type}`);
    if (!APPS.includes(app)) fail(`허용되지 않는 APP: ${app} (대문자 사용)`);
    if (!description.trim() || description !== description.trim()) fail('description은 비어 있거나 앞뒤 공백을 포함할 수 없습니다.');
    if (!issuePattern.test(issue)) fail(`잘못된 Jira Issue Key: ${issue} (ACK-숫자 필요)`);
    return {issue};
}

function validateIssueMatch(branch, title) {
    const {issue: branchIssue} = validateBranch(branch);
    const {issue: titleIssue} = validateTitle(title);
    // main is explicitly exempt from branch naming and has no issue key to compare.
    if (branchIssue === null) return 'main 브랜치는 Jira Issue Key 비교 대상에서 제외합니다.';
    if (branchIssue !== titleIssue) {
        throw new Error(`Jira Issue Key 불일치: 브랜치=${branchIssue}, PR 제목=${titleIssue}\n브랜치와 PR 제목에 같은 키를 사용하세요.\n예시: feat/ACK-12-user-login → feat(BE): 로그인 구현 [ACK-12]`);
    }
    return `Jira Issue Key 일치: ${branchIssue}`;
}

function validatePush(input) {
    const errors = [];
    for (const line of input.split(/\r?\n/).filter((line) => line.trim())) {
        const fields = line.trim().split(/\s+/);
        if (fields.length !== 4) throw new Error('pre-push ref 입력은 <local-ref> <local-sha> <remote-ref> <remote-sha> 형식이어야 합니다.');
        const [, localSha, remoteRef] = fields;
        // Allow deleting old invalid branches and pushing tags. Validate the destination,
        // including HEAD:branch, SHA:branch, renamed destinations, and multiple refspecs.
        if (/^0+$/.test(localSha) || !remoteRef.startsWith('refs/heads/')) continue;
        try {
            validateBranch(remoteRef.slice('refs/heads/'.length));
        } catch (error) {
            errors.push(error.message);
        }
    }
    if (errors.length) throw new Error(errors.join('\n\n'));
}

function main() {
    const [command, ...args] = process.argv.slice(2);
    if (command === 'pre-push') {
        validatePush(readFileSync(0, 'utf8'));
        console.log('Push 대상 브랜치명 검사 통과');
        return;
    }
    // Read PR data as JSON, never interpolate untrusted metadata into shell code.
    const pr = process.env.GITHUB_EVENT_PATH
        ? JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8')).pull_request
        : undefined;
    if (command === 'branch') {
        validateBranch(args[0] ?? pr?.head?.ref ?? execFileSync('git', ['branch', '--show-current'], {encoding: 'utf8'}).trim());
    } else if (command === 'pr-title') {
        validateTitle(args[0] ?? pr?.title);
    } else if (command === 'jira') {
        console.log(validateIssueMatch(args[0] ?? pr?.head?.ref, args[1] ?? pr?.title));
    } else {
        throw new Error('사용법: node scripts/git-conventions.cjs <branch|pre-push|pr-title|jira> [브랜치 또는 제목]');
    }
    console.log(`${command} 검사 통과`);
}

module.exports = {TYPES, COMMIT_TYPES, APPS, validateBranch, validateTitle, validateIssueMatch, validatePush};

if (require.main === module) {
    try {
        main();
    } catch (error) {
        console.error(error.message);
        process.exitCode = 1;
    }
}
