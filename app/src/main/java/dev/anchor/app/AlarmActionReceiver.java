package dev.anchor.app;

import android.content.*;

/** Explicit, non-exported notification controls, with occurrence-token validation. */
public final class AlarmActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i) {
        try { RingingAlarms.handleAction(c,i); }
        catch(IllegalArgumentException ignored) { /* Reject malformed or obsolete snooze actions. */ }
    }
}
