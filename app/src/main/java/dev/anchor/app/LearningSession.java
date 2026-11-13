package dev.anchor.app;

public final class LearningSession {
    public long id;
    public String date;
    public long startedAt;
    public Long completedAt;
    public int totalQuestions;
    public int correctAnswers;
    public String mode;

    public static final String MODE_REVIEW = "review";
    public static final String MODE_QUIZ = "quiz";
    public static final String MODE_DAILY = "daily";
    public static final String MODE_WEEKLY = "weekly";
}