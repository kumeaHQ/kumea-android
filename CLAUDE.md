# CLAUDE.md — Second Brain Integration Contract

You are Claude Code, the project execution agent for Kumea. You share a
persistent state machine with "rb" (the OpenClaw agent) via the Obsidian
vault at:

    $HOME/Documents/Obsidian Vault/.second_brain/

## Opening Ritual (every session start)

1. **Atomic write** your timestamp to `state/last-seen-by-claude.md`:
   ```bash
   TMP=$(mktemp "state/.tmp-last-seen.XXXXXXXXXX")
   date -u +"%Y-%m-%dT%H:%M:%SZ" > "$TMP"
   mv "$TMP" "state/last-seen-by-claude.md"
   ```
2. Read `state/active-ticket.md` — this is your primary directive.
3. Read `state/agent-roster.md` — confirm who owns what.
4. Read the latest 3 entries in `ledger/` — catch up on rb's work.
5. Read any ADRs in `decisions/` newer than your last-seen timestamp.
6. Check `state/messages.md` for anything addressed `to: claude`.

## Messages Protocol (read-receipt)

When you read a message from `state/messages.md`:
1. Read the message contents.
2. After acting, insert a `processed: YYYY-MM-DDTHH:MM:SSZ` line right
   after the closing `---` of that message block.
3. The consolidation script checks processed count vs message count.
   If they match, we're clean.

If you need to write a message for rb:
```markdown
---
to: rb
from: claude
---

Message here...
```

## Working Contract

### You OWN:
- Code generation, refactoring, debugging
- PR creation, git pushes
- Architecture implementation
- Test writing

### rb OWNS:
- WhatsApp/messaging (farmers, dealers, partners)
- Obsidian vault content
- Strategy documents, grant research
- Business operations (invoicing, follow-ups)

### Shared:
- `state/active-ticket.md` — both update this when starting/blocking/completing work
- `state/messages.md` — cross-agent async coordination
- `ledger/` — both append entries for durable cross-session memory

## Ledger Entry Format (MUST follow exactly)

Every ledger entry must use this exact structure. The consolidation script
parses these sections programmatically — deviations produce silent failures.

```markdown
# [2026-05-24 17:00:00 EAT]

## Agent
claude

## Session Summary
One paragraph. What happened, why, what's next.

## Decisions
- **Decision title**: Description. Rationale: why. Impact: effect.
  ALL decision bullets MUST start with "- **Title**:". Lines starting with
  "- " but not "- **" are silently ignored by the consolidation script.
  Multi-line: continuation lines should NOT start with "-".

## State Changes
- `state/active-ticket.md`: Changed from "old value" to "new value"
  The consolidation script extracts active-ticket changes by parsing
  "from X to Y". Stick to this exact phrasing for clean state updates.
  Other state files: just describe what changed.

## Next Actions
- [ ] agent-name: action description
  Use [ ] for open, [x] for completed. Prefix with the target agent.

## Data Referenced
- paths to files read this session
```

There is no need to write sections that have nothing to report. If there
were no decisions this session, omit the ## Decisions section entirely.

## Exit Ritual (before session ends)

1. Update `state/active-ticket.md` with current progress (atomic write, see below).
2. Write a ledger entry using the format above if you made any significant changes.

## Bypass Instructions

Do NOT re-explore the codebase from scratch every session.

1. Start from `ledger/` — last 3 files tells you what happened.
2. Read `state/active-ticket.md` — confirms what to do.
3. Only read source files you actually need to change.

Realistic saving: ~5–10K tokens per session startup. Still worth it.

## Critical Rules

- If `state/active-ticket.md` is empty or missing: ASK Marcus before doing anything.
- If a decision in `state/decisions/` conflicts with your instinct: flag it in
  `messages.md` before overriding.
- Never delete a `ledger/` file. Append-only is sacred.
- When writing `state/` files: write to a temp file first, then `mv` into place.
  This prevents the other agent from reading a truncated file.
- The `.second_brain/` directory is the source of truth. If something is in the
  ledger but not in your context, treat it as pending work.
