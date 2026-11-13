# Period Planning Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for native execution in this session.

**Goal:** Sort a list into a safe, editable period plan and support explicit single-session rescheduling through chat.
**Architecture:** Pure PeriodPlan preview rules; strict model proposal parser; atomic SQLite batch store with migration5; focused period/move dialogs; existing native reminders and report Undo.
**Tech Stack:** Existing Java Android framework, SQLite, llama.cpp1024-token local context.
**Spec:** docs/superpowers/specs/2026-10-29-period-planning-design.md

## Global Constraints
- Offline, no new dependencies or permissions.
- Maximum12 routine definitions and90 calendar days; exact dates shown before Save.
- No model writes; revision guards, atomic batch persistence, protected commitments and cancellation checks.

## Review Focus
- Multiple routines share both the gap occupancy and the flexible budget.
- Existing data survives v4→v5, plus older supported migrations.
- Stale/repeated batch saves and later edits cannot duplicate or be overwritten by Undo.
- Multiple matching reading sessions require explicit selection.
- Model truncation/malformed proposals never become visible JSON or false success.

###1. Period rules and batch storage
- [x] Write and run failing PeriodPlan tests:61-day inclusive range, ordering, overlap/budget and safe empty/invalid list handling.
- [x] Add category migration, atomic batch save, durable summary/guarded Undo; disposable-device storage/stale/rollback checks passed. Corrected v4 migration fixture rerun is pending.
###2. Chat tools and UI
- [x] Test compact proposal parser and explicit move request parsing.
- [x] Add editable plan composer/preview/history, named starter suggestions and date-range shortcut.
- [x] Add model list extraction with deterministic editable fallback, and move review/report integration.
###3. Verification and delivery
- [x] Run computer checks:78 JVM cases, app/test builds, lint0errors/53warnings. Earlier phone regression60passes/1fixtureerror recorded.
- [x] Independent focused review and necessary regression fixes; reviewer confirmed fixes.
- [x] Package0.4.0 and record limitations; earlier releases preserved. Earlier0.4 installed; final APK awaits installation.
- [ ] Device follow-up: capture cause of model test process crash, rerun corrected migration/full regression and check final UI/model flow. USB disconnected; user explicitly requested finishing computer checks for now.
