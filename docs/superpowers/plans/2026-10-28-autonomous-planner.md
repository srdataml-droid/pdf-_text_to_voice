# Anchor scheduling and alarms continuation

**Goal:** Extend the existing offline Android app into a reliable deterministic scheduling MVP.
**Architecture:** Preserve native Java/SQLite/JNI app and stored data. Add protected/flexible task fields, transactional rescheduling reports and revision-checked Undo. AlarmManager owns delivery; WorkManager owns deferrable observation. The language model cannot mutate the calendar.
**Authority:** User explicitly requests implementation against detailed requirements and continuing existing Work app. No further design gate needed.

## Tasks
- [x] Scheduler: test gap placement, protected commitments, active tasks, budget overload, previous-day crossings, deterministic ordering. Implement SQLite v1 migration, atomic apply/report/Undo with stale guards.
- [x] Alarms: revision/token guards, bounded foreground ringing, permission-aware fallback, snooze/dismiss, boot/time reconciliation. Test lifecycle and stale actions.
- [x] Integration: editable day window/flexible ratio/automatic switch, manual replan, readable reports and Undo, event observation/periodic worker, 60-day challenge. Preserve text/chat/memory/offline speech.
- [x] Models: verify official candidate artifacts and pinned runtime support; provide repeatable Android benchmark and run when actual device available.
- [x] Validation: compile APK/tests/lint, run JVM/device tests, inspect final merged permissions, fresh review, deliver APK/source/checks with explicit device gaps.

## Decisions and limits
Existing Java framework UI and SQLite are retained rather than rewriting in Kotlin/Compose/Room during reliability work. A future migration can preserve schema. WorkManager minimum periodic15min and OS delay mean no always-on inference or timing guarantee. Fixed tasks never move automatically. All changes stay local; no INTERNET or calendar permissions. Alarm ringing depends on notification and exact-alarm access. Never label host execution as phone benchmarking.
