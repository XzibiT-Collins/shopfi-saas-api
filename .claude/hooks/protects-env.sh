#!/usr/bin/env bash
# PreToolUse hook on Bash — blocks commands that directly read .env files.
# Uses Node.js for line-by-line parsing with heredoc tracking and quote stripping.

INPUT=$(cat)
TMPINPUT=$(mktemp)
printf '%s' "$INPUT" > "$TMPINPUT"

BLOCKED=$(node -e "
const fs = require('fs');
const input = JSON.parse(fs.readFileSync(process.argv[1], 'utf8'));
const command = input?.tool_input?.command ?? '';

const READ_CMDS = new Set(['cat','head','tail','less','more','grep','awk','sed','nano','vim','vi','source','bat']);
const ENV_PATTERN = /\\.env(\\.[a-zA-Z0-9]+)?(\\s|\$)/;

let blocked = false;
let inHeredoc = false;
let heredocDelim = '';

for (const rawLine of command.split('\n')) {
  const line = rawLine.trim();

  // Skip heredoc content lines
  if (inHeredoc) {
    if (line === heredocDelim) inHeredoc = false;
    continue;
  }

  if (!line || line.startsWith('#')) continue;

  // Detect heredoc start — mark subsequent lines as non-executable content
  const hm = line.match(/<<[-\\s]*[\"']?([A-Za-z_][A-Za-z0-9_]*)[\"']?/);
  if (hm) { heredocDelim = hm[1]; inHeredoc = true; }

  // Strip single- and double-quoted strings to remove false positives
  const stripped = line.replace(/'[^']*'/g, '').replace(/\"[^\"]*\"/g, '');

  // Check each pipeline/chain segment independently
  for (const seg of stripped.split(/[|;&]+/)) {
    const tokens = seg.trim().split(/\\s+/).filter(Boolean);
    // Find the command token (first non-flag, non-variable-reference token)
    const cmdIdx = tokens.findIndex(t => !t.startsWith('-') && !t.startsWith('\$') && !t.startsWith('('));
    if (cmdIdx === -1) continue;

    const cmd = tokens[cmdIdx].replace(/.*\\//, ''); // basename only
    if (!READ_CMDS.has(cmd) && cmd !== '.') continue;

    // Check if any subsequent token looks like a .env file path
    for (let i = cmdIdx + 1; i < tokens.length; i++) {
      if (ENV_PATTERN.test(tokens[i])) { blocked = true; break; }
    }
    if (blocked) break;
  }
  if (blocked) break;
}

process.stdout.write(blocked ? 'deny' : '');
" "$TMPINPUT")

rm -f "$TMPINPUT"

if [ "$BLOCKED" = "deny" ]; then
  printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"Reading .env files is not permitted. Ask the user if you need a specific environment variable value."}}'
fi

exit 0