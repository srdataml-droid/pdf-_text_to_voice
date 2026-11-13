package dev.anchor.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/** Spaced repetition scheduler for learning reviews. Integrates with existing alarm system. */
public final class LearningScheduler {
    private static final String PREFS = "learning-scheduler";
    private static final long DAY_MS = 86400000L;
    private static final double INITIAL_EASE = 2.5;
    private static final int MIN_INTERVAL = 1;
    private static final int MAX_INTERVAL = 365;

    private LearningScheduler() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Calculate next review interval using SM-2 algorithm variant. */
    public static int calculateInterval(LearningConcept concept, int quality) {
        // quality: 0=again, 1=hard, 2=good, 3=easy
        double ef = concept.easeFactor;
        int interval = concept.reviewIntervalDays;

        if (quality >= 2) {
            if (interval == 0) interval = 1;
            else if (interval == 1) interval = 6;
            else interval = (int) Math.round(interval * ef);
        } else {
            interval = 1;
        }

        // Update ease factor
        ef = Math.max(1.3, ef + (0.1 - (3 - quality) * (0.08 + (3 - quality) * 0.02)));

        interval = Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, interval));
        return interval;
    }

    /** Apply review result to concept and return updated next review date. */
    public static long applyReview(Context context, AnchorDb db, LearningConcept concept, int quality, LearningQuestion question, boolean correct, String userAnswer, long timeTakenMs) {
        int newInterval = calculateInterval(concept, quality);
        concept.easeFactor = Math.max(1.3, concept.easeFactor + (0.1 - (3 - quality) * (0.08 + (3 - quality) * 0.02)));
        concept.reviewIntervalDays = newInterval;
        concept.lastReviewed = System.currentTimeMillis();
        concept.mastery = Math.min(1.0, concept.mastery + (correct ? 0.1 : -0.05));
        concept.updatedAt = System.currentTimeMillis();
        db.saveConcept(concept);

        // Save answer record
        if (question != null) {
            LearningAnswer answer = new LearningAnswer();
            answer.sessionId = 0; // standalone
            answer.questionId = question.id;
            answer.userAnswer = userAnswer != null ? userAnswer : "";
            answer.isCorrect = correct;
            answer.confidence = quality;
            answer.timeTakenMs = timeTakenMs;
            answer.answeredAt = System.currentTimeMillis();
            db.saveAnswer(answer);
        }

        // Schedule next review alarm
        long nextReview = System.currentTimeMillis() + newInterval * DAY_MS;
        scheduleReviewAlarm(context, db, concept.id, nextReview);
        return nextReview;
    }

    /** Get concepts due for review. */
    public static List<LearningConcept> getDueConcepts(AnchorDb db) {
        return db.getConceptsDueForReview();
    }

    /** Get concepts due today for a daily review session. */
    public static List<LearningConcept> getDailyReviewConcepts(AnchorDb db, int maxItems) {
        List<LearningConcept> due = getDueConcepts(db);
        due.sort(Comparator.comparingDouble(c -> -c.mastery)); // weakest first
        return due.subList(0, Math.min(maxItems, due.size()));
    }

    /** Create a review session with questions for the given concepts. */
    public static LearningSession createReviewSession(AnchorDb db, List<LearningConcept> concepts, String mode) {
        List<Long> conceptIds = new ArrayList<>();
        for (LearningConcept c : concepts) conceptIds.add(c.id);

        List<LearningQuestion> questions = new ArrayList<>();
        for (Long cid : conceptIds) {
            List<LearningQuestion> qs = db.getQuestions(cid);
            if (!qs.isEmpty()) {
                // Pick the least-asked question
                qs.sort(Comparator.comparingInt(q -> q.timesAsked));
                questions.add(qs.get(0));
            }
        }

        LearningSession session = new LearningSession();
        session.date = LocalDate.now().toString();
        session.startedAt = System.currentTimeMillis();
        session.mode = mode;
        session.totalQuestions = questions.size();
        session.correctAnswers = 0;
        long sessionId = db.saveSession(session);

        // Update questions with session link (we'd need to extend LearningQuestion for this)
        // For now, questions are tracked via answers

        return session;
    }

    /** Record answer in session and update stats. */
    public static void recordAnswer(AnchorDb db, LearningSession session, LearningQuestion question, String userAnswer, boolean correct, int confidence, long timeTakenMs) {
        LearningAnswer answer = new LearningAnswer();
        answer.sessionId = session.id;
        answer.questionId = question.id;
        answer.userAnswer = userAnswer;
        answer.isCorrect = correct;
        answer.confidence = confidence;
        answer.timeTakenMs = timeTakenMs;
        answer.answeredAt = System.currentTimeMillis();
        db.saveAnswer(answer);

        question.timesAsked++;
        if (correct) question.timesCorrect++;
        db.saveQuestion(question);

        session.totalQuestions++;
        if (correct) session.correctAnswers++;
        db.saveSession(session);
    }

    /** Complete a review session. */
    public static void completeSession(AnchorDb db, LearningSession session) {
        session.completedAt = System.currentTimeMillis();
        db.saveSession(session);
    }

    /** Schedule a review alarm for a concept. */
    private static void scheduleReviewAlarm(Context context, AnchorDb db, long conceptId, long triggerAt) {
        // Create a task for the review reminder
        Task task = new Task();
        task.title = "Review: concept #" + conceptId;
        task.date = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(triggerAt), ZoneId.systemDefault()).toLocalDate().toString();
        task.time = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(triggerAt), ZoneId.systemDefault()).toLocalTime().toString();
        task.durationMinutes = 15;
        task.flexible = true;
        task.reminderMinutes = 0;
        task.notes = "Learning review session";
        db.save(task);
        Reminders.reconcile(context, db);
    }

    /** Get review statistics. */
    public static Map<String, Object> getStats(AnchorDb db) {
        Map<String, Object> stats = new HashMap<>();
        List<LearningConcept> all = new ArrayList<>();
        // Get all concepts from all sources
        List<LearningSource> sources = db.getSources(null);
        for (LearningSource s : sources) {
            all.addAll(db.getConcepts(s.id));
        }
        int total = all.size();
        int due = 0, overdue = 0, mastered = 0;
        long now = System.currentTimeMillis();
        for (LearningConcept c : all) {
            if (c.lastReviewed != null) {
                long nextReview = c.lastReviewed + c.reviewIntervalDays * DAY_MS;
                if (nextReview <= now) {
                    if (nextReview < now - DAY_MS) overdue++;
                    else due++;
                }
            } else {
                due++;
            }
            if (c.mastery >= 0.8) mastered++;
        }
        stats.put("total", total);
        stats.put("due", due);
        stats.put("overdue", overdue);
        stats.put("mastered", mastered);
        stats.put("retention", total > 0 ? (double) mastered / total : 0.0);
        return stats;
    }

    /** Enable/disable daily review reminder. */
    public static void setDailyReviewEnabled(Context context, boolean enabled, int hour, int minute) {
        prefs(context).edit().putBoolean("daily-review-enabled", enabled).putInt("daily-review-hour", hour).putInt("daily-review-minute", minute).apply();
        if (enabled) {
            // Schedule daily alarm (would integrate with existing alarm system)
        }
    }

    /** Get next review date for a concept. */
    public static long getNextReviewDate(LearningConcept concept) {
        if (concept.lastReviewed == null) return System.currentTimeMillis();
        return concept.lastReviewed + concept.reviewIntervalDays * DAY_MS;
    }
}