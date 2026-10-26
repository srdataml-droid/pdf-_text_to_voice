package com.novaxis.localreader;

import android.content.*;
import android.os.*;
import androidx.core.content.ContextCompat;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@CapacitorPlugin(name = "ReaderPlayback")
public class ReaderPlaybackPlugin extends Plugin {
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver;
    private static final AtomicLong commands = new AtomicLong();
    static boolean isCurrentCommand(long command) { return commands.get() == command; }
    static File plansDir(Context context) {
        File folder = new File(context.getFilesDir(), "narration-plans");
        folder.mkdirs(); return folder;
    }
    @Override public void load() {
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { notifyListeners("state", snapshot()); }
        };
        ContextCompat.registerReceiver(getContext(), receiver, new IntentFilter(ReaderPlaybackService.STATE_EVENT), ContextCompat.RECEIVER_NOT_EXPORTED);
        files.execute(() -> {
            File[] stale = plansDir(getContext()).listFiles(file -> file.getName().matches("narration-[a-f0-9-]+") && System.currentTimeMillis() - file.lastModified() > 24L * 60 * 60 * 1000);
            ReaderPlaybackService service = ReaderPlaybackService.current();
            if (stale != null) for (File file : stale) if (service == null || !service.usesPlan(file)) NarrationPlan.delete(file);
        });
    }
    private JSObject snapshot() {
        SharedPreferences prefs = getContext().getSharedPreferences(ReaderPlaybackService.PREFS, Context.MODE_PRIVATE);
        JSObject result = new JSObject();
        result.put("planId", prefs.getString("planId", "")); result.put("bookId", prefs.getString("bookId", "")); result.put("index", prefs.getInt("index", 0));
        String state = prefs.getString("state", "stopped");
        boolean alive = ReaderPlaybackService.current() != null;
        if (!alive && (state.equals("playing") || state.equals("loading") || state.equals("paused"))) state = "stopped";
        result.put("state", state); result.put("alive", alive);
        result.put("savedAt", prefs.getLong("savedAt", 0)); result.put("error", prefs.getString("error", ""));
        return result;
    }
    @PluginMethod public void getState(PluginCall call) { call.resolve(snapshot()); }
    @PluginMethod public void getProgress(PluginCall call) {
        String id = call.getString("bookId", "");
        String saved = getContext().getSharedPreferences(ReaderPlaybackService.PREFS, Context.MODE_PRIVATE).getString("progress:" + id, "{}");
        try { call.resolve(new JSObject(saved)); } catch (JSONException error) { call.resolve(new JSObject()); }
    }
    // Only the files executor accesses an upload. Bridge messages contain at most 128 passages.
    private Upload upload;
    private static final class Upload {
        final String id, bookId, title; final int index; final float speed; final long command;
        final NarrationPlan.Writer writer;
        Upload(String id, String bookId, String title, int index, float speed, long command, NarrationPlan.Writer writer) {
            this.id = id; this.bookId = bookId; this.title = title; this.index = index; this.speed = speed; this.command = command; this.writer = writer;
        }
    }
    private void discardUpload() {
        Upload old = upload; upload = null;
        if (old != null) { try { old.writer.close(); } catch (IOException ignored) {} NarrationPlan.delete(old.writer.folder); }
    }
    private void cancelPending() {
        commands.incrementAndGet();
        files.execute(() -> { if (upload != null && !isCurrentCommand(upload.command)) discardUpload(); });
    }
    @PluginMethod public void beginPlan(PluginCall call) {
        long command = commands.incrementAndGet();
        String id = call.getString("bookId", ""), title = call.getString("title", "Your book");
        int count = call.getInt("count", 0), index = call.getInt("index", 0); float speed = call.getFloat("speed", 1f);
        if (count < 1 || count > NarrationPlan.MAX_PASSAGES || id.isEmpty() || id.length() > 512 || index < 0 || index >= count) {
            call.reject("This book exceeds the supported narration limits or has no readable passages."); return;
        }
        files.execute(() -> {
            discardUpload();
            try {
                if (!isCurrentCommand(command)) { call.reject("Narration preparation cancelled."); return; }
                String token = "narration-" + UUID.randomUUID();
                upload = new Upload(token, id, title, index, speed, command, new NarrationPlan.Writer(new File(plansDir(getContext()), token), count));
                JSObject result = new JSObject(); result.put("uploadId", token); call.resolve(result);
            } catch (IOException error) { discardUpload(); call.reject("Not enough local space to prepare narration. Free space and try again."); }
        });
    }
    @PluginMethod public void appendPlan(PluginCall call) {
        JSArray values = call.getArray("segments"); String token = call.getString("uploadId", "");
        files.execute(() -> {
            if (upload == null || !upload.id.equals(token) || !isCurrentCommand(upload.command)) { call.reject("Narration preparation cancelled."); return; }
            try {
                if (values == null || values.length() < 1 || values.length() > NarrationPlan.MAX_BATCH) throw new IOException("Invalid narration batch");
                List<String> texts = new ArrayList<>();
                for (int i = 0; i < values.length(); i++) texts.add(values.getString(i));
                upload.writer.append(texts); call.resolve();
            } catch (Exception error) { discardUpload(); call.reject("Narration could not be stored. Check free space or try a smaller book."); }
        });
    }
    @PluginMethod public void finishPlan(PluginCall call) {
        String token = call.getString("uploadId", "");
        files.execute(() -> {
            if (upload == null || !upload.id.equals(token) || !isCurrentCommand(upload.command)) { call.reject("Narration preparation cancelled."); return; }
            Upload ready = upload;
            try { ready.writer.finish(); upload = null; }
            catch (IOException error) { discardUpload(); call.reject("Narration transfer did not finish. Try Play again."); return; }
            main.post(() -> {
                try {
                    if (!isCurrentCommand(ready.command)) { NarrationPlan.delete(ready.writer.folder); call.reject("Narration preparation cancelled."); return; }
                    Intent intent = new Intent(getContext(), ReaderPlaybackService.class).setAction("start")
                        .putExtra("command", ready.command).putExtra("plan", ready.id).putExtra("bookId", ready.bookId).putExtra("title", ready.title).putExtra("index", ready.index).putExtra("speed", ready.speed);
                    ContextCompat.startForegroundService(getContext(), intent); call.resolve();
                } catch (Exception error) { NarrationPlan.delete(ready.writer.folder); call.reject("Android could not start narration. Open the app and press Play again."); }
            });
        });
    }
    @PluginMethod public void abortPlan(PluginCall call) {
        String token = call.getString("uploadId", "");
        files.execute(() -> { if (upload != null && upload.id.equals(token)) discardUpload(); call.resolve(); });
    }
    @PluginMethod public void pause(PluginCall call) { cancelPending(); main.post(() -> { ReaderPlaybackService service = ReaderPlaybackService.current(); if (service != null) service.pauseReading(); call.resolve(snapshot()); }); }
    @PluginMethod public void resume(PluginCall call) { main.post(() -> { ReaderPlaybackService service = ReaderPlaybackService.current(); if (service != null) service.resumeReading(); call.resolve(snapshot()); }); }
    @PluginMethod public void seek(PluginCall call) { main.post(() -> { ReaderPlaybackService service = ReaderPlaybackService.current(); if (service != null) service.seek(call.getInt("index", 0)); call.resolve(snapshot()); }); }
    @PluginMethod public void stop(PluginCall call) { cancelPending(); main.post(() -> { ReaderPlaybackService service = ReaderPlaybackService.current(); if (service != null) service.stopReading(); call.resolve(snapshot()); }); }
    @PluginMethod public void setSpeed(PluginCall call) { main.post(() -> { ReaderPlaybackService service = ReaderPlaybackService.current(); if (service != null) service.speed(call.getFloat("speed", 1f)); call.resolve(); }); }
    @Override protected void handleOnResume() { notifyListeners("state", snapshot()); }
    @Override protected void handleOnDestroy() {
        if (receiver != null) getContext().unregisterReceiver(receiver);
        files.execute(this::discardUpload); files.shutdown();
    }
}
