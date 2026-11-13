package dev.anchor.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateFormat;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import dev.anchor.app.LocalAi.ReasoningMode;
import dev.anchor.app.LearningChunk;
import dev.anchor.app.LearningConcept;
import dev.anchor.app.LearningExtractor;
import dev.anchor.app.LearningScheduler;
import dev.anchor.app.LearningSession;
import dev.anchor.app.LearningSource;
import dev.anchor.app.LearningQuestion;
import dev.anchor.app.LearningAnswer;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Framework-only, offline planner UI. Every task, note and message is backed by SQLite. */
public final class MainActivity extends Activity implements LocalAi.Listener {
    private static final int BG = 0xfff5f6fc, WHITE = 0xffffffff, INK = 0xff202742,
            MUTED = 0xff68728c, ACCENT = 0xff4d54b8, TEAL = 0xff25766c, LINE = 0xffe3e6f0;
    private static final String[] TABS = {"Today", "Calendar", "Chat", "Learning", "Progress", "Settings"};
    private Typeface fontRegular, fontBold;
    private AnchorDb db;
    private LocalAi ai;
    private LinearLayout root, body, navigation;
    private LocalDate selectedDate = LocalDate.now();
    private LocalDate lastVisibleToday = LocalDate.now();
    private YearMonth calendarMonth = YearMonth.now();
    private int tab;
    private TextView aiStatus;
    private EditText chatInput;
    private String draft = "", status = "", lastReply = "";
    private boolean sending;
    private final android.os.Handler progressClock=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshProgress=new Runnable(){@Override public void run(){if(tab==0||tab==3)render();progressClock.postDelayed(this,60_000);}};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        fontRegular = getResources().getFont(R.font.anchor_sans);
        fontBold = Typeface.create(fontRegular, Typeface.BOLD);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (state != null) {
            try { selectedDate = LocalDate.parse(state.getString("date", LocalDate.now().toString())); } catch (RuntimeException ignored) { }
            try { calendarMonth = YearMonth.parse(state.getString("month", YearMonth.now().toString())); } catch (RuntimeException ignored) { }
            tab = state.getInt("tab", 0);
            draft = state.getString("draft", "");
            try { lastVisibleToday = LocalDate.parse(state.getString("lastToday", LocalDate.now().toString())); } catch (RuntimeException ignored) { }
        }
        selectNotificationDate(getIntent());
        db = new AnchorDb(this);
        ai = new LocalAi(this, db, this);
        status = ai.status();
        root = column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                root.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            } else {
                root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout brand = row(); brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(dp(22), dp(10), dp(22), dp(7));
        TextView wordmark = text("anchor", 24, INK); wordmark.setTypeface(fontBold);
        wordmark.setLetterSpacing(-.04f);
        brand.addView(wordmark, new LinearLayout.LayoutParams(0, dp(40), 1));
        TextView local = text("ON DEVICE", 10, TEAL); local.setLetterSpacing(.1f);
        local.setGravity(Gravity.CENTER); local.setPadding(dp(10), dp(5), dp(10), dp(5));
        local.setBackground(shape(0xffe6f1ee, 20, 0));
        brand.addView(local);
        root.addView(brand);
        body = column(); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        navigation = row(); navigation.setBackgroundColor(WHITE);
        navigation.setPadding(dp(5), dp(5), dp(5), dp(4));
        root.addView(navigation, new LinearLayout.LayoutParams(-1, dp(65)));
        setContentView(root);
        root.requestApplyInsets();
        PlannerObserver.schedule(this);
        PlannerObserver.observe(this, db, "App opened");
        render();
        if (getIntent().getBooleanExtra("show_reports", false)) { getIntent().removeExtra("show_reports"); showReports(); }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        captureDraft();
        out.putString("date", selectedDate.toString()); out.putString("month", calendarMonth.toString());
        out.putString("draft", draft); out.putInt("tab", tab);
        out.putString("lastToday", lastVisibleToday.toString());
        super.onSaveInstanceState(out);
    }

    private void captureDraft() { if (chatInput != null) draft = chatInput.getText().toString(); }

    private void render() {
        captureDraft(); chatInput = null; aiStatus = null;
        body.removeAllViews(); renderNavigation();
        switch (tab) {
            case 1: renderCalendar(); break;
            case 2: renderChat(); break;
            case 3: renderLearning(); break;
            case 4: renderProgress(); break;
            case 5: renderSettings(); break;
            default: renderToday();
        }
    }

    private void switchTab(int next) { tab = next; render(); }

    private void selectNotificationDate(Intent intent) {
        if (intent == null || !intent.hasExtra("date")) return;
        try {
            selectedDate = LocalDate.parse(intent.getStringExtra("date"));
            calendarMonth = YearMonth.from(selectedDate);
            lastVisibleToday = LocalDate.now();
            tab = 0;
            intent.removeExtra("date");
        } catch (RuntimeException ignored) { /* Ignore malformed external deep links. */ }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        selectNotificationDate(intent);
        render();
        if (intent.getBooleanExtra("show_reports", false)) { intent.removeExtra("show_reports"); showReports(); }
    }

    private void renderNavigation() {
        navigation.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            final int destination = i;
            LinearLayout item = column(); item.setGravity(Gravity.CENTER);
            item.setBackground(ripple(i == tab ? 0xffeef0fc : WHITE, 13));
            item.setContentDescription(TABS[i] + (i == tab ? ", selected" : ""));
            item.setFocusable(true); item.setOnClickListener(v -> switchTab(destination));
            NavIcon icon = new NavIcon(this, i, i == tab ? ACCENT : MUTED);
            item.addView(icon, new LinearLayout.LayoutParams(dp(23), dp(23)));
            TextView label = text(TABS[i], 10.5f, i == tab ? ACCENT : MUTED);
            label.setGravity(Gravity.CENTER); label.setPadding(0, dp(3), 0, 0);
            label.setTypeface(i == tab ? fontBold : fontRegular);
            item.addView(label);
            navigation.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
    }

    private LinearLayout scrollPage() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout page = column(); page.setPadding(dp(22), dp(10), dp(22), dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        body.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        return page;
    }

    private void pageTitle(LinearLayout parent, String eyebrow, String title, String description) {
        TextView top = text(eyebrow.toUpperCase(Locale.getDefault()), 10, ACCENT);
        top.setLetterSpacing(.13f); top.setTypeface(fontBold); parent.addView(top);
        TextView heading = text(title, 29, INK); heading.setTypeface(fontBold);
        heading.setPadding(0, dp(5), 0, dp(7)); parent.addView(heading);
        if (!description.isEmpty()) { TextView copy = text(description, 14, MUTED); copy.setLineSpacing(dp(2), 1); parent.addView(copy); }
        space(parent, 16);
    }

    private void renderToday() {
        LinearLayout page = scrollPage();
        boolean today = selectedDate.equals(LocalDate.now());
        pageTitle(page, today ? "A little structure. More room." : "Your daily plan",
                today ? "Today, at your pace." : selectedDate.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.getDefault()), "");
        LinearLayout dateRow = row(); dateRow.setGravity(Gravity.CENTER_VERTICAL);
        dateRow.addView(smallButton("‹", "Previous day", v -> changeDate(-1)), new LinearLayout.LayoutParams(dp(48), dp(48)));
        Button date = button(selectedDate.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")), false, v -> pickDate(selectedDate, picked -> { selectedDate = picked; calendarMonth = YearMonth.from(picked); render(); }));
        date.setTextSize(13); dateRow.addView(date, new LinearLayout.LayoutParams(0, dp(48), 1));
        dateRow.addView(smallButton("›", "Next day", v -> changeDate(1)), new LinearLayout.LayoutParams(dp(48), dp(48)));
        page.addView(dateRow);
        if (!today) page.addView(button("Back to today", false, v -> { selectedDate = LocalDate.now(); calendarMonth = YearMonth.now(); render(); }));
        space(page, 14);

        LinearLayout aiQuick = row();
        aiQuick.addView(button("AI Chat", false, v -> { tab = 2; render(); }), new LinearLayout.LayoutParams(0, dp(44), 1));
        final Button[] voiceBtn = new Button[1];
        voiceBtn[0] = button("Voice", false, v -> {
            if (ai == null) return;
            ai.listen(transcript -> {
                // Create a quick task from voice input
                Task t = new Task(); t.title = transcript; t.date = selectedDate.toString();
                t.durationMinutes = 30; t.flexible = true; t.reminderMinutes = -1;
                db.save(t); Reminders.reconcile(MainActivity.this, db);
                render(); toast("Task created: " + transcript);
            }, error -> toast("Voice: " + error), new VoiceController.ListeningCallback() {
                @Override public void onListeningStart() { voiceBtn[0].setText("● Listening…"); voiceBtn[0].setBackgroundColor(0xffe05252); }
                @Override public void onPartialResult(String partial) { voiceBtn[0].setText("● " + partial); }
                @Override public void onListeningEnd() { voiceBtn[0].setText("Voice"); voiceBtn[0].setBackground(null); }
                @Override public void onError(String error) { voiceBtn[0].setText("Voice"); voiceBtn[0].setBackground(null); }
            });
        });
        voiceBtn[0].setTextSize(11);
        aiQuick.addView(voiceBtn[0], new LinearLayout.LayoutParams(0, dp(44), 1));
        page.addView(aiQuick);
        space(page, 10);

        List<Task> tasks = db.tasks(selectedDate.toString());
        Map<Long, String> moveNotes = loadMoveNotes(selectedDate);
        int planned = 0, done = 0, events = 0;
        for (Task task : tasks) { if (task.event) events++; else { planned++; if (task.done) done++; } }
        LinearLayout summary = card();
        LinearLayout summaryTop = row();
        TextView label = text("DAILY FOCUS", 10, MUTED); label.setLetterSpacing(.1f);
        summaryTop.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        summaryTop.addView(text(done + " / " + planned + " tasks", 13, TEAL)); summary.addView(summaryTop);
        space(summary, 8);
        Task focus = null;
        for (Task candidate : tasks) if (!candidate.event && !candidate.done) { focus = candidate; break; }
        String focusText = focus != null ? focus.title : (planned == 0 ? "Make space for what matters." : "All planned tasks complete.");
        TextView summaryTitle = text(focusText, 19, INK);
        summaryTitle.setTypeface(fontBold); summary.addView(summaryTitle);
        space(summary, 8);
        DailyProgress.Day outcome = DailyProgress.forDay(tasks, selectedDate, java.time.LocalDateTime.now());
        summary.addView(new DayProgressView(this, outcome), new LinearLayout.LayoutParams(-1, dp(10)));
        space(summary, 9);
        summary.addView(text(outcome.completed + " done · " + outcome.missed + " missed · " + outcome.pending + " not yet due", 12, MUTED));
        summary.addView(text(events + (events == 1 ? " event" : " events") + " · Check a task when you complete it", 12, MUTED));
        page.addView(summary);
        space(page, 12);
        LinearLayout planning = row();
        planning.addView(button("Replan today", false, v -> replanToday()), new LinearLayout.LayoutParams(0, dp(48), 1));
        planning.addView(button("Change reports", false, v -> showReports()), new LinearLayout.LayoutParams(0, dp(48), 1));
        page.addView(planning);
        page.addView(button("Goals timeline", false, v -> new GoalsUi(this, db, this::openDay).show()));
        page.addView(button("Routines & goals", false, v -> showRoutines()));
        page.addView(button("Period plans · daily & challenges", false, v -> new PeriodUi(this, db, this::openDay).history()));
        space(page, 10);
        renderSectionedTimeline(page, tasks, moveNotes, today);
    }

    private Map<Long, String> loadMoveNotes(LocalDate date) {
        Map<Long, String> notes = new HashMap<>();
        List<ScheduleChange> reports = db.changeReports();
        for (ScheduleChange report : reports) {
            if (!report.date.equals(date.toString())) continue;
            for (Scheduler.Move move : report.moves) {
                notes.put(move.taskId, "Moved: " + (move.fromTime.isEmpty() ? "Unscheduled" : move.fromTime) + " → " + move.toTime + " (" + report.reason + ")");
            }
        }
        return notes;
    }

    private void renderSectionedTimeline(LinearLayout page, List<Task> tasks, Map<Long, String> moveNotes, boolean today) {
        LocalDateTime now = LocalDateTime.now();
        List<Task> current = new ArrayList<>();
        List<Task> upcoming = new ArrayList<>();
        List<Task> overdue = new ArrayList<>();
        List<Task> completed = new ArrayList<>();
        List<Task> events = new ArrayList<>();
        List<Task> unscheduled = new ArrayList<>();

        for (Task task : tasks) {
            if (task.event) { events.add(task); continue; }
            if (task.done) { completed.add(task); continue; }
            if (task.time.isEmpty()) { unscheduled.add(task); continue; }
            LocalTime taskTime = LocalTime.parse(task.time);
            LocalDateTime taskStart = LocalDate.parse(task.date).atTime(taskTime);
            LocalDateTime taskEnd = taskStart.plusMinutes(task.durationMinutes);
            if (today && !taskStart.isAfter(now) && taskEnd.isAfter(now)) {
                current.add(task);
            } else if (today && taskStart.isAfter(now)) {
                upcoming.add(task);
            } else if (today && taskEnd.isBefore(now)) {
                overdue.add(task);
            } else if (!today && LocalDate.parse(task.date).isBefore(LocalDate.now())) {
                overdue.add(task);
            } else {
                upcoming.add(task);
            }
        }

        current.sort(Comparator.comparing((Task t) -> t.time).thenComparingLong(t -> t.id));
        upcoming.sort(Comparator.comparing((Task t) -> t.time).thenComparingLong(t -> t.id));
        overdue.sort(Comparator.comparing((Task t) -> t.time).thenComparingLong(t -> t.id));
        completed.sort(Comparator.comparing((Task t) -> t.time.isEmpty() ? "24:00" : t.time).thenComparingLong(t -> t.id));

        if (!current.isEmpty()) {
            page.addView(sectionHeader("Right now", ACCENT));
            renderTaskSection(page, current, moveNotes, false, true);
        }
        if (!upcoming.isEmpty()) {
            page.addView(sectionHeader("Upcoming", TEAL));
            renderTaskSection(page, upcoming, moveNotes, false, false);
        }
        if (!overdue.isEmpty()) {
            page.addView(sectionHeader("Overdue", 0xffe05252));
            renderTaskSection(page, overdue, moveNotes, true, false);
        }
        if (!events.isEmpty()) {
            page.addView(sectionHeader("Events", MUTED));
            renderTaskSection(page, events, moveNotes, false, false);
        }
        if (!unscheduled.isEmpty()) {
            page.addView(sectionHeader("Unscheduled · Needs room", MUTED));
            renderTaskSection(page, unscheduled, moveNotes, false, false);
        }
        if (!completed.isEmpty()) {
            page.addView(sectionHeader("Completed", TEAL));
            renderTaskSection(page, completed, moveNotes, false, false);
        }
        if (current.isEmpty() && upcoming.isEmpty() && overdue.isEmpty() && events.isEmpty() && unscheduled.isEmpty() && completed.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("A clear day.", 19, INK)); space(empty, 7);
            empty.addView(text("Add a task or event when you're ready. Your plan stays on this device.", 14, MUTED));
            space(empty, 13); empty.addView(button("Add your first item", true, v -> editTask(null)));
            page.addView(empty);
        }
    }

    private LinearLayout sectionHeader(String title, int color) {
        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(8), 0, dp(4));
        TextView label = text(title.toUpperCase(Locale.getDefault()), 11, color);
        label.setLetterSpacing(.1f); label.setTypeface(fontBold);
        header.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        View line = new View(this); line.setBackgroundColor(color);
        header.addView(line, new LinearLayout.LayoutParams(0, dp(2), 1));
        return header;
    }

    private void renderTaskSection(LinearLayout page, List<Task> tasks, Map<Long, String> moveNotes, boolean overdue, boolean current) {
        for (Task task : tasks) {
            LinearLayout timeline = row(); timeline.setGravity(Gravity.TOP);
            LinearLayout time = column(); time.setPadding(0, dp(17), dp(7), 0);
            int timeColor = overdue ? 0xffe05252 : (current ? ACCENT : MUTED);
            TextView hour = text(task.time.isEmpty() ? "Anytime" : displayTime(task.time), task.time.isEmpty() ? 10 : 11, timeColor);
            hour.setGravity(Gravity.CENTER); time.addView(hour);
            int dotColor = task.done ? TEAL : (overdue ? 0xffe05252 : (current ? ACCENT : (task.flexible ? 0xff8194c8 : ACCENT)));
            TextView dot = text("•", 21, dotColor); dot.setGravity(Gravity.CENTER); time.addView(dot);
            timeline.addView(time, new LinearLayout.LayoutParams(dp(57), -2));
            LinearLayout taskCard = card(); taskCard.setPadding(dp(13), dp(11), dp(7), dp(11));
            int cardBg = task.done ? 0xffedf5f2 : (overdue ? 0xfffdeaea : WHITE);
            taskCard.setBackground(ripple(cardBg, 15));
            taskCard.setFocusable(true); taskCard.setContentDescription("Edit " + task.title);
            taskCard.setOnClickListener(v -> editTask(task));
            LinearLayout titleRow = row(); titleRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout words = column();
            TextView title = text(task.title, 16, task.done ? TEAL : (overdue ? 0xffe05252 : INK));
            title.setTypeface(fontBold);
            if (task.done) title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            words.addView(title);
            String details = (task.event ? "Event · Protected" : task.flexible ? "Flexible task" : "Fixed · Protected") + " · " + task.durationMinutes + " min";
            String category = db.taskCategory(task.id);
            if (!category.isEmpty()) details = ("challenge".equals(category) ? "Challenge · " : "Daily · ") + details;
            if (task.flexible && task.time.isEmpty() && !task.done) details += " · Needs room";
            if (task.reminderMinutes >= 0) details += task.time.isEmpty() ? " · Reminder when scheduled" : " · Reminder";
            TextView detail = text(details, 11, MUTED); detail.setPadding(0, dp(5), 0, 0); words.addView(detail);
            if (!task.notes.isEmpty()) { TextView notes = text(task.notes, 12, MUTED); notes.setMaxLines(2); notes.setPadding(0, dp(6), 0, 0); words.addView(notes); }
            String moveNote = moveNotes.get(task.id);
            if (moveNote != null) {
                TextView move = text(moveNote, 11, 0xff6a7dba);
                move.setPadding(0, dp(5), 0, 0); words.addView(move);
            }
            titleRow.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            if (!task.event) {
                CheckBox check = new CheckBox(this); check.setChecked(task.done);
                check.setButtonTintList(ColorStateList.valueOf(task.done ? TEAL : (overdue ? 0xffe05252 : ACCENT)));
                check.setContentDescription((task.done ? "Undo completion: " : "Complete: ") + task.title);
                check.setMinWidth(dp(48)); check.setMinHeight(dp(48));
                check.setOnCheckedChangeListener((view, checked) -> {
                    db.complete(task.id, checked); PlannerObserver.observe(this, db, "Completion changed"); render();
                    toast(checked ? "Completed. Tap the check to undo." : "Completion undone.");
                });
                titleRow.addView(check, new LinearLayout.LayoutParams(dp(48), dp(48)));
            }
            taskCard.addView(titleRow);
            timeline.addView(taskCard, new LinearLayout.LayoutParams(0, -2, 1));
            page.addView(timeline); space(page, 9);
        }
    }

    private void changeDate(int days) { selectedDate = selectedDate.plusDays(days); calendarMonth = YearMonth.from(selectedDate); render(); }

    private void renderTasks(LinearLayout page, List<Task> tasks) {
        if (tasks.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("A clear day.", 19, INK)); space(empty, 7);
            empty.addView(text("Add a task or event when you’re ready. Your plan stays on this device.", 14, MUTED));
            space(empty, 13); empty.addView(button("Add your first item", true, v -> editTask(null)));
            page.addView(empty); return;
        }
        for (Task task : tasks) {
            LinearLayout timeline = row(); timeline.setGravity(Gravity.TOP);
            LinearLayout time = column(); time.setPadding(0, dp(17), dp(7), 0);
            TextView hour = text(task.time.isEmpty() ? "Anytime" : displayTime(task.time), task.time.isEmpty() ? 10 : 11, MUTED);
            hour.setGravity(Gravity.CENTER); time.addView(hour);
            TextView dot = text("•", 21, task.done ? TEAL : ACCENT); dot.setGravity(Gravity.CENTER); time.addView(dot);
            timeline.addView(time, new LinearLayout.LayoutParams(dp(57), -2));
            LinearLayout taskCard = card(); taskCard.setPadding(dp(13), dp(11), dp(7), dp(11));
            taskCard.setBackground(ripple(task.done ? 0xffedf5f2 : WHITE, 15));
            taskCard.setFocusable(true); taskCard.setContentDescription("Edit " + task.title);
            taskCard.setOnClickListener(v -> editTask(task));
            LinearLayout titleRow = row(); titleRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout words = column();
            TextView title = text(task.title, 16, task.done ? TEAL : INK);
            title.setTypeface(fontBold);
            if (task.done) title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            words.addView(title);
            String details = (task.event ? "Event · Protected" : task.flexible ? "Flexible task" : "Fixed · Protected") + " · " + task.durationMinutes + " min";
            String category=db.taskCategory(task.id);if(!category.isEmpty())details=("challenge".equals(category)?"Challenge · ":"Daily · ")+details;
            if(task.flexible&&task.time.isEmpty()&&!task.done)details+=" · Needs room";
            if (task.reminderMinutes >= 0) details += task.time.isEmpty()?" · Reminder when scheduled":" · Reminder";
            TextView detail = text(details, 11, MUTED); detail.setPadding(0, dp(5), 0, 0); words.addView(detail);
            if (!task.notes.isEmpty()) { TextView notes = text(task.notes, 12, MUTED); notes.setMaxLines(2); notes.setPadding(0, dp(6), 0, 0); words.addView(notes); }
            titleRow.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            if (!task.event) {
                CheckBox check = new CheckBox(this); check.setChecked(task.done);
                check.setButtonTintList(ColorStateList.valueOf(task.done ? TEAL : ACCENT));
                check.setContentDescription((task.done ? "Undo completion: " : "Complete: ") + task.title);
                check.setMinWidth(dp(48)); check.setMinHeight(dp(48));
                check.setOnCheckedChangeListener((view, checked) -> {
                    db.complete(task.id, checked); PlannerObserver.observe(this, db, "Completion changed"); render();
                    toast(checked ? "Completed. Tap the check to undo." : "Completion undone.");
                });
                titleRow.addView(check, new LinearLayout.LayoutParams(dp(48), dp(48)));
            }
            taskCard.addView(titleRow);
            timeline.addView(taskCard, new LinearLayout.LayoutParams(0, -2, 1));
            page.addView(timeline); space(page, 9);
        }
    }

    private void renderCalendar() {
        LinearLayout page = scrollPage();
        page.setPadding(dp(12), dp(10), dp(12), dp(24));
        pageTitle(page, "Anchor’s own calendar", "Room for your days.", "Choose a day to see its plan. Entries stay inside Anchor.");
        LinearLayout calendar = card();
        calendar.setPadding(0, dp(9), 0, dp(12));
        calendar.setMinimumWidth(dp(336));
        LinearLayout month = row(); month.setGravity(Gravity.CENTER_VERTICAL);
        month.addView(smallButton("‹", "Previous month", v -> { calendarMonth = calendarMonth.minusMonths(1); render(); }), new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView name = text(calendarMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")), 18, INK);
        name.setTypeface(fontBold); name.setGravity(Gravity.CENTER);
        month.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        month.addView(smallButton("›", "Next month", v -> { calendarMonth = calendarMonth.plusMonths(1); render(); }), new LinearLayout.LayoutParams(dp(48), dp(48)));
        calendar.addView(month);
        LinearLayout weekdays = row();
        for (String day : new String[]{"M", "T", "W", "T", "F", "S", "S"}) {
            TextView label = text(day, 11, MUTED); label.setGravity(Gravity.CENTER);
            weekdays.addView(label, new LinearLayout.LayoutParams(0, dp(32), 1));
        }
        calendar.addView(weekdays);
        List<Task> monthTasks = db.range(calendarMonth.atDay(1).toString(), calendarMonth.atEndOfMonth().toString());
        java.util.Set<String> occupied = new java.util.HashSet<>();
        for (Task task : monthTasks) occupied.add(task.date);
        int offset = calendarMonth.atDay(1).getDayOfWeek().getValue() - 1;
        int rows = (offset + calendarMonth.lengthOfMonth() + 6) / 7;
        for (int week = 0; week < rows; week++) {
            LinearLayout days = row();
            for (int dow = 0; dow < 7; dow++) {
                int number = week * 7 + dow - offset + 1;
                if (number < 1 || number > calendarMonth.lengthOfMonth()) { days.addView(new View(this), new LinearLayout.LayoutParams(0, dp(48), 1)); continue; }
                LocalDate day = calendarMonth.atDay(number);
                boolean selected = day.equals(selectedDate);
                Button cell = button(number + (occupied.contains(day.toString()) ? "\n•" : ""), false, v -> { selectedDate = day; render(); });
                cell.setPadding(0, 0, 0, 0); cell.setTextSize(13); cell.setGravity(Gravity.CENTER);
                cell.setTextColor(selected ? WHITE : day.equals(LocalDate.now()) ? ACCENT : INK);
                cell.setBackground(ripple(selected ? ACCENT : day.equals(LocalDate.now()) ? 0xffeef0fc : WHITE, 12));
                cell.setContentDescription(day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy"))
                        + (occupied.contains(day.toString()) ? ", has planned items" : ", no planned items") + (selected ? ", selected" : ""));
                days.addView(cell, new LinearLayout.LayoutParams(0, dp(48), 1));
            }
            calendar.addView(days);
        }
        space(calendar, 6);
        TextView legend = text("• Planned items     Pale indigo: today", 11, MUTED);
        legend.setPadding(dp(12), 0, dp(12), 0); calendar.addView(legend);
        HorizontalScrollView calendarScroll = new HorizontalScrollView(this);
        calendarScroll.setFillViewport(true); calendarScroll.setHorizontalScrollBarEnabled(false);
        calendarScroll.addView(calendar, new HorizontalScrollView.LayoutParams(-1, -2));
        page.addView(calendarScroll, new LinearLayout.LayoutParams(-1, -2)); space(page, 18);
        LinearLayout heading = row(); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(selectedDate.format(DateTimeFormatter.ofPattern("EEE, d MMM")), 19, INK); label.setTypeface(fontBold);
        heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(button("+ Add", true, v -> editTask(null)), new LinearLayout.LayoutParams(dp(91), dp(48)));
        page.addView(heading); space(page, 11); renderTasks(page, db.tasks(selectedDate.toString()));
    }

    private void renderProgress() {
        LinearLayout page = scrollPage();
        pageTitle(page, "Small steps add up", "Your consistency.", "A record of completed tasks, one day at a time.");
        LocalDate today = LocalDate.now();
        String savedStart = getPreferences(MODE_PRIVATE).getString("challenge-start", today.toString());
        LocalDate start;
        try { start = LocalDate.parse(savedStart); } catch (RuntimeException ex) { start = today; }
        getPreferences(MODE_PRIVATE).edit().putString("challenge-start", start.toString()).apply();
        LocalDate challengeStart = start;
        Map<String,Integer> counts = db.completionCounts(start.toString(), today.toString());
        Consistency.Snapshot challenge = Consistency.snapshot(start, today, counts);
        int total = challenge.completedTasks, active = challenge.activeDays;
        LinearLayout challengeCard = card();
        challengeCard.addView(text("60-day consistency challenge", 19, INK));
        space(challengeCard, 8);
        challengeCard.addView(text("Day " + challenge.day + " / 60 · " + active + " active days" + (challenge.finished ? " · Completed window" : ""), 15, TEAL));
        challengeCard.addView(text("Started " + start + ". A missed day does not restart the challenge.", 12, MUTED));
        challengeCard.addView(button("Edit challenge start", false, v -> pickDate(challengeStart, picked -> {
            getPreferences(MODE_PRIVATE).edit().putString("challenge-start", picked.toString()).apply(); render();
        })));
        page.addView(challengeCard); space(page, 15);
        LinearLayout metrics = row();
        metric(metrics, String.valueOf(challenge.currentStreak), "Day streak");
        metric(metrics, String.valueOf(total), "Tasks done");
        metric(metrics, String.valueOf(active), "Active days");
        page.addView(metrics); space(page, 16);
        LinearLayout heatmapCard = card(); heatmapCard.addView(text("Your 60-day window", 17, INK));
        space(heatmapCard, 10);
        HeatmapView heatmap = new HeatmapView(this);
        heatmap.setPlanData(DailyProgress.window(db.range(start.toString(),start.plusDays(59).toString()),start,60,java.time.LocalDateTime.now()),challengeStart,today,date->openDay(date));
        heatmapCard.addView(heatmap, new LinearLayout.LayoutParams(-1, -2));
        space(heatmapCard,8);heatmapCard.addView(text("Green: done · Red: missed · Grey: not yet due",12,MUTED));
        heatmapCard.addView(text("Each task fills an equal share of its planned day. Empty days stay grey. Tap a day to see its tasks. Activity totals above count the date you actually completed the work.",12,MUTED));
        page.addView(heatmapCard); space(page, 16);
        LinearLayout week = card();
        week.addView(text("This week", 18, INK)); space(week, 10);
        LocalDate monday = today.with(java.time.DayOfWeek.MONDAY);
        Map<String,DailyProgress.Day> weekOutcomes=DailyProgress.window(db.range(monday.toString(),monday.plusDays(6).toString()),monday,7,java.time.LocalDateTime.now());
        for (int i = 0; i < 7; i++) {
            LocalDate day = monday.plusDays(i);
            LinearLayout item = row(); item.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(day.format(DateTimeFormatter.ofPattern("EEE, d MMM")), 13, day.isAfter(today) ? MUTED : INK);
            item.addView(name, new LinearLayout.LayoutParams(0, dp(36), 1));
            DailyProgress.Day result=weekOutcomes.get(day.toString());
            item.addView(text(result.completed+" / "+result.total+" done · "+result.missed+" missed",12,MUTED));week.addView(item);
            week.addView(new DayProgressView(this,result),new LinearLayout.LayoutParams(-1,dp(8)));space(week,5);
        }
        space(week, 8); week.addView(text("Events don’t count as tasks. Undoing a check removes its contribution.", 12, MUTED));
        page.addView(week);
    }

    private void metric(LinearLayout parent, String value, String label) {
        LinearLayout block = column(); block.setPadding(dp(10), dp(16), dp(5), dp(16)); block.setBackground(shape(WHITE, 15, 0));
        TextView number = text(value, 25, ACCENT); number.setTypeface(fontBold); block.addView(number);
        block.addView(text(label, 11, MUTED));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (parent.getChildCount() != 0) params.leftMargin = dp(8);
        parent.addView(block, params);
    }

    private void renderChat() {
        LinearLayout page = column(); page.setPadding(dp(22), dp(8), dp(22), dp(10));
        body.addView(page, new LinearLayout.LayoutParams(-1, -1));
        TextView title = text("A space to think.", 27, INK); title.setTypeface(fontBold); page.addView(title);
        aiStatus = text(briefStatus(status), 11, MUTED); aiStatus.setPadding(0, dp(7), 0, dp(8)); aiStatus.setMaxLines(3); page.addView(aiStatus);
        LinearLayout actions = row();
        actions.addView(button("Stop", false, v -> { ai.stop(); sending = false; }), new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(button("Read last reply", false, v -> {
            if (!lastReply.isEmpty()) ai.speak(lastReply); else toast("There is no reply to read yet.");
        }), new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(button("Clear", false, v -> clearChat()), new LinearLayout.LayoutParams(dp(65), dp(48)));
        page.addView(actions);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout messages = column(); messages.setPadding(0, dp(10), 0, dp(10));
        List<ChatMessage> history = db.messages();
        if (history.isEmpty()) {
            LinearLayout empty = card(); empty.addView(text("Start with what’s on your mind.", 18, INK)); space(empty, 8);
            empty.addView(text("Import a GGUF model in Settings for local AI. Planner helpers work without a model: /today, /tomorrow and /progress.", 14, MUTED));
            space(empty, 12); empty.addView(text("Messages stay here. Only memory notes you explicitly save are included as remembered context.", 12, MUTED)); messages.addView(empty);
        }
        for (ChatMessage message : history) {
            boolean user = "user".equals(message.role);
            LinearLayout bubble = column(); bubble.setPadding(dp(14), dp(11), dp(14), dp(12));
            bubble.setBackground(shape(user ? 0xffe9ebfa : WHITE, 16, 0));
            bubble.addView(text(user ? "YOU" : "ANCHOR", 9, user ? ACCENT : TEAL));
            TextView content = text(message.content, 15, INK); content.setTextIsSelectable(true); content.setLineSpacing(dp(3), 1); content.setPadding(0, dp(5), 0, 0); bubble.addView(content);
            if(message.taskId>0) {
                Task created=db.task(message.taskId);
                if(created!=null)bubble.addView(button("View in timeline",false,v->openDay(LocalDate.parse(created.date))));
                if(message.creationUndone)bubble.addView(text("Adding this task was undone.",12,TEAL));
                else if(message.canUndoCreation)bubble.addView(button("Undo adding task",false,v->{
                    if(db.undoChatCreation(message.id)){Reminders.cancel(this,message.taskId);Reminders.reconcile(this,db);render();}
                    else toast("This task changed later. Open it in Calendar to edit it.");
                }));
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(10);
            if (user) params.leftMargin = dp(22); else params.rightMargin = dp(10);
            messages.addView(bubble, params);
            if (!user) lastReply = message.content;
        }
        scroll.addView(messages); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout composer = row(); composer.setGravity(Gravity.BOTTOM); composer.setPadding(0, dp(8), 0, 0);
        chatInput = input("Write a message…", draft, true); chatInput.setMinLines(1); chatInput.setMaxLines(4);
        chatInput.setTextSize(14); chatInput.setPadding(dp(12), dp(11), dp(8), dp(11)); chatInput.setBackground(shape(WHITE, 13, LINE));
        composer.addView(chatInput, new LinearLayout.LayoutParams(0, -2, 1));
        final Button voice = smallButton("Mic", "Dictate with on-device speech recognition", null);
        voice.setOnClickListener(v -> {
            if (ai == null) return;
            ai.listen(transcript -> {
                draft = transcript;
                if (chatInput != null) { chatInput.setText(transcript); chatInput.setSelection(chatInput.length()); }
            }, error -> toast("Voice: " + error), new VoiceController.ListeningCallback() {
                @Override public void onListeningStart() { voice.setText("●"); voice.setBackgroundColor(0xffe05252); }
                @Override public void onPartialResult(String partial) { voice.setText("● " + partial); }
                @Override public void onListeningEnd() { voice.setText("Mic"); voice.setBackground(null); }
                @Override public void onError(String error) { voice.setText("Mic"); voice.setBackground(null); }
            });
        }); voice.setTextSize(11);
        composer.addView(voice, new LinearLayout.LayoutParams(dp(48), dp(48)));
        Button send = button("Send", true, v -> sendChat()); send.setTextSize(12);
        composer.addView(send, new LinearLayout.LayoutParams(dp(64), dp(48))); page.addView(composer);
        if (!history.isEmpty()) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void sendChat() {
        if (chatInput == null) return;
        String prompt = chatInput.getText().toString().trim();
        if (prompt.isEmpty()) return;
        if (sending) { toast("Stop the current reply before sending another message."); return; }
        if (prompt.length() > 12000) { chatInput.setError("Keep messages under 12,000 characters."); return; }
        db.message("user", prompt); draft = ""; chatInput.setText(""); sending = true; render();
        ai.send(prompt);
    }
    private void openDay(LocalDate date){selectedDate=date;calendarMonth=YearMonth.from(date);tab=0;render();}
    private void showRoutines(){new RoutinesUi(this,db,date->openDay(date)).show();}

    private void clearChat() {
        new AlertDialog.Builder(this).setTitle("Clear this conversation?")
                .setMessage("This deletes all chat messages from this device. Your tasks and saved memory notes stay available.")
                .setNegativeButton("Keep", null).setPositiveButton("Clear chat", (dialog, which) -> {
                    ai.stop(); sending = false; db.clearMessages(); lastReply = ""; render();
                }).show();
    }

    private void renderLearning() {
        LinearLayout page = scrollPage();
        pageTitle(page, "Learning Library", "Build knowledge that sticks.", "Import notes, generate questions, review with spaced repetition.");
        LinearLayout actions = row();
        actions.addView(button("Import Notes", true, v -> importLearningNotes()), new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(button("Daily Review", false, v -> startDailyReview()), new LinearLayout.LayoutParams(0, dp(48), 1));
        page.addView(actions); space(page, 12);

        List<LearningSource> sources = db.getSources(null);
        if (sources.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("No learning materials yet.", 19, INK)); space(empty, 7);
            empty.addView(text("Import text notes or PDFs to start building your knowledge base.", 14, MUTED));
            space(empty, 13); empty.addView(button("Import your first note", true, v -> importLearningNotes()));
            page.addView(empty);
        } else {
            for (LearningSource source : sources) {
                LinearLayout card = card(); card.setPadding(dp(13), dp(11), dp(7), dp(11));
                card.setBackground(ripple(WHITE, 15));
                LinearLayout titleRow = row(); titleRow.setGravity(Gravity.CENTER_VERTICAL);
                TextView title = text(source.title, 16, INK); title.setTypeface(fontBold);
                titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                TextView meta = text(source.type + " · " + source.totalChunks + " chunks", 11, MUTED);
                titleRow.addView(meta, new LinearLayout.LayoutParams(dp(80), -2));
                card.addView(titleRow);
                LinearLayout details = column();
                if (!source.author.isEmpty()) details.addView(text("By " + source.author, 12, MUTED));
                if (!source.uri.isEmpty()) details.addView(text(source.uri, 11, MUTED));
                List<LearningConcept> concepts = db.getConcepts(source.id);
                int due = 0; for (LearningConcept c : concepts) if (c.lastReviewed == null || c.lastReviewed + c.reviewIntervalDays * 86400000L <= System.currentTimeMillis()) due++;
                details.addView(text(concepts.size() + " concepts · " + due + " due for review", 11, due > 0 ? 0xffe05252 : TEAL));
                card.addView(details);
                LinearLayout btnRow = row();
                btnRow.addView(button("Review", false, v -> startSourceReview(source.id)), new LinearLayout.LayoutParams(0, dp(40), 1));
                btnRow.addView(button("Questions", false, v -> ai.generateLearningQuestions(source.id)), new LinearLayout.LayoutParams(0, dp(40), 1));
                btnRow.addView(button("Concepts", false, v -> ai.extractLearningConcepts(source.id)), new LinearLayout.LayoutParams(0, dp(40), 1));
                btnRow.addView(button("Delete", false, v -> { db.deleteSource(source.id); render(); }), new LinearLayout.LayoutParams(dp(60), dp(40)));
                card.addView(btnRow);
                page.addView(card); space(page, 9);
            }
        }
    }

    private void importLearningNotes() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "text/markdown", "application/pdf"});
        startActivityForResult(intent, 8723);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 8723 && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            String name = "Imported Note";
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && !cursor.isNull(idx)) name = cursor.getString(idx);
                }
            }
            LearningSource source = new LearningSource();
            source.type = name.toLowerCase().endsWith(".pdf") ? LearningSource.TYPE_PDF : LearningSource.TYPE_NOTE;
            source.title = name; source.uri = uri.toString(); source.createdAt = source.updatedAt = System.currentTimeMillis();
            long sourceId = db.saveSource(source);
            LearningExtractor.ExtractResult result = LearningExtractor.extractFromUri(this, uri, sourceId);
            if (result.success()) {
                for (LearningChunk ch : result.chunks) db.saveChunk(ch);
                source.totalChunks = result.chunks.size(); db.saveSource(source);
                toast("Imported " + result.chunks.size() + " chunks from " + name);
            } else {
                toast("Import failed: " + result.error);
            }
            render();
        } else if (ai != null && ai.onActivityResult(requestCode, resultCode, data)) {
            return;
        }
    }

    private void startDailyReview() {
        List<LearningConcept> due = LearningScheduler.getDailyReviewConcepts(db, 10);
        if (due.isEmpty()) { toast("Nothing due for review today!"); return; }
        LearningSession session = LearningScheduler.createReviewSession(db, due, LearningSession.MODE_DAILY);
        startReviewSession(session.id, due);
    }

    private void startSourceReview(long sourceId) {
        List<LearningConcept> concepts = db.getConcepts(sourceId);
        List<LearningConcept> due = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (LearningConcept c : concepts) if (c.lastReviewed == null || c.lastReviewed + c.reviewIntervalDays * 86400000L <= now) due.add(c);
        if (due.isEmpty()) { toast("No concepts due for review in this source."); return; }
        LearningSession session = LearningScheduler.createReviewSession(db, due, LearningSession.MODE_REVIEW);
        startReviewSession(session.id, due);
    }

    private void startReviewSession(long sessionId, List<LearningConcept> concepts) {
        Intent intent = new Intent(this, ReviewActivity.class);
        intent.putExtra("session_id", sessionId);
        startActivity(intent);
    }

    @Override public void onStatus(String message) {
        status = message;
        if (aiStatus != null) aiStatus.setText(tab == 2 ? briefStatus(message) : message);
    }
    private String briefStatus(String message) {int end=message.indexOf('\n');return end<0?message:message.substring(0,end);}
    @Override public void onReply(String reply) {
        Task proposal = ScheduleProposal.parse(reply);
        if (proposal != null) {
            sending = false;
            lastReply = "Draft ready: " + proposal.title + ". Review its date, time and duration before saving.";
            db.message("assistant", lastReply);
            if (tab == 2) render();
            editTask(proposal);
            return;
        }
        if (reply.trim().startsWith("{") || reply.trim().startsWith("[") || reply.trim().startsWith("```") || reply.contains("\"action\"") || reply.contains("\"duration_minutes\""))
            reply = "Tell me the task name and start time, and I can schedule it. I’ll use 30 minutes unless you specify a duration.";
        sending = false; db.message("assistant", reply); lastReply = reply;
        if (tab == 2) render(); else toast("Anchor’s reply is ready in Chat.");
    }
    @Override public void onError(String message) {
        sending = false; status = message;
        if (aiStatus != null) aiStatus.setText(message);
        toast(message);
    }
    @Override public void onCalendarChanged() {sending=false;render();if(tab!=2)toast("Your schedule is saved in Calendar.");}
    @Override public void onRoutineSetupRequested() {
        sending=false;db.message("assistant","Let’s set up the repeating routine. Choose its name, session length, weekdays and period, then preview the dates before saving.");render();
        new RoutinesUi(this,db,date->openDay(date)).create();
    }
    @Override public void onPeriodPlanDraft(List<Routine> items,String explanation,String source) {
        sending=false;db.message("assistant","I’ve prepared "+items.size()+" editable routine/goal suggestions. Choose the period, review the list, then preview the actual calendar slots. Nothing is saved yet.");render();
        new PeriodUi(this,db,date->openDay(date)).edit(items,explanation,source);
    }
    @Override public void onMoveRequested(String text) {
        sending=false;db.message("assistant","Choose the exact session and review its new date and time. I’ll check for conflicts before moving it.");render();new MoveUi(this,db,date->openDay(date)).show(text);
    }
    @Override public void onLearningQuestionsGenerated(List<LearningQuestion> questions, String sourceTitle) {
        sending=false;db.message("assistant","Generated "+questions.size()+" questions for \""+sourceTitle+"\". Open Learning → Review to start a session.");render();toast(questions.size()+" questions ready");
    }
    @Override public void onLearningConceptsExtracted(List<LearningConcept> concepts, String sourceTitle) {
        sending=false;db.message("assistant","Extracted "+concepts.size()+" key concepts from \""+sourceTitle+"\". They're now scheduled for spaced repetition review.");render();toast(concepts.size()+" concepts added");
    }
    @Override public void onLearningReviewAnswered(LearningQuestion question, boolean correct, String feedback) {
        sending=false;db.message("assistant",(correct?"Correct!":"Not quite right.")+" "+feedback);render();
    }
    @Override public void onLearningSessionComplete(LearningSession session) {
        sending=false;db.message("assistant","Review session complete. Score: "+session.correctAnswers+"/"+session.totalQuestions+".");render();
    }

    private void renderSettings() {
        LinearLayout page = scrollPage();
        pageTitle(page, "Made to stay with you", "Private by default.", "Your planner, memory and model live on this device.");
        renderPlanningSettings(page);
        LinearLayout model = card(); model.addView(text("Local AI", 19, INK)); space(model, 8);
        status = ai.status(); aiStatus = text(status, 12, MUTED); aiStatus.setTextIsSelectable(true); model.addView(aiStatus);
        space(model, 10);
        ActivityManager manager = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo(); manager.getMemoryInfo(memory);
        model.addView(text(String.format(Locale.getDefault(), "System-accessible RAM: %.2f GiB · Available: %.2f GiB", memory.totalMem / 1073741824d, memory.availMem / 1073741824d), 12, MUTED));
        space(model, 10);
        Switch guard = new Switch(this); guard.setText("Large model guard (requires 256 MiB headroom)"); guard.setTextSize(13); guard.setTypeface(fontRegular); guard.setMinHeight(dp(48));
        guard.setChecked(MemoryPolicy.isGuardEnabled(this));
        guard.setOnCheckedChangeListener((view, checked) -> { MemoryPolicy.setGuardEnabled(this, checked); render(); });
        model.addView(guard);
        model.addView(text("Disable to load models larger than available RAM. May cause system kill on memory pressure.", 12, MUTED));
        space(model, 10);
        model.addView(button("Import GGUF model", true, v -> ai.importModel()));
        space(model, 7);
        model.addView(button("Run device benchmark", false, v -> { new AlertDialog.Builder(this).setTitle("Measure this device?")
                .setMessage("The benchmark loads the imported model and generates a short reply. It can use considerable RAM. For an offline check, turn on airplane mode first. Results describe this run on this device.")
                .setNegativeButton("Cancel", null).setPositiveButton("Run benchmark", (dialog, which) -> ai.benchmark()).show(); }));
        LinearLayout controls = row();
        controls.addView(button("Stop", false, v -> { ai.stop(); sending = false; }), new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(button("Unload", false, v -> { ai.unload(); sending = false; }), new LinearLayout.LayoutParams(0, dp(48), 1)); model.addView(controls);
        model.addView(button("Export benchmark report", false, v -> ai.exportBenchmark()));
        space(model, 5); model.addView(text("Model loading is manual. Device measurements determine whether a model is usable; importing a file does not prove it will run well.", 12, MUTED));
        page.addView(model); space(page, 15);
        LinearLayout reasoning = card(); reasoning.addView(text("Reasoning Mode", 19, INK)); space(reasoning, 8);
        reasoning.addView(text("Choose how much context and computation to use. Higher modes use more RAM and take longer.", 12, MUTED)); space(reasoning, 8);
        Spinner modeSpinner = new Spinner(this);
        String[] modes = {"Fast · 2K context, 256 out", "Balanced · 4K context, 512 out", "Deep · 8K context, 1K out"};
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, modes) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                View view = super.getView(position, recycled, parent);
                if (view instanceof TextView) ((TextView)view).setTypeface(fontRegular);
                return view;
            }
            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                View view = super.getDropDownView(position, recycled, parent);
                if (view instanceof TextView) ((TextView)view).setTypeface(fontRegular);
                return view;
            }
        };
        modeSpinner.setAdapter(modeAdapter);
        modeSpinner.setSelection(ai.getReasoningMode().ordinal());
        modeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                LocalAi.ReasoningMode newMode = LocalAi.ReasoningMode.values()[position];
                if (newMode != ai.getReasoningMode()) {
                    ai.setReasoningMode(newMode);
                    ai.unload(); // Reload with new context size
                    render();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        reasoning.addView(modeSpinner);
        page.addView(reasoning); space(page, 15);
        LinearLayout memories = card();
        LinearLayout memoryHeader = row(); memoryHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text("Memory notes", 19, INK); memoryHeader.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        memoryHeader.addView(button("+ Add", false, v -> addMemory()), new LinearLayout.LayoutParams(dp(80), dp(48))); memories.addView(memoryHeader);
        memories.addView(text("Only notes you save here become remembered context in local chat. Tap a note to delete it.", 12, MUTED)); space(memories, 8);
        Switch memoryContext = new Switch(this); memoryContext.setText("Include saved notes in local AI chat"); memoryContext.setTextSize(13); memoryContext.setTypeface(fontRegular); memoryContext.setMinHeight(dp(48));
        memoryContext.setChecked(getSharedPreferences("privacy", MODE_PRIVATE).getBoolean("memory-context", true));
        memoryContext.setOnCheckedChangeListener((v, enabled) -> getSharedPreferences("privacy", MODE_PRIVATE).edit().putBoolean("memory-context", enabled).apply()); memories.addView(memoryContext);
        List<Memory> notes = db.memories();
        if (notes.isEmpty()) memories.addView(text("No memory notes saved.", 14, MUTED));
        for (Memory note : notes) {
            TextView item = text(note.text, 14, INK); item.setPadding(dp(9), dp(13), dp(9), dp(13)); item.setMinHeight(dp(48));
            item.setBackground(ripple(0xfff5f6fc, 9)); item.setFocusable(true); item.setContentDescription(note.text + ". Tap to delete memory note.");
            item.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Delete memory note?").setMessage(note.text)
                    .setNegativeButton("Keep", null).setPositiveButton("Delete", (dialog, which) -> { db.forget(note.id); render(); }).show());
            memories.addView(item); space(memories, 6);
        }
        page.addView(memories); space(page, 15);
        LinearLayout notifications = card(); notifications.addView(text("Reminders", 19, INK)); space(notifications, 8);
        notifications.addView(text(Reminders.status(this), 13, MUTED));
        Switch ringing = new Switch(this); ringing.setText("Ring for task reminders"); ringing.setTypeface(fontRegular); ringing.setMinHeight(dp(48));
        ringing.setChecked(RingingAlarms.ringingEnabled(this));
        ringing.setOnCheckedChangeListener((view, checked) -> { RingingAlarms.setRingingEnabled(this, checked); Reminders.reconcile(this, db); render(); });
        notifications.addView(ringing);
        notifications.addView(text(RingingAlarms.status(this), 12, MUTED));
        notifications.addView(button("Manage reminder access", false, v -> Reminders.requestAccess(this)));
        notifications.addView(text("Reminders use Android alarms. Force-stopping Anchor prevents delivery until you reopen it. Lock-screen text is generic.", 12, MUTED)); page.addView(notifications); space(page, 15);
        LinearLayout privacy = card(); privacy.addView(text("Your data stays local", 19, INK)); space(privacy, 8);
        privacy.addView(text("No account, analytics or network permission. Cloud and device backup are disabled. Android’s app sandbox and device encryption protect storage; the database has no additional app-level encryption.", 13, MUTED));
        space(privacy, 8); privacy.addView(text("Voice uses Android’s on-device recognition when available, and an installed offline voice for read-aloud. Unsupported devices keep typing available.", 12, MUTED));
        privacy.addView(button("Clear chat history", false, v -> clearChat())); page.addView(privacy);
        space(page, 18); TextView version = text("ANCHOR  ·  First local release", 10, MUTED); version.setLetterSpacing(.08f); version.setGravity(Gravity.CENTER); page.addView(version);
    }

    private void replanToday() {
        PlannerSettings settings = PlannerSettings.load(this);
        long id = db.replan(LocalDate.now(), LocalTime.now(), settings.dayStartMinute, settings.dayEndMinute,
                settings.flexiblePercent, "Requested by you");
        Reminders.reconcile(this, db); render();
        if (id > 0) showReports(); else toast("No schedule changes needed. Protected items stayed in place.");
    }

    private void showReports() {
        LinearLayout list = dialogBody();
        List<ScheduleChange> reports = db.changeReports();
        if (reports.isEmpty()) list.addView(text("No automatic schedule changes yet.", 15, MUTED));
        for (ScheduleChange report : reports) {
            LinearLayout item = card();
            item.addView(text(report.date + " · " + report.reason, 13, ACCENT));
            space(item, 7); item.addView(text(report.summary, 14, INK));
            for (Scheduler.Move move : report.moves) {
                Task task = db.task(move.taskId);
                item.addView(text(move.title + ": "
                        + (move.fromTime.isEmpty() ? "Unscheduled" : move.fromTime) + " → "
                        + (move.toTime.isEmpty() ? "Unscheduled" : move.toTime), 12, MUTED));
            }
            for (long id : report.unscheduled) {
                Task task = db.task(id);
                item.addView(text("Needs room: " + (task == null ? "Deleted item" : task.title), 12, MUTED));
            }
            if (report.undone) item.addView(text("Undone", 13, TEAL));
            else if (report.canUndo) item.addView(button("Undo and pause automatic planning", false, v -> {
                if (PlannerObserver.undoAndPause(this, db, report.id)) {
                    toast("Schedule restored. Automatic planning paused; re-enable it in Settings.");
                    item.addView(text("Undone · automatic planning paused", 13, TEAL));
                } else toast("These items changed later. Undo cannot overwrite the newer changes.");
                render();
                View parent = (View) list.getParent();
                if (parent != null) parent.announceForAccessibility("Report updated. Close and reopen to refresh.");
                v.setEnabled(false);
            }));
            else item.addView(text(report.moves.isEmpty() ? "No time changes to undo." : "Undo unavailable because the calendar changed later.", 12, MUTED));
            list.addView(item); space(list, 12);
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(list);
        new AlertDialog.Builder(this).setTitle("Schedule change reports").setView(scroll).setPositiveButton("Close", null).show();
    }

    private void renderPlanningSettings(LinearLayout page) {
        PlannerSettings settings = PlannerSettings.load(this);
        LinearLayout planning = card(); planning.addView(text("Your planning rules", 19, INK)); space(planning, 8);
        Switch automatic = new Switch(this); automatic.setTypeface(fontRegular); automatic.setText("Automatically arrange flexible tasks");
        automatic.setTextSize(13); automatic.setMinHeight(dp(48)); automatic.setChecked(settings.automaticPlanning);
        automatic.setOnCheckedChangeListener((view, checked) -> {
            PlannerSettings fresh = PlannerSettings.load(this); fresh.automaticPlanning = checked; fresh.save(this);
            PlannerObserver.schedule(this); PlannerObserver.observe(this, db, "Planning preference changed"); render();
        }); planning.addView(automatic);
        planning.addView(text(String.format(Locale.ROOT,"Day window %02d:%02d–%02d:%02d · Flexible %d%% / fixed or free %d%%",
                settings.dayStartMinute / 60, settings.dayStartMinute % 60, settings.dayEndMinute / 60, settings.dayEndMinute % 60,
                settings.flexiblePercent, 100 - settings.flexiblePercent), 13, INK));
        space(planning, 8); planning.addView(text("The flexible percentage caps total flexible minutes in the day window. Fixed commitments always keep their times. Work that cannot fit stays unscheduled.", 12, MUTED));
        planning.addView(button("Edit window and allocation", false, v -> editPlanningRules()));
        planning.addView(text(PlannerObserver.status(this), 12, MUTED));
        planning.addView(button("View change reports", false, v -> showReports()));
        page.addView(planning); space(page, 15);
    }

    private void editPlanningRules() {
        PlannerSettings settings = PlannerSettings.load(this);
        int[] window = {settings.dayStartMinute, settings.dayEndMinute};
        LinearLayout form = dialogBody();
        Button start = button(String.format(Locale.ROOT,"Start %02d:%02d",window[0]/60,window[0]%60), false, null);
        Button end = button(String.format(Locale.ROOT,"End %02d:%02d",window[1]/60,window[1]%60), false, null);
        start.setOnClickListener(v -> new TimePickerDialog(this,(picker,h,m)-> { window[0]=h*60+m;start.setText(String.format(Locale.ROOT,"Start %02d:%02d",h,m)); },window[0]/60,window[0]%60,true).show());
        end.setOnClickListener(v -> new TimePickerDialog(this,(picker,h,m)-> { window[1]=h*60+m;end.setText(String.format(Locale.ROOT,"End %02d:%02d",h,m)); },window[1]/60,window[1]%60,true).show());
        form.addView(start); form.addView(end); form.addView(fieldLabel("FLEXIBLE ALLOCATION, 0–100%"));
        EditText allocation = input("Flexible percentage",String.valueOf(settings.flexiblePercent),false); allocation.setInputType(InputType.TYPE_CLASS_NUMBER); form.addView(allocation);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Planning window").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                settings.dayStartMinute=window[0];settings.dayEndMinute=window[1];settings.flexiblePercent=Integer.parseInt(allocation.getText().toString().trim());
                settings.save(this); PlannerObserver.observe(this,db,"Planning rules changed");dialog.dismiss();render();
            } catch (IllegalArgumentException | IllegalStateException ex) { toast(ex.getMessage()); }
        })); dialog.show();
    }

    private void addMemory() {
        EditText note = input("Something you want Anchor to remember", "", true);
        note.setMinLines(3); note.setMaxLines(8);
        LinearLayout wrapper = dialogBody(); wrapper.addView(note);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Save a memory note").setView(wrapper)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = note.getText().toString().trim();
            if (value.isEmpty()) { note.setError("Write a note first."); return; }
            if (value.length() > 4000) { note.setError("Keep memory notes under 4,000 characters."); return; }
            db.remember(value); dialog.dismiss(); render();
        })); dialog.show();
    }

    private void editTask(Task existing) {
        boolean editing = existing != null && existing.id != 0;
        Task task = existing != null ? existing : new Task();
        final LocalDate[] dateValue = { existing != null ? LocalDate.parse(task.date) : selectedDate };
        final String[] timeValue = { existing != null ? task.time : "" };
        LinearLayout form = dialogBody();
        EditText title = input("Task or event title", task.title, false);
        title.setMaxLines(3); form.addView(title);
        form.addView(fieldLabel("TYPE"));
        Switch event = new Switch(this); event.setTypeface(fontRegular); event.setText("Event"); event.setTextColor(INK); event.setTextSize(14); event.setMinHeight(dp(48)); event.setChecked(task.event); form.addView(event);
        TextView typeHint = text("Tasks contribute to progress. Events don’t.", 12, MUTED); form.addView(typeHint);
        Switch flexible = new Switch(this); flexible.setTypeface(fontRegular); flexible.setText("Flexible · Anchor may move this task");
        flexible.setTextSize(13); flexible.setMinHeight(dp(48)); flexible.setChecked(task.flexible); flexible.setEnabled(!event.isChecked()); form.addView(flexible);
        form.addView(text("Fixed tasks and events keep their times. Flexible tasks use your day window and allocation.", 12, MUTED));
        event.setOnCheckedChangeListener((view, checked) -> { flexible.setEnabled(!checked); if (checked) flexible.setChecked(false); });
        form.addView(fieldLabel("DATE & TIME"));
        Button day = button(dateValue[0].format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")), false, null);
        day.setOnClickListener(v -> pickDate(dateValue[0], picked -> { dateValue[0] = picked; day.setText(picked.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))); })); form.addView(day);
        LinearLayout timeRow = row();
        Button time = button(timeValue[0].isEmpty() ? "Set time (optional)" : displayTime(timeValue[0]), false, null);
        time.setOnClickListener(v -> {
            LocalTime initial = timeValue[0].isEmpty() ? LocalTime.now() : LocalTime.parse(timeValue[0]);
            new TimePickerDialog(this, (picker, hour, minute) -> { timeValue[0] = String.format(Locale.ROOT, "%02d:%02d", hour, minute); time.setText(displayTime(timeValue[0])); }, initial.getHour(), initial.getMinute(), DateFormat.is24HourFormat(this)).show();
        });
        timeRow.addView(time, new LinearLayout.LayoutParams(0, dp(48), 1));
        timeRow.addView(button("Clear", false, v -> { timeValue[0] = ""; time.setText("Set time (optional)"); }), new LinearLayout.LayoutParams(dp(70), dp(48))); form.addView(timeRow);
        form.addView(fieldLabel("DURATION (MINUTES)"));
        EditText duration = input("30", String.valueOf(task.durationMinutes), false); duration.setInputType(InputType.TYPE_CLASS_NUMBER); form.addView(duration);
        form.addView(fieldLabel("REMINDER"));
        Spinner reminder = new Spinner(this); String[] reminders = {"No reminder", "At start time", "5 minutes before", "15 minutes before", "30 minutes before", "1 hour before", "1 day before"};
        int[] reminderValues = {-1, 0, 5, 15, 30, 60, 1440};
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, reminders) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                View view = super.getView(position, recycled, parent);
                if (view instanceof TextView) ((TextView)view).setTypeface(fontRegular);
                return view;
            }
            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                View view = super.getDropDownView(position, recycled, parent);
                if (view instanceof TextView) ((TextView)view).setTypeface(fontRegular);
                return view;
            }
        }; reminder.setAdapter(adapter); reminder.setMinimumHeight(dp(48));
        for (int i = 0; i < reminderValues.length; i++) if (reminderValues[i] == task.reminderMinutes) reminder.setSelection(i);
        form.addView(reminder, new LinearLayout.LayoutParams(-1, dp(48)));
        form.addView(fieldLabel("NOTES"));
        EditText notes = input("Optional details", task.notes, true); notes.setMinLines(2); notes.setMaxLines(5); form.addView(notes);
        ScrollView scroller = new ScrollView(this); scroller.addView(form);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(editing ? "Edit item" : "Add to your day").setView(scroller)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null);
        if (editing) builder.setNeutralButton("Delete", null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = title.getText().toString().trim();
                if (name.isEmpty()) { title.setError("Give this item a title."); title.requestFocus(); return; }
                if (name.length() > 200) { title.setError("Keep titles under 200 characters."); return; }
                int minutes;
                try { minutes = Integer.parseInt(duration.getText().toString().trim()); }
                catch (NumberFormatException error) { duration.setError("Enter a duration in minutes."); return; }
                if (minutes < 1 || minutes > 1440) { duration.setError("Use 1 to 1,440 minutes."); return; }
                int reminderMinutes = reminderValues[reminder.getSelectedItemPosition()];
                if (reminderMinutes >= 0 && timeValue[0].isEmpty()&&!flexible.isChecked()) { toast("Set a time before adding a reminder, or mark this task flexible."); return; }
                String noteText = notes.getText().toString().trim();
                if (noteText.length() > 4000) { notes.setError("Keep notes under 4,000 characters."); return; }
                task.title = name; task.date = dateValue[0].toString(); task.time = timeValue[0];
                task.durationMinutes = minutes; task.event = event.isChecked(); task.flexible = !task.event && flexible.isChecked(); task.reminderMinutes = reminderMinutes; task.notes = noteText;
                try { DateRules.validate(task); db.save(task); }
                catch (IllegalArgumentException error) { toast(error.getMessage()); return; }
                selectedDate = dateValue[0]; calendarMonth = YearMonth.from(selectedDate);
                PlannerObserver.observe(this, db, "Item saved"); dialog.dismiss(); render(); toast(editing ? "Item updated." : "Added to your day.");
            });
            if (editing) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Delete this item?").setMessage(task.title + "\n\nThis also removes its completion contribution.")
                    .setNegativeButton("Keep", null).setPositiveButton("Delete", (confirm, which) -> {
                        Reminders.cancel(this, task.id); db.delete(task.id); PlannerObserver.observe(this, db, "Item deleted"); dialog.dismiss(); render(); toast("Item deleted.");
                    }).show());
        });
        dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private LinearLayout dialogBody() { LinearLayout form = column(); form.setPadding(dp(23), dp(5), dp(23), dp(18)); return form; }
    private TextView fieldLabel(String label) { TextView view = text(label, 10, MUTED); view.setLetterSpacing(.07f); view.setPadding(0, dp(15), 0, dp(5)); return view; }
    private void pickDate(LocalDate initial, java.util.function.Consumer<LocalDate> result) {
        DatePickerDialog picker = new DatePickerDialog(this, (view, year, month, day) -> result.accept(LocalDate.of(year, month + 1, day)), initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
        picker.getDatePicker().setFirstDayOfWeek(java.util.Calendar.MONDAY); picker.show();
    }
    private String displayTime(String time) {
        try { return LocalTime.parse(time).format(DateTimeFormatter.ofPattern(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a")); }
        catch (RuntimeException ignored) { return time; }
    }
    private TextView text(String content, float size, int color) {
        TextView view = new TextView(this); view.setTypeface(fontRegular); view.setText(content); view.setTextSize(size); view.setTextColor(color); view.setIncludeFontPadding(false); return view;
    }
    private EditText input(String hint, String value, boolean multiline) {
        EditText view = new EditText(this); view.setTypeface(fontRegular); view.setTextSize(16); view.setTextColor(INK); view.setHintTextColor(MUTED); view.setHint(hint); view.setText(value);
        view.setMinHeight(dp(48)); view.setSelectAllOnFocus(false);
        view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        view.setGravity(Gravity.TOP | Gravity.START); return view;
    }
    private Button button(String label, boolean primary, View.OnClickListener listener) {
        Button view = new Button(this); view.setText(label); view.setTextSize(14); view.setAllCaps(false); view.setTextColor(primary ? WHITE : ACCENT);
        view.setTypeface(fontBold); view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48)); view.setMinWidth(0); view.setMinimumWidth(0);
        view.setPadding(dp(10), dp(8), dp(10), dp(8)); view.setBackground(ripple(primary ? ACCENT : 0x00000000, 12));
        if (listener != null) view.setOnClickListener(listener); return view;
    }
    private Button smallButton(String label, String description, View.OnClickListener listener) {
        Button button = button(label, false, listener); button.setTextSize(23); button.setContentDescription(description); return button;
    }
    private LinearLayout card() { LinearLayout card = column(); card.setPadding(dp(16), dp(16), dp(16), dp(16)); card.setBackground(shape(WHITE, 17, 0)); return card; }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private GradientDrawable shape(int color, int radius, int stroke) { GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(radius)); if (stroke != 0) background.setStroke(dp(1), stroke); return background; }
    private RippleDrawable ripple(int color, int radius) { return new RippleDrawable(ColorStateList.valueOf(0x224d54b8), shape(color, radius, 0), shape(WHITE, radius, 0)); }
    private void space(LinearLayout layout, int height) { layout.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message == null ? "Please check your input." : message, Toast.LENGTH_LONG).show(); }

    @Override protected void onResume() {
        super.onResume();
        LocalDate today = LocalDate.now();
        if (selectedDate.equals(lastVisibleToday) && !today.equals(lastVisibleToday)) {
            selectedDate = today;
            calendarMonth = YearMonth.from(today);
        }
        lastVisibleToday = today;
        if (db != null) { PlannerObserver.observe(this, db, "App resumed"); if (body != null) render(); }
        progressClock.removeCallbacks(refreshProgress);progressClock.postDelayed(refreshProgress,60_000);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        ai.onRequestPermissionsResult(request, permissions, results);
        Reminders.reconcile(this, db);
        if (tab == 4) render();
    }
    @Override protected void onDestroy() {
        if (ai != null) ai.close();
        if (db != null) db.close();
        super.onDestroy();
    }
    @Override protected void onStop() {
        progressClock.removeCallbacks(refreshProgress);
        if (ai != null) ai.stopVoice();
        super.onStop();
    }

    private static final class NavIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int kind;
        NavIcon(Activity context, int kind, int color) { super(context); this.kind = kind; paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.65f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float scale = getWidth() / 24f; canvas.save(); canvas.scale(scale, scale);
            if (kind == 0) {
                canvas.drawRoundRect(4, 3, 20, 21, 3, 3, paint); canvas.drawLine(8, 8, 16, 8, paint); canvas.drawLine(8, 12, 16, 12, paint); canvas.drawLine(8, 16, 13, 16, paint);
            } else if (kind == 1) {
                canvas.drawRoundRect(3, 5, 21, 21, 3, 3, paint); canvas.drawLine(3, 10, 21, 10, paint); canvas.drawLine(8, 2, 8, 7, paint); canvas.drawLine(16, 2, 16, 7, paint); canvas.drawPoint(8, 14, paint); canvas.drawPoint(12, 14, paint); canvas.drawPoint(16, 14, paint); canvas.drawPoint(8, 18, paint); canvas.drawPoint(12, 18, paint);
            } else if (kind == 2) {
                Path path = new Path(); path.moveTo(6, 4); path.lineTo(18, 4); path.quadTo(21, 4, 21, 7); path.lineTo(21, 15); path.quadTo(21, 18, 18, 18); path.lineTo(9, 18); path.lineTo(4, 22); path.lineTo(4, 17); path.quadTo(3, 17, 3, 15); path.lineTo(3, 7); path.quadTo(3, 4, 6, 4); canvas.drawPath(path, paint); canvas.drawLine(8, 9, 16, 9, paint); canvas.drawLine(8, 13, 13, 13, paint);
            } else if (kind == 3) {
                canvas.drawLine(4, 21, 21, 21, paint); canvas.drawRoundRect(5, 13, 8, 21, 1, 1, paint); canvas.drawRoundRect(11, 8, 14, 21, 1, 1, paint); canvas.drawRoundRect(17, 3, 20, 21, 1, 1, paint);
            } else {
                canvas.drawCircle(12, 12, 7, paint); canvas.drawCircle(12, 12, 2.5f, paint);
                for (int i = 0; i < 8; i++) { double angle = i * Math.PI / 4; canvas.drawLine(12 + (float)Math.cos(angle) * 8, 12 + (float)Math.sin(angle) * 8, 12 + (float)Math.cos(angle) * 10, 12 + (float)Math.sin(angle) * 10, paint); }
            }
            canvas.restore();
        }
    }
}
