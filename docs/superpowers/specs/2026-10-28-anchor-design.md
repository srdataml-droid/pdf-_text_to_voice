# Anchor: first useful Android release

The user needs an offline personal agent on an HONOR Magic4 Lite 5G. The first priority is a real local calendar, daily task timeline, completion tracking and a consistency heatmap, followed by measured feasibility of Granite 4.1 3B Q4. Voice and private explicit memory must never silently use a cloud service.

## Product decisions (builder choices)

- Native Android with Java, framework views and SQLite. Minimum Android 8/API26, compile/target36. No account, Google Calendar, analytics, network permission or server dependency. A small APK and independent planner matter more than a cross-platform codebase.
- Working name Anchor. Pale slate background #F5F6FC, white surfaces #FFFFFF, ink #202742, indigo #4D54B8, muted #68728C, completed teal #25766C. System sans for fast, accessible Android text; 48dp minimum tap targets. Left-aligned daily timeline is the distinctive element. Five simple destinations: Today, Calendar, Chat, Progress, Settings.
- No seed data masquerading as the user's history. First launch has an honest empty state and an Add task button. Local date/time is used and saved explicitly; week starts Monday. Items have a date, optional start time, duration, task/event type, optional reminder, notes and completion state. Events do not inflate task completion counts. Undoing a check removes its completion contribution. Completion heatmap records the actual local completion day.
- Explicit memory notes are locally stored; user can add/delete them. Chat history is local and can be cleared. Sending a prompt includes bounded recent chat and opted-in memory; generated text cannot execute actions. Planner helper commands are explicitly identified as local tools when no language model is loaded.
- llama.cpp CPU JNI, arm64-v8a; model is imported with Android's file picker and copied into app-private files. Never load on startup. Show physical/available RAM separately, file size, context limit, model state. Stop/unload controls. Short context (1024 tokens initially), two CPU threads, bounded output. Import has disk-space and GGUF checks. No model feasibility claim before device measurements.
- Benchmark records load time, first-token delay, generation tokens/s, process PSS, available RAM, thermal state and airplane-mode status. Manual airplane mode, not airplane settings manipulation. A real model load/generation is required. A successful airplane-mode run is evidence for that run, not a universal claim. Export a report on explicit user action.
- Local STT only via Android's on-device recognizer when API31+ and available; unsupported phones get a clear capability explanation, typing remains available. TTS selects a voice that does not require network. Vosk/whisper are evaluated follow-ups if no OS recognizer is available, rather than silently invoking online speech.
- Native AlarmManager reminders, notification permission on Android13+, optional exact-alarm access. Inexact fallback is labeled; boot/time/timezone and edits/completion/deletion reconcile alarms. Notification content is generic on the lock screen. Force-stopped apps cannot deliver until reopened. No cloud/device backup of private data. Device file encryption/sandbox provides storage isolation; no claim of app-level database encryption.

## Acceptance and limits

Build a signed development APK, run JVM logic tests and Android persistence/reminder tests if a device or emulator is available, exercise real UI CRUD/check/undo/calendar/memory/history, verify restart persistence and airplane-mode planner behavior. Model benchmark on the HONOR requires an authorized USB connection and a model file. Build/test unavailability must be recorded plainly; desktop timing must never be presented as phone performance. First release is developer-signed, not Play published; the model is a separate download.

## File boundaries

`Task.java`, `Memory.java`, `ChatMessage.java`, `DateRules.java`: domain contract. `AnchorDb.java`: SQLite repository. `Reminders.java`, `ReminderReceiver.java`: notifications/lifecycle. `MainActivity.java`, `HeatmapView.java`: real native UI. `LocalAi.java`, `NativeLlama.java`, `VoiceController.java`, `cpp/`: AI import/inference/benchmarks and speech. `PlannerTest.java`, `PlannerDeviceTest.java`: focused behavioral verification.

## Shared Java interface

Package `dev.anchor.app`. Task has public fields `long id, updatedAt; String title,date,time,notes,completedDate; int durationMinutes,reminderMinutes; boolean done,event;` default time/notes/completedDate empty, duration30, reminder-1. `new Task()` is allowed. Memory has `long id,createdAt; String text;`. ChatMessage has `long id,createdAt; String role,content;`.

AnchorDb(Context) extends SQLiteOpenHelper: `List<Task> tasks(String date)`, `List<Task> range(String start,String end)`, `Task task(long id)`, `long save(Task t)`, `void delete(long id)`, `void complete(long id,boolean done)`, `List<Memory> memories()`, `long remember(String text)`, `void forget(long id)`, `List<ChatMessage> messages()`, `void message(String role,String content)`, `void clearMessages()`, `Map<String,Integer> completionCounts(String start,String end)`.

DateRules: `String today()`, `String date(LocalDate date)`, `void validate(Task task)`, `long reminderAt(Task task,ZoneId zone)`, `int streak(Map<String,Integer> counts,LocalDate today)`; validation throws IllegalArgumentException. Reminders: static `void reconcile(Context,AnchorDb)`, `void cancel(Context,long id)`, `String status(Context)`, `void requestAccess(Activity)`.
