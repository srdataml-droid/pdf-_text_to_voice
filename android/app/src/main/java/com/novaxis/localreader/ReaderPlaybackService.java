package com.novaxis.localreader;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.*;
import android.os.*;
import android.util.Log;
import androidx.core.content.ContextCompat;
import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;

/** Owns Piper synthesis and PCM playback independently of the WebView and screen. */
public class ReaderPlaybackService extends Service {
    public static final String STATE_EVENT = "com.novaxis.localreader.PLAYBACK_STATE";
    public static final String PREFS = "native-reader-playback";
    private static final String CHANNEL = "book-narration";
    private static final int NOTIFICATION = 3101;
    private static WeakReference<ReaderPlaybackService> current = new WeakReference<>(null);
    public static ReaderPlaybackService current() { return current.get(); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService synthesis = Executors.newSingleThreadExecutor();
    private final ExecutorService output = Executors.newSingleThreadExecutor();
    private final Object stateLock = new Object();
    private volatile Session session;
    private volatile AudioTrack track;
    private OfflineTts engine; // Accessed exclusively by the synthesis executor.
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock wakeLock;
    private boolean resumeOnFocus;
    private String state = "stopped", bookId = "", planId = "", title = "Novaxis Reader", error = "";
    private int position;
    private long rawHead, headWrap;
    private AudioTrack headTrack;
    private long wakeRenewed;
    private Future<?> producer, writer;
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { pauseReading(); }
    };
    private final AudioManager.OnAudioFocusChangeListener focus = change -> main.post(() -> {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (resumeOnFocus) { resumeOnFocus = false; resumeReading(); }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            resumeOnFocus = session != null && !session.paused;
            pauseReading(false);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) { resumeOnFocus = false; pauseReading(); }
    });

    private static class Clip {
        final int index; final short[] pcm; final int rate;
        Clip(int index, short[] pcm, int rate) { this.index = index; this.pcm = pcm; this.rate = rate; }
    }
    private static class Marker {
        final int index; final long start, end;
        Marker(int index, long start, long end) { this.index = index; this.start = start; this.end = end; }
    }
    private static class Session {
        final LinkedBlockingQueue<Clip> queue = new LinkedBlockingQueue<>(3);
        final ArrayDeque<Marker> markers = new ArrayDeque<>();
        volatile boolean cancelled, paused, done;
        volatile float speed;
        volatile int nextIndex;
        volatile long written;
        volatile String failure;
        final PlaybackWatchdog watchdog = new PlaybackWatchdog(SystemClock.elapsedRealtime(), 120000);
        long lastHead;
        Session(int index, float speed) { this.nextIndex = index; this.speed = speed; }
    }

    @Override public void onCreate() {
        super.onCreate(); current = new WeakReference<>(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Novaxis:BookNarration");
        wakeLock.setReferenceCounted(false);
        mediaSession = new MediaSession(this, "NovaxisReader");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resumeReading(); }
            @Override public void onPause() { pauseReading(); }
            @Override public void onStop() { stopReading(); }
            @Override public void onSkipToNext() { seek(position + 1); }
            @Override public void onSkipToPrevious() { seek(Math.max(0, position - 1)); }
        }, main);
        mediaSession.setActive(true);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Book narration", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Playback controls for offline book reading");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        ContextCompat.registerReceiver(this, noisy, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        // Enter foreground immediately, including while the first local voice is being prepared.
        state = "loading";
        startForeground(NOTIFICATION, notification());
        main.post(tick);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopReading(); return START_NOT_STICKY; }
        switch (intent.getAction() == null ? "" : intent.getAction()) {
            case "start":
                String filename = intent.getStringExtra("plan");
                if (filename == null || !filename.matches("narration-[a-f0-9-]+")) {
                    if (session == null) stopReading();
                    break;
                }
                File plan = new File(ReaderPlaybackPlugin.plansDir(this), filename);
                if (!ReaderPlaybackPlugin.isCurrentCommand(intent.getLongExtra("command", -1))) {
                    NarrationPlan.delete(plan);
                    if (session == null) stopReading();
                    break;
                }
                title = intent.getStringExtra("title"); bookId = intent.getStringExtra("bookId");
                begin(plan, intent.getIntExtra("index", 0), intent.getFloatExtra("speed", 1));
                break;
            case "pause": pauseReading(); break;
            case "resume": resumeReading(); break;
            case "stop": stopReading(); break;
        }
        return START_NOT_STICKY;
    }

    private volatile File planFolder;
    private volatile int passageCount;
    boolean usesPlan(File folder) { return folder.equals(planFolder); }
    private void releasePlan() {
        File old = planFolder; planFolder = null; passageCount = 0;
        if (old != null) synthesis.submit(() -> NarrationPlan.delete(old));
    }
    private void begin(File plan, int index, float speed) {
        cancelSession(); releasePlan(); planFolder = plan; planId = plan.getName(); passageCount = (int) (new File(plan, "index.bin").length() / 8); position = index; error = "";
        Session next = new Session(index, clampSpeed(speed)); session = next;
        publish("loading");
        if (!takeFocus()) { fail(next, "Audio focus is unavailable. Close other audio and try Play again."); return; }
        holdCpu();
        producer = synthesis.submit(() -> {
            try {
                if (!plan.getCanonicalFile().getParentFile().equals(ReaderPlaybackPlugin.plansDir(this).getCanonicalFile())) throw new IOException("Invalid narration plan");
                try (NarrationPlan values = new NarrationPlan(plan)) {
                    if (!owns(next)) return;
                    if (index < 0 || index >= values.size()) throw new IOException("The reading position is outside this book.");
                    produce(next, values, index);
                }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            catch (Exception | LinkageError ex) { queueFailure(next, "Piper could not prepare this book. Check local storage and retry Play."); }
        });
        startWriter(next);
    }

    public void seek(int index) {
        File folder = planFolder; Session old = session;
        if (folder == null || old == null || passageCount == 0) return;
        index = Math.max(0, Math.min(index, passageCount - 1));
        float speed = old.speed;
        cancelSession(); position = index; error = "";
        Session next = new Session(index, speed); session = next;
        publish("loading");
        if (!takeFocus()) { fail(next, "Audio focus is unavailable. Try Play again."); return; }
        holdCpu(); final int start = index;
        producer = synthesis.submit(() -> {
            try (NarrationPlan values = new NarrationPlan(folder)) { produce(next, values, start); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            catch (Exception | LinkageError ex) { queueFailure(next, "The stored narration plan could not be read. Retry Play from your saved place."); }
        });
        startWriter(next);
    }

    private void produce(Session own, NarrationPlan values, int start) throws Exception {
        for (int i = start; i < values.size() && owns(own); i++) {
            awaitResume(own);
            if (!owns(own)) return;
            own.nextIndex = i;
            String text = Normalizer.normalize(values.text(i), Normalizer.Form.NFKC)
                .replaceAll("[\\u00ad\\u200b\\ufeff]", "").replaceAll("\\s+", " ").trim();
            if (!text.matches("(?s).*?[\\p{L}\\p{N}].*")) { own.watchdog.progress(SystemClock.elapsedRealtime()); continue; }
            GeneratedAudio audio;
            try {
                audio = SpeechRecovery.run(() -> {
                    awaitResume(own);
                    if (!owns(own)) throw new InterruptedException();
                    ensureEngine();
                    GeneratedAudio result = engine.generateWithCallback(text, 0, own.speed, new PiperSynthesisCallback(() -> owns(own)));
                    if (result.getSamples().length == 0 || result.getSampleRate() < 8000 || result.getSampleRate() > 48000 || result.getSamples().length > result.getSampleRate() * 90) throw new IOException("Empty speech result");
                    return result;
                }, () -> {
                    Log.w("NovaxisPlayback", "Retrying speech at passage " + own.nextIndex);
                    releaseEngine();
                }, () -> owns(own));
            } catch (InterruptedException cancelled) { throw cancelled; }
            catch (Exception | LinkageError ex) {
                // Drain already prepared speech before reporting a failed following passage.
                Log.e("NovaxisPlayback", "Speech failure at passage " + own.nextIndex + ": " + ex.getClass().getSimpleName());
                own.failure = "Piper could not read the next passage. Your place is saved; retry Play or choose another voice.";
                own.done = true; return;
            }
            if (!owns(own)) return;
            float[] samples = audio.getSamples();
            short[] pcm = new short[samples.length];
            for (int j = 0; j < samples.length; j++) pcm[j] = (short) Math.round(Math.max(-1f, Math.min(1f, samples[j])) * 32767f);
            own.watchdog.progress(SystemClock.elapsedRealtime());
            Clip clip = new Clip(i, pcm, audio.getSampleRate());
            while (owns(own) && !own.queue.offer(clip, 250, TimeUnit.MILLISECONDS)) { /* Bounded look-ahead. */ }
        }
        if (owns(own)) own.done = true;
    }

    private void startWriter(Session own) {
        writer = output.submit(() -> {
            try {
                AudioTrack local = null;
                while (owns(own)) {
                    awaitResume(own);
                    if (!owns(own)) return;
                    Clip clip = own.queue.poll(200, TimeUnit.MILLISECONDS);
                    if (clip == null) {
                        if (own.done && own.queue.isEmpty()) {
                            while (owns(own) && local != null && played(local) < own.written) { awaitResume(own); Thread.sleep(30); }
                            if (owns(own)) main.post(() -> { if (own.failure != null) fail(own, own.failure); else finish(own); });
                            return;
                        }
                        if (local != null && played(local) >= own.written) main.post(() -> {
                            if (owns(own) && !own.paused && state.equals("playing")) publish("loading");
                        });
                        continue;
                    }
                    if (local == null) {
                        int bytes = Math.max(AudioTrack.getMinBufferSize(clip.rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4, clip.rate * 4);
                        local = new AudioTrack.Builder().setAudioAttributes(attributes())
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(clip.rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(bytes).setTransferMode(AudioTrack.MODE_STREAM).build();
                        if (local.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("Audio output could not initialize");
                        synchronized (stateLock) {
                            if (!owns(own)) { local.release(); return; }
                            track = local;
                            if (!own.paused) local.play();
                        }
                    }
                    synchronized (stateLock) { own.markers.add(new Marker(clip.index, own.written, own.written + clip.pcm.length)); }
                    int offset = 0;
                    while (offset < clip.pcm.length && owns(own)) {
                        awaitResume(own);
                        if (!owns(own)) return;
                        int written = local.write(clip.pcm, offset, Math.min(4096, clip.pcm.length - offset), AudioTrack.WRITE_BLOCKING);
                        if (written < 0) throw new IOException("Audio output failed: " + written);
                        offset += written;
                        if (written == 0) Thread.sleep(10);
                    }
                    own.written += clip.pcm.length;
                }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            catch (Exception ex) { fail(own, "The audio output stopped. Try Play again from your saved passage."); }
        });
    }

    private void releaseEngine() {
        if (engine != null) { engine.release(); engine = null; }
    }
    private void ensureEngine() throws IOException {
        if (engine != null) return;
        File root = new File(getFilesDir(), "native-piper-1.13.8");
        copyAssets("native-piper", root);
        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
        vits.setModel(new File(root, "en_US-lessac-medium.onnx").getAbsolutePath());
        vits.setTokens(new File(root, "tokens.txt").getAbsolutePath());
        vits.setDataDir(new File(root, "espeak-ng-data").getAbsolutePath());
        OfflineTtsModelConfig model = new OfflineTtsModelConfig();
        model.setVits(vits); model.setNumThreads(2); model.setProvider("cpu"); model.setDebug(false);
        OfflineTtsConfig config = new OfflineTtsConfig(); config.setModel(model); config.setMaxNumSentences(1); config.setSilenceScale(.2f);
        engine = new OfflineTts(null, config);
    }
    private void copyAssets(String path, File target) throws IOException {
        String[] children = getAssets().list(path);
        if (children != null && children.length > 0) {
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Voice storage could not be created");
            for (String child : children) copyAssets(path + "/" + child, new File(target, child));
        } else if (!target.isFile() || target.length() == 0) {
            File pending = new File(target.getPath() + ".part");
            try (InputStream in = getAssets().open(path); OutputStream out = new FileOutputStream(pending)) {
                byte[] bytes = new byte[65536]; int count;
                while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
            }
            if (!pending.renameTo(target)) throw new IOException("Voice installation could not finish");
        }
    }

    public void pauseReading() { resumeOnFocus = false; pauseReading(true); }
    private void pauseReading(boolean abandon) {
        Session own = session; if (own == null) return;
        syncPosition(); own.paused = true;
        synchronized (stateLock) { if (track != null) track.pause(); }
        releaseCpu(); if (abandon) abandonFocus(); publish("paused");
    }
    public void resumeReading() {
        Session own = session; if (own == null || own.cancelled) return;
        if (!takeFocus()) { publish("paused"); return; }
        holdCpu(); own.watchdog.progress(SystemClock.elapsedRealtime()); own.paused = false;
        synchronized (stateLock) { if (track != null) track.play(); stateLock.notifyAll(); }
        publish(track == null ? "loading" : "playing");
    }
    public void speed(float value) { if (session != null) session.speed = clampSpeed(value); }
    public void stopReading() {
        syncPosition(); cancelSession(); releasePlan(); publish("stopped");
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    private void finish(Session own) {
        if (!owns(own)) return;
        position = Math.max(0, passageCount - 1);
        cancelSession(); releasePlan(); publish("ended"); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    private void queueFailure(Session own, String message) {
        if (owns(own)) { own.failure = message; own.done = true; }
    }
    private void fail(Session own, String message) {
        main.post(() -> {
            if (!owns(own)) return;
            syncPosition(); error = message; Log.e("NovaxisPlayback", "Narration failure near passage " + own.nextIndex);
            cancelSession(); releasePlan(); publish("error"); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        });
    }
    private void cancelSession() {
        Session own = session; if (own != null) { own.cancelled = true; own.queue.clear(); }
        session = null;
        if (producer != null) producer.cancel(true);
        if (writer != null) writer.cancel(true);
        synchronized (stateLock) {
            stateLock.notifyAll();
            AudioTrack previous = track; track = null;
            if (previous != null) { try { previous.pause(); previous.flush(); } catch (IllegalStateException ignored) {} previous.release(); }
        }
        releaseCpu(); abandonFocus();
    }
    private boolean owns(Session own) { return session == own && !own.cancelled; }
    private void awaitResume(Session own) throws InterruptedException {
        synchronized (stateLock) { while (owns(own) && own.paused) stateLock.wait(); }
    }
    private synchronized long played(AudioTrack audio) {
        if (headTrack != audio) { headTrack = audio; rawHead = 0; headWrap = 0; }
        long raw = Integer.toUnsignedLong(audio.getPlaybackHeadPosition());
        if (raw < rawHead) headWrap += 1L << 32;
        rawHead = raw; return headWrap + raw;
    }
    private void syncPosition() {
        Session own = session; AudioTrack audio = track;
        if (own == null || audio == null) return;
        long head;
        try { head = played(audio); } catch (IllegalStateException ex) { return; }
        if (head > own.lastHead) { own.lastHead = head; own.watchdog.progress(SystemClock.elapsedRealtime()); }
        synchronized (stateLock) {
            while (own.markers.size() > 1 && own.markers.peek().end <= head) own.markers.remove();
            Marker marker = own.markers.peek();
            if (marker != null && marker.start <= head && position != marker.index) { position = marker.index; publish(own.paused ? "paused" : "playing"); }
            else if (marker != null && head > marker.start && !own.paused && state.equals("loading")) publish("playing");
        }
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            syncPosition();
            Session own = session;
            if (own != null) {
                if (own.watchdog.expired(SystemClock.elapsedRealtime(), own.paused)) fail(own, "Narration made no progress for two minutes. Your place is saved. Press Play to retry.");
                else if (!own.paused) holdCpu();
            }
            main.postDelayed(this, 250);
        }
    };
    private void holdCpu() {
        long now = SystemClock.elapsedRealtime();
        if (!wakeLock.isHeld() || now - wakeRenewed > 5 * 60 * 1000L) { wakeLock.acquire(10 * 60 * 1000L); wakeRenewed = now; }
    }
    private void releaseCpu() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
    private AudioAttributes attributes() { return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(); }
    private boolean takeFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes()).setOnAudioFocusChangeListener(focus, main).setWillPauseWhenDucked(true).build();
            return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return audioManager.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }
    private void abandonFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        else audioManager.abandonAudioFocus(focus);
    }
    private static float clampSpeed(float speed) { return Math.max(.7f, Math.min(2f, speed)); }

    private void publish(String next) {
        state = next;
        long now = System.currentTimeMillis();
        SharedPreferences.Editor saved = getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("planId", planId).putString("bookId", bookId).putInt("index", position).putString("state", state).putString("error", error).putLong("savedAt", now);
        if (!bookId.isEmpty()) saved.putString("progress:" + bookId, "{\"index\":" + position + ",\"savedAt\":" + now + "}");
        saved.apply();
        Intent event = new Intent(STATE_EVENT).setPackage(getPackageName()); sendBroadcast(event);
        int playback = state.equals("playing") ? PlaybackState.STATE_PLAYING : state.equals("loading") ? PlaybackState.STATE_BUFFERING : state.equals("paused") ? PlaybackState.STATE_PAUSED : state.equals("error") ? PlaybackState.STATE_ERROR : PlaybackState.STATE_STOPPED;
        mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS).setState(playback, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1).build());
        mediaSession.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).putString(MediaMetadata.METADATA_KEY_ARTIST, "Novaxis Reader · Piper").build());
        if (!state.equals("stopped") && !state.equals("ended") && !state.equals("error")) ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification());
    }
    private PendingIntent action(String command, int id) {
        return PendingIntent.getService(this, id, new Intent(this, ReaderPlaybackService.class).setAction(command), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 20, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        boolean paused = state.equals("paused");
        return builder.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(title == null ? "Novaxis Reader" : title)
            .setContentText(state.equals("loading") ? "Preparing offline narration…" : paused ? "Paused" : "Reading with Piper")
            .setContentIntent(content).setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setOngoing(!paused)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(new Notification.Action.Builder(paused ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause, paused ? "Play" : "Pause", action(paused ? "resume" : "pause", 1)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action("stop", 2)).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0, 1)).build();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null); cancelSession(); releasePlan();
        try { unregisterReceiver(noisy); } catch (IllegalArgumentException ignored) {}
        mediaSession.release(); current.clear();
        synthesis.submit(this::releaseEngine);
        synthesis.shutdown(); output.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
