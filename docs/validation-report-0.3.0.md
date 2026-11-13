# Anchor 0.3.0 — routines, chat scheduling and partial progress

Prepared 29 October 2026 for HONOR Magic4 Lite 5G (ANY-NX1, Android13/API33).

## What changed

Clear single-task chat requests create real tasks and return a confirmation, a direct timeline link and guarded Undo. Missing duration defaults to 30 minutes and is stated in the confirmation. An omitted date uses the next occurrence of the requested local time. Specified times are protected by default. Conflicts and duplicates do not move existing entries. Repeating chat requests open the routine setup form so you can choose the exact pattern; they never silently become a single task. Complex or ambiguous single-task requests use an editable draft form; raw JSON stays internal, and model suggestions do not directly change the calendar.

Today → Routines & goals supports suggested chores, reading, learning, applications and customer outreach, plus custom names. Choose session minutes, a start date, 1–90 calendar days, selected weekdays and start-time notifications. The 30/60/90 buttons are shortcuts. Preview lists every occurrence before Save. Slots use the existing day window and flexible percentage; all existing placements are protected while adding a routine. No-space days remain visible as untimed flexible tasks with reminder intent preserved. They receive no alarm until they have a time. Sessions remain individually editable. Stop removes upcoming, incomplete, unchanged sessions and preserves past/completed/manual edits. Whole-series changes currently use stop-and-create.

Daily/weekly bars and the 60-day map show each task’s share: green completed, red overdue, grey not yet due. A timed task is overdue after its full duration ends; an untimed task after its day ends. Late completion changes red to green. Events do not count; empty days stay neutral. Colours belong to planned dates; existing activity totals/streaks retain actual completion dates. Foreground progress refreshes about once a minute.

The calendar belongs to Anchor. It does not synchronize with the phone’s other calendar apps or Google Calendar.

## Alarm reliability

Native alarms remain independent of the LLM. The nearest 200 pending reminders occupy system alarm slots; the queue refills after delivery even if automatic planning is off, and on normal calendar/clock/reboot checks. Snoozes use their own due times. This bounds the alarm expansion from multiple 90-day routines. Android’s default alarm service has a per-UID limit of 500; device policies may differ. [AOSP implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/apex/jobscheduler/service/java/com/android/server/alarm/AlarmManagerService.java).

Notifications need Android notification access. Ringing remains a user opt-in and needs exact alarm access. Force stop prevents delivery until the app is reopened; WorkManager timing can be delayed by Android/OEM power management. Scheduling failures are visible in Settings.

## Verification

- 61 JVM tests passed, including 90-day expansion, weekdays, overnight/all-day protection, allocation overload, partial progress, explicit chat date/intent handling and bounded alarm selection.
- 49 physical-device regression tests passed in the final complete run (13.851 seconds), including the real 205-entry bounded queue/refill check and exact bitmap green/red/neutral shares. One additional real-alarm delivery/ringing/snooze/dismiss test passed separately. Total: 50 device cases across the two completed runs.
- A real AlarmManager delivery/ringing/snooze/dismiss check passed after queue changes; its labeled task was removed. The routine builder and a 30-session reading preview were exercised on the phone in landscape. The preview respected an existing 08:00 commitment by placing the next day’s reading at 08:30. It was cancelled; no personal routine was seeded.
- Build and Android lint completed; lint has 38 warnings and 0 errors. Warnings include existing platform/dependency, preference-write, localization and drawing-allocation categories.
- SQLite schema 4 preserves existing tasks/chat/memories across v1/v2/v3 upgrades. Tests use disposable data.
- Independent review found alarm-capacity and chat-intent/date/partial-parsing issues; fixes have focused regressions. More complex model interpretations now use human review in the editable form.

## Limits

This release retains the recovered Java framework UI and SQLite implementation. Kotlin/Compose, Room/DataStore, sherpa-onnx, arbitrary recurring prose, book chapter subdivision and whole-series editing are not implemented. Voice service availability was previously checked, but microphone transcription and audible TTS quality were not tested. No multi-day Doze, reboot/power-loss soak,90-day real-time run, daylight saving device-clock-change run or sustained inference thermal qualification was performed. The app does not request Internet or calendar-provider access. No new model benchmark was needed because the native runtime/model files are unchanged; previous LFM3/LFM2.5/Granite measurements remain in the 0.2 report.

The development APK is for personal testing. Model weights are separate from the APK/source archive. No private chat database, audio or actual memory notes are included in the deliverables.
