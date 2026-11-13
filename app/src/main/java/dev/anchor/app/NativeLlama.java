package dev.anchor.app;

import java.nio.charset.StandardCharsets;

/** Thin CPU-only JNI boundary. Call load/generate/free on one background executor. */
public final class NativeLlama {
    private static final String loadError;
    static {
        String error = null;
        try { System.loadLibrary("anchor_llama"); }
        catch (UnsatisfiedLinkError e) { error = e.getMessage(); }
        loadError = error;
    }

    public interface TokenListener { void onToken(byte[] text, int count); }
    public static final class Result {
        public final String text;
        public final int promptTokens, outputTokens;
        public final double firstTokenMs, generationMs;
        public final boolean cancelled;
        public Result(byte[] utf8, int prompt, int output, double first, double generation, boolean cancelled) {
            text = new String(utf8, StandardCharsets.UTF_8);
            promptTokens = prompt; outputTokens = output;
            firstTokenMs = first; generationMs = generation; this.cancelled = cancelled;
        }
        public double tokensPerSecond() {
            return outputTokens > 1 && generationMs > 0 ? (outputTokens - 1) * 1000.0 / generationMs : 0;
        }
    }
    public static boolean available() { return loadError == null; }
    public static String unavailableReason() { return loadError == null ? "" : loadError; }
    public static long load(String path, int context, int threads) {
        if (!available()) throw new IllegalStateException("The local AI library is unavailable in this build: " + loadError);
        return nativeLoad(path, context, threads);
    }
    public static void cancel() { if (available()) nativeCancel(); }
    public static void resetCancellation() { if (available()) nativeResetCancellation(); }
    public static void free(long handle) { if (handle != 0 && available()) nativeFree(handle); }
    public static String description(long handle) { return nativeDescription(handle); }
    public static String systemInfo() { return available() ? nativeSystemInfo() : unavailableReason(); }
    public static Result generate(long handle, String[] roles, String[] texts, int maxTokens, TokenListener listener) {
        return nativeGenerate(handle, roles, texts, maxTokens, listener);
    }
    private static native long nativeLoad(String path, int context, int threads);
    private static native void nativeFree(long handle);
    private static native void nativeCancel();
    private static native void nativeResetCancellation();
    private static native String nativeDescription(long handle);
    private static native String nativeSystemInfo();
    private static native Result nativeGenerate(long handle, String[] roles, String[] texts, int maxTokens, TokenListener listener);
}
