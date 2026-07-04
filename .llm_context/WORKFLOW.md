# AI Agent Workflow

## Folder Structure

```text
.llm_context/
├── architecture/       ← Read-only. Architecture, constraints, API contracts, use cases.
├── skills/             ← Read-only. Code patterns and instructions.
│   ├── SKILLS_README.md
│   └── ...
├── tasks/              ← Active and completed task definitions.
│   ├── 01_core_models.md
│   ├── 02_fake_repository.md
│   └── ...
├── STATUS.md           ← Single source of truth. Read this FIRST every session.
├── WORKFLOW.md         ← This file.
└── .cache/             ← Optional. Drafts pending user review. Clean up after acceptance.
```

## Standard Procedure (Every Session)

### 1. Read Context
1. Read `STATUS.md`.
2. Note the active task name.
3. Open only the files listed in that task's "Read first" section.
4. Do not scan or read other files unless the task explicitly requires it.

### 2. Execute
- Before writing any code, look in `.llm_context/skills/` and read the `SKILL.md` file that matches the layer you are working on (e.g., read `kmp-data-layer/SKILL.md` if working on a repository).
- Do one task at a time.
- Never start the next task automatically.
- Write clean, self-documenting code.
- No inline comments (`//`). KDoc allowed only for public API signatures when strictly necessary.
- Minimal Gradle changes: touch only the exact entry required.
- If anything is ambiguous — stop and ask.

### 3. Complex Changes (Optional Draft Step)
If the task involves widespread changes across multiple modules:
1. Write proposed changes as drafts into `.llm_context/.cache/`.
2. Stop and ask the user to review.
3. Apply only after user approval.
4. Clean up `.cache/` after acceptance.

### 4. Wrap Up
After completing a task:
1. List all changed files.
2. Mark the task file as `Status: done`.
3. Update `STATUS.md`: move the task to Completed, update Active Task.
4. Ask: "Task X is complete. Should I proceed to Task Y?"
5. Do not proceed until the user confirms.

## Task File Format

```md
# <N>. <Task Name>

Status: active | done
Depends on: <file references if any>

Goal:
One sentence describing what this task produces.

Read first:
- .llm_context/architecture/<file>
- .llm_context/skills/<file>

Constraints:
- <list of hard rules>

Definition of done:
- <checkable outcomes>
```

## Keeping Task Files After Completion
Keep completed task files in `tasks/` with `Status: done`. They serve as a history log
and provide context if a future task revisits the same area. Never delete them.

## Information Gathering

When facing unresolved references, unfamiliar library APIs, or build failures that persist beyond two attempts:

1. **Primary**: Use `duckduckgo_search` to find official documentation (e.g., "Ktor 3.5 SSE server-sent events", "Kotlin Gradle version catalog TOML accessor dots vs camelCase").
2. **Fallback**: Use `webfetch` on a specific documentation page URL if search results are insufficient.
3. **Never**: Guess API signatures, module coordinates, or accessor names. Each incorrect guess wastes a full build cycle and context.
