#!/usr/bin/env bash
# PreToolUse hook — fires before every Write call.
# Checks for similar existing files and duplicate exported symbols.
# Denies the write with findings so Claude must verify necessity first.

INPUT=$(cat)
TMPINPUT=$(mktemp)
TMPRESULT=$(mktemp)
printf '%s' "$INPUT" > "$TMPINPUT"

# ── Parse JSON with Node (no shell calls — just extract fields) ─────────────
node -e "
const fs = require('fs');
const input = JSON.parse(fs.readFileSync(process.argv[1], 'utf8'));
const filePath = input?.tool_input?.file_path ?? '';
const content  = input?.tool_input?.content   ?? '';
const matches  = [...content.matchAll(
  /export\s+(?:const|function|class|type|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]+)/g
)];
const names = [...new Set(matches.map(m => m[1]))].slice(0, 15);
fs.writeFileSync(process.argv[2], filePath + '\n' + names.join('\n'));
" "$TMPINPUT" "$TMPRESULT"

rm -f "$TMPINPUT"

FILE_PATH=$(head -1 "$TMPRESULT")
EXPORT_NAMES=$(tail -n +2 "$TMPRESULT")
rm -f "$TMPRESULT"

# ── Bail early: non-TS or infrastructure files ──────────────────────────────
case "$FILE_PATH" in *.ts|*.tsx) ;; *) exit 0 ;; esac
case "$FILE_PATH" in
  */.claude/*|*/.github/*|*/node_modules/*|*/dist/*|*/coverage/*) exit 0 ;;
esac

SRC_DIR="$CLAUDE_PROJECT_DIR/src"
WARNINGS=""

# ── Similar-named files already in src/ ────────────────────────────────────
BASENAME=$(basename "$FILE_PATH" | sed 's/\.[^.]*$//')
if [ "${#BASENAME}" -gt 4 ]; then
  SIMILAR=$(find "$SRC_DIR" -name "*${BASENAME}*" 2>/dev/null \
    | grep -v "node_modules" | grep -v "^${FILE_PATH}$" | head -6 || true)
  if [ -n "$SIMILAR" ]; then
    LIST=$(printf '%s' "$SIMILAR" | tr '\n' '|' | sed 's/|$//')
    WARNINGS="${WARNINGS}Similar-named files: ${LIST}. "
  fi
fi

# ── Duplicate exported symbols ──────────────────────────────────────────────
DUPS=""
while IFS= read -r name; do
  [ -z "$name" ] && continue
  FOUND=$(grep -rl --include="*.ts" "export.*${name}" "$SRC_DIR" 2>/dev/null \
    | grep -v "^${FILE_PATH}$" | head -3 || true)
  if [ -n "$FOUND" ]; then
    FILES=$(printf '%s' "$FOUND" | tr '\n' ',' | sed 's/,$//')
    DUPS="${DUPS}'${name}' in: ${FILES}; "
  fi
done <<< "$EXPORT_NAMES"
[ -n "$DUPS" ] && WARNINGS="${WARNINGS}Duplicate exports: ${DUPS}"

# ── Output deny JSON if warnings found ─────────────────────────────────────
if [ -n "$WARNINGS" ]; then
  FNAME=$(basename "$FILE_PATH")
  REASON="DUPLICATION CHECK - ${FNAME}: ${WARNINGS}Before creating this file, verify existing code does not already cover this need. Prefer modifying an existing file over creating a new one."
  ESCAPED=$(printf '%s' "$REASON" | sed 's/"/\\"/g' | tr '\n' ' ')
  printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"%s"}}' "$ESCAPED"
fi