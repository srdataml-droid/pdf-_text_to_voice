package dev.anchor.app;

import android.content.*;

public class RescheduleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i) {
        PendingResult pending=goAsync();
        new Thread(()->{
            // Rebuild future alarms only; Android 15+ forbids media-playback FGS from boot.
            try(AnchorDb db=new AnchorDb(c)) { PlannerObserver.observe(c,db,"Device clock or restart"); }
            finally { pending.finish(); }
        },"anchor-reschedule").start();
    }
}
