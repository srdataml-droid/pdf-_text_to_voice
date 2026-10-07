package com.novaxis.localreader;

import android.app.Activity;
import android.content.Intent;
import android.util.Base64;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.OutputStream;

@CapacitorPlugin(name = "LocalFiles")
public class LocalFilesPlugin extends Plugin {
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
