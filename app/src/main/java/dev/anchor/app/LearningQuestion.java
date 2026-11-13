package dev.anchor.app;

public final class LearningQuestion {
    public long id;
    public long conceptId;
    public String type;
    public String question;
    public String answer;
    public int difficulty;
    public int timesAsked;
    public int timesCorrect;
    public long createdAt;

    public static final String TYPE_COMPREHENSION = "comprehension";
    public static final String TYPE_ACTIVE_RECALL = "active_recall";
    public static final String TYPE_REASONING = "reasoning";
    public static final String TYPE_APPLICATION = "application";
}