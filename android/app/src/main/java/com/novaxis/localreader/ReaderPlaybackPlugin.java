package com.novaxis.localreader;

import android.content.*;
import android.os.*;
import androidx.core.content.ContextCompat;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@CapacitorPlugin(name = "ReaderPlayback")
public class ReaderPlaybackPlugin extends Plugin {
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver;
    private static final AtomicLong commands = new AtomicLong();
    static boolean isCurrentCommand(long command) { return commands.get() == command; }
    @Override public void load() {
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { notifyListeners("state", snapshot()); }
        };
        ContextCompat.registerReceiver(getContext(), receiver, new IntentFilter(ReaderPlaybackService.STATE_EVENT), ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    private JSObject snapshot() {
        SharedPreferences prefs = getContext().getSharedPreferences(ReaderPlaybackService.PREFS, Context.MODE_PRIVATE);
        JSObject result = new JSObject();
        result.put("bookId", prefs.getString("bookId", "")); result.put("index", prefs.getInt("index", 0));
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
    @PluginMethod public void start(PluginCall call) {
        long command = commands.incrementAndGet();
        JSArray segments = call.getArray("segments");
        String id = call.getString("bookId", ""), title = call.getString("title", "Your book");
        int index = call.getInt("index", 0); float speed = call.getFloat("speed", 1f);
        if (segments == null || segments.length() == 0 || segments.length() > 200000 || id.isEmpty() || id.length() > 512 || index < 0 || index >= segments.length()) {
            call.reject("This book has no valid narration plan."); return;
        }
        files.execute(() -> {
            File plan = null;
            try {
                byte[] data = new JSONObject().put("segments", segments).toString().getBytes(StandardCharsets.UTF_8);
                if (data.length > 32 * 1024 * 1024) throw new IOException("The book is too large for a narration session");
                plan = File.createTempFile("narration-", ".json", getContext().getCacheDir());
                try (OutputStream out = new FileOutputStream(plan)) { out.write(data); }
                final File file = plan;
                main.post(() -> {
                    try {
                        if (commands.get() != command) { file.delete(); call.reject("Narration start cancelled."); return; }
                        Intent intent = new Intent(getContext(), ReaderPlaybackService.class).setAction("start")
                            .putExtra("command", command).putExtra("plan", file.getName()).putExtra("bookId", id).putExtra("title", title).putExtra("index", index).putExtra("speed", speed);
                        ContextCompat.startForegroundService(getContext(), intent); call.resolve();
                    } catch (Exception error) { file.delete(); call.reject("Android could not start background narration. Open the app and press Play again."); }
                });
            } catch (Exception error) { if (plan != null) plan.delete(); call.reject("The local narration plan could not be prepared."); }
        });
    }
    @PluginMethod public void pause(PluginCall call) { commands.incrementAndGet(); main.post(() -> { ReaderPlaybackService service = ReaderPlaybackService.current(); if (service != null) service.pauseReading(); call.resolve(snapshot()); }); }
    @PluginMethod public void resume(PluginCall call) { main.post(() -> { ReaderPlaybackService service = ReaderPlaybackService.current(); if (service != null) service.resumeReading(); call.resolve(snapshot()); }); }
    @PluginMethod public void seek(PluginCall call) { main.post(() -> { ReaderPlaybackService service = ReaderPlaybackService.current(); if (service != null) service.seek(call.getInt("index", 0)); call.resolve(snapshot()); }); }
    @PluginMethod public void stop(PluginCall call) { commands.incrementAndGet(); main.post(() -> { ReaderPlaybackService service = ReaderPlaybackService.current(); if (service != null) service.stopReading(); call.resolve(snapshot()); }); }
    @PluginMethod public void setSpeed(PluginCall call) { main.post(() -> { ReaderPlaybackService service = ReaderPlaybackService.current(); if (service != null) service.speed(call.getFloat("speed", 1f)); call.resolve(); }); }
    @Override protected void handleOnResume() { notifyListeners("state", snapshot()); }
    @Override protected void handleOnDestroy() {
        if (receiver != null) getContext().unregisterReceiver(receiver);
        files.shutdown();
    }
}
