package dev.anchor.app;

public final class LearningSource {
    public long id;
    public String type;
    public String title;
    public String author;
    public String uri;
    public String filePath;
    public int totalChunks;
    public long createdAt;
    public long updatedAt;

    public static final String TYPE_BOOK = "book";
    public static final String TYPE_VIDEO = "video";
    public static final String TYPE_NOTE = "note";
    public static final String TYPE_PDF = "pdf";
    public static final String TYPE_ARTICLE = "article";
    public static final String TYPE_COURSE = "course";
}