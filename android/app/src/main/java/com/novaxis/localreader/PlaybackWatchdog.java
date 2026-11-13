package com.novaxis.localreader;

/** Monotonic clock supplied by the caller. Pausing resets the stall budget. */
final class PlaybackWatchdog {
    private final long timeout;
    private volatile long lastProgress;
    PlaybackWatchdog(long now, long timeout) { lastProgress = now; this.timeout = timeout; }
    void progress(long now) { lastProgress = now; }
    boolean expired(long now, boolean paused) {
        if (paused) { progress(now); return false; }
        return now - lastProgress >= timeout;
    }
}
