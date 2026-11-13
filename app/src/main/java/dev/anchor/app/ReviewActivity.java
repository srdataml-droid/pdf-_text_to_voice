package dev.anchor.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Typeface;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Review session screen with question/answer flow. */
public final class ReviewActivity extends Activity {
    private LinearLayout root, body;
    private AnchorDb db;
    private long sessionId;
    private List<LearningConcept> concepts;
    private int currentIndex = 0;
    private LearningQuestion currentQuestion;
    private Handler handler = new Handler(Looper.getMainLooper());
    private long questionStartTime;
    private boolean answering = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sessionId = getIntent().getLongExtra("session_id", -1);
        if (sessionId < 0) { finish(); return; }
        db = new AnchorDb(this);
        LearningSession session = db.getSession(sessionId);
        if (session == null) { finish(); return; }
        concepts = new ArrayList<>();
        List<LearningAnswer> answers = db.getAnswers(sessionId);
        // Reconstruct concepts from answered questions
        // For simplicity, we'll just fetch due concepts again
        concepts = LearningScheduler.getDailyReviewConcepts(db, 10);
        if (concepts.isEmpty()) { finish(); return; }

        root = column(); root.setBackgroundColor(0xfff5f6fc); root.setPadding(dp(22), dp(10), dp(22), dp(24));
        setContentView(root);
        showProgress();
        nextQuestion();
    }

    private void showProgress() {
        root.removeAllViews();
        TextView progress = text("Question " + (currentIndex + 1) + " of " + concepts.size(), 14, 0xff68728c);
        progress.setGravity(Gravity.CENTER); progress.setPadding(0, 0, 0, dp(8));
        root.addView(progress);
        // Progress bar
        android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(concepts.size()); bar.setProgress(currentIndex);
        bar.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(6)));
        root.addView(bar); space(root, 16);
    }

    private void nextQuestion() {
        if (currentIndex >= concepts.size()) {
            finishSession();
            return;
        }
        showProgress();
        LearningConcept concept = concepts.get(currentIndex);
        List<LearningQuestion> questions = db.getQuestions(concept.id);
        if (questions.isEmpty()) {
            currentIndex++;
            nextQuestion();
            return;
        }
        // Pick least-asked question
        questions.sort((a, b) -> Integer.compare(a.timesAsked, b.timesAsked));
        currentQuestion = questions.get(0);
        showQuestion();
    }

    private void showQuestion() {
        body = column(); body.setPadding(0, dp(16), 0, 0);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView conceptLabel = text("Concept: " + currentQuestion.conceptId, 12, 0xff68728c);
        root.addView(conceptLabel); space(root, 8);

        TextView qText = text(currentQuestion.question, 19, 0xff202742);
        qText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        qText.setLineSpacing(dp(4), 1);
        root.addView(qText); space(root, 24);

        if (!answering) {
            LinearLayout actions = row();
            Button show = button("Show Answer", true, v -> showAnswer());
            actions.addView(show, new LinearLayout.LayoutParams(0, dp(48), 1));
            Button skip = button("Skip", false, v -> { currentIndex++; nextQuestion(); });
            actions.addView(skip, new LinearLayout.LayoutParams(dp(80), dp(48)));
            root.addView(actions);
        } else {
            showAnswerControls();
        }
        questionStartTime = System.currentTimeMillis();
    }

    private void showAnswer() {
        answering = true;
        // Remove the last added view (actions) and add answer
        root.removeViewAt(root.getChildCount() - 1);
        TextView aText = text("Answer: " + currentQuestion.answer, 16, 0xff25766c);
        aText.setLineSpacing(dp(3), 1);
        root.addView(aText); space(root, 16);
        showAnswerControls();
    }

    private void showAnswerControls() {
        LinearLayout actions = row();
        actions.addView(button("Correct", true, v -> recordAnswer(true, 3)), new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(button("Partial", false, v -> recordAnswer(true, 2)), new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(button("Incorrect", false, v -> recordAnswer(false, 0)), new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(actions);
    }

    private void recordAnswer(boolean correct, int quality) {
        long timeTaken = System.currentTimeMillis() - questionStartTime;
        LearningScheduler.applyReview(this, db, db.getConcepts(0).get(0), quality, currentQuestion, correct, "", timeTaken);
        // Simplified: just move to next
        LearningScheduler.recordAnswer(db, db.getSession(sessionId), currentQuestion, "", correct, quality, timeTaken);
        currentIndex++;
        answering = false;
        nextQuestion();
    }

    private void finishSession() {
        LearningSession session = db.getSession(sessionId);
        if (session != null) {
            LearningScheduler.completeSession(db, session);
        }
        Toast.makeText(this, "Session complete! " + session.correctAnswers + "/" + session.totalQuestions + " correct.", Toast.LENGTH_LONG).show();
        finish();
    }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private TextView text(String s, float sp, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t; }
    private Button button(String s, boolean accent, View.OnClickListener l) { Button b = new Button(this); b.setText(s); b.setTextSize(14); b.setOnClickListener(l); b.setMinHeight(dp(48)); return b; }
    private void space(LinearLayout p, int dp) { View v = new View(this); p.addView(v, new LinearLayout.LayoutParams(-1, dp)); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}