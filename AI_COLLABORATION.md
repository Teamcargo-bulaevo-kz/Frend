# AI Collaboration Log

This file is the persistent handoff point for every work session on this repository.

## Roles and decision process

- **User is the product owner** and has final authority over goals and acceptance.
- **Claude is the lead developer**: leads implementation and proposes architectural decisions.
- **ChatGPT is the independent reviewer / technical assistant**: checks regressions, tests, diffs, build results, and documents findings. ChatGPT should not start a competing rewrite.
- The lead developer should consider review findings and explain disagreements with technical evidence. The user decides unresolved trade-offs.

## Non-negotiable project constraints

1. Preserve existing working features, settings, and behavior unless the user explicitly approves a change.
2. Keep fixes narrowly scoped. Do not refactor unrelated code.
3. For the current microphone issue, verify on the target Xiaomi/MIUI device; a successful CI build alone is not proof that the microphone works.
4. The wake-word model must be bundled in the APK. Do not introduce a runtime model download.
5. Do not merge a microphone fix until CI passes, the APK artifact is inspected, and device testing is reported. If device testing is not yet possible, clearly mark it as pending.
6. Record each meaningful session below: date, branch/commit, changes, checks, unresolved risks, and next action. Do not claim a test was performed unless it actually was.

## Session log

### 2026-10-25 — ChatGPT handoff setup

- **Working branch:** `fix/mic-only-preserve-settings`
- **PR:** [#1 — Fix microphone input without changing existing settings](https://github.com/Teamcargo-bulaevo-kz/Frend/pull/1)
- **Commit reviewed:** `3339e4995a6ac29f5b40d11d867b3ee1cad7fbd3`
- **Scope:** `SherpaWakeWordEngine.kt` and the build workflow only; existing `MainActivity.kt` and `WakeWordService.kt` were left unchanged.
- **Build:** GitHub Actions run [#151](https://github.com/Teamcargo-bulaevo-kz/Frend/actions/runs/40196976829) completed successfully.
- **Current diagnosis to verify:** `VOICE_RECOGNITION` with a fixed 8 kHz rate may be incompatible with some Xiaomi/MIUI audio paths; the branch restores `MIC` and removes a strict recording-state check.
- **Not yet verified:** actual microphone / wake-word behavior on the user's Xiaomi device; APK artifact contents and size should be checked before testing.
- **Next action for Claude:** review this handoff and PR, inspect the generated APK/artifact and diff, report any concerns here or in PR #1, and avoid unrelated edits. Do not merge without device-test results or an explicit user decision to accept that limitation.
- **Next action for ChatGPT:** independently inspect new commits, Claude's response, workflow results, and test evidence; append a dated entry here after each substantive session.
