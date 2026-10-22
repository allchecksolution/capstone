const { COMMIT_TYPES } = require('./scripts/git-conventions.cjs');

module.exports = {
  extends: ['@commitlint/config-conventional'],
  // Validate every commit, including merge/revert/version messages.
  defaultIgnores: false,
  rules: {
    'type-enum': [2, 'always', COMMIT_TYPES],
    'scope-case': [0], // Domain scopes and uppercase APP identifiers are both valid.
    'subject-case': [0], // Allow Korean descriptions and acronyms such as JWT/API.
    'header-max-length': [0], // The team convention specifies no length limit.
  },
};
