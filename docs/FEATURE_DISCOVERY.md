# Feature Discovery and Prioritization

Use this playbook during each substantial LabelSheet change. This is a research process, not a live link to paid services or permission to send user data elsewhere.

## Evidence sources
1. Existing workflows and user reports in GitHub issues and previous decisions, especially errors and pain points.
2. Familiar editing behaviors from comparable label/layout/printing apps; inspect Mobbin and app documentation when authorized and available.
3. Material Design, platform accessibility and Android app-quality guidance.
4. Optional user feedback board (such as Canny), usability testing (such as Maze), or anonymized opt-in product analytics (such as PostHog) **only** after the owner connects and approves them.

## Every upgrade
- Inventory current features, including crop, print, PDF, printer wake, multi-select, alignment, formats, undo/redo, aspect and physical dimensions.
- Write 3-5 potentially useful improvements that the user did **not** explicitly request.
- For each ask: What concrete user problem does it solve? How frequent/severe? Is there evidence? Can current controls already solve it? What would it cost? What are the privacy/performance/accessibility risks?
- Rank 1-5 each on usefulness, confidence, implementation effort, regression risk and storage/permissions burden. Prefer high-value low-risk changes.
- Present two strongest suggestions and at least one idea to defer, with reasons. Never invent user feedback or claim testing you did not perform.
- Obtain explicit approval before new behavior that adds third-party tracking, uploads label images, permissions, subscriptions, background network usage, or changes stored files.
- Implement accepted changes on a branch, run build/tests and capture a verified release note.
- Treat analytics as opt-in and do not capture screenshots, label contents or printer addresses.

## Potential ideas to evaluate (not automatically implement)
- Bulk editing selected labels (border, rotation, exact size, copied format; locked labels skipped).
- Editable multi-picture style presets.
- Repeat last edit / match selected item style.
- Cut-line and safe-margin guides hidden from print.
- Automatic layout using consistent gaps and actual printed dimensions.
- Save/load named template layouts (without embedding full source images).
- Accessible tool palette and easily scrolled controls on landscape phones.
- Print preview showing clipping/out-of-bounds warnings.

## Decision log
| Date | Candidate | User problem/evidence | Value | Effort | Risk | Decision | Verified |
|---|---|---|---|---|---|---|---|
| 2026-10-11 | Bulk multi-picture editing | Selecting multiple labels but editing styles only singly | High | Moderate | Medium | Implement on feature branch | Build/device review pending |
| 2026-10-11 | Scrollable landscape tool rail | Fixed short screen can truncate tools | High | Low | Low | Implement on feature branch | Build/device review pending |
