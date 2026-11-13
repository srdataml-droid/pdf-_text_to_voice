package dev.anchor.app;

public class ChatMessage {
    public long id, createdAt, taskId, taskVersion;
    public boolean canUndoCreation,creationUndone;
    public String role="", content="";
}
