package com.novaxis.localreader;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.WindowManager;
import android.util.Base64;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.OutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;

@CapacitorPlugin(name = "LocalFiles")
public class LocalFilesPlugin extends Plugin {
    private static final int MAX_STORAGE_ENTRIES = 20000;
    private static final long STORAGE_SCAN_MILLIS = 2000;

    @PluginMethod
    public void setReadingActive(PluginCall call) {
        Activity activity = getActivity();
        if (activity == null) { call.reject("The reader is not connected to a screen."); return; }
        boolean active = Boolean.TRUE.equals(call.getBoolean("active", false));
        activity.runOnUiThread(() -> {
            if (active) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            call.resolve();
        });
    }

    @PluginMethod
    public void getStorageInfo(PluginCall call) {
        new Thread(() -> {
            try {
                StatFs volume = new StatFs(getContext().getFilesDir().getAbsolutePath());
                StorageEstimate estimate = estimateAppData(getContext().getDataDir());
                JSObject info = new JSObject();
                info.put("totalBytes", volume.getTotalBytes());
                info.put("freeBytes", volume.getAvailableBytes());
                info.put("appBytes", estimate.bytes);
                info.put("appBytesPartial", estimate.partial);
                info.put("libraryLocation", "Private app storage");
                call.resolve(info);
            } catch (Exception error) {
                call.reject("Storage information is not available on this device.", error);
            }
        }, "reader-storage-info").start();
    }

    @PluginMethod
    public void openAppStorageSettings(PluginCall call) {
        Activity activity = getActivity();
        if (activity == null) { call.reject("The reader is not connected to an active screen."); return; }
        activity.runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.fromParts("package", getContext().getPackageName(), null));
                activity.startActivity(intent);
                call.resolve();
            } catch (Exception error) {
                call.reject("This device could not open the app storage settings.", error);
            }
        });
    }

    // Count private files, including WebView IndexedDB/databases, without
    // opening their contents. APK assets are outside this data-directory estimate.
    private StorageEstimate estimateAppData(File directory) throws IOException {
        StorageEstimate estimate = new StorageEstimate();
        File root = directory.getCanonicalFile();
        String rootPrefix = root.getAbsolutePath() + File.separator;
        ArrayDeque<File> pending = new ArrayDeque<>();
        pending.push(root);
        long deadline = SystemClock.elapsedRealtime() + STORAGE_SCAN_MILLIS;
        int[] accepted = { 1 };
        while (!pending.isEmpty()) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                estimate.partial = true;
                break;
            }
            File entry = pending.pop();
            try {
                String path = entry.getCanonicalPath();
                // Skip symbolic links and paths outside private app storage.
                if (!path.equals(entry.getAbsolutePath()) ||
                    (!entry.equals(root) && !path.startsWith(rootPrefix))) continue;
                if (entry.isFile()) {
                    if (!entry.canRead()) { estimate.partial = true; continue; }
                    long bytes = entry.length();
                    estimate.bytes = bytes > Long.MAX_VALUE - estimate.bytes ? Long.MAX_VALUE : estimate.bytes + bytes;
                } else if (entry.isDirectory()) {
                    String name = entry.getName().toLowerCase(java.util.Locale.ROOT);
                    if (!entry.equals(root) && (name.equals("cache") || name.equals("code_cache") || name.endsWith("cache"))) continue;
                    File[] children = entry.listFiles(child -> {
                        if (accepted[0] >= MAX_STORAGE_ENTRIES || SystemClock.elapsedRealtime() >= deadline) {
                            estimate.partial = true;
                            return false;
                        }
                        accepted[0]++;
                        return true;
                    });
                    if (children == null) { estimate.partial = true; continue; }
                    for (File child : children) pending.push(child);
                }
            } catch (IOException | SecurityException error) {
                estimate.partial = true;
            }
        }
        return estimate;
    }

    private static class StorageEstimate {
        long bytes;
        boolean partial;
    }

    @PluginMethod
    public void saveBackup(PluginCall call) {
        if (call.getString("data") == null) {
            call.reject("The backup is empty.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, "novaxis-reader-backup.zip");
        startActivityForResult(call, intent, "saveResult");
    }

    @ActivityCallback
    private void saveResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            call.reject("Backup save cancelled.");
            return;
        }
        new Thread(() -> {
            try (OutputStream output = getContext().getContentResolver().openOutputStream(result.getData().getData())) {
                if (output == null) throw new java.io.IOException("Could not open the selected file.");
                output.write(Base64.decode(call.getString("data"), Base64.DEFAULT));
                output.flush();
                call.resolve(new JSObject());
            } catch (Exception error) {
                call.reject("Could not save the backup to this device.", error);
            }
        }).start();
    }
}
