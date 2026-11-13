package dev.anchor.app;

public final class LearningAnswer {
    public long id;
    public long sessionId;
    public long questionId;
    public String userAnswer;
    public boolean isCorrect;
    public int confidence;
    public long timeTakenMs;
    public long answeredAt;
}