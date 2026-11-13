package dev.anchor.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Rebuild alarms from the database, cancelling previous IDs including deleted entries. */
public final class Reminders {
    static final String CHANNEL="anchor_reminders";
    private static final ExecutorService REFILL=Executors.newSingleThreadExecutor(r->new Thread(r,"anchor-reminder-refill"));
    private Reminders() {}
    static void channel(Context c) {
        NotificationChannel ch=new NotificationChannel(CHANNEL,"Calendar reminders",NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Reminders scheduled on this device");ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }
    static boolean exactAllowed(Context c) {
        return Build.VERSION.SDK_INT<31 || c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }
    public static void cancel(Context c,long id) {
        synchronized(RingingAlarms.class) {
            RingingAlarms.dismiss(c,id);RingingAlarms.removeState(c,id);
        }
    }
    public static void reconcile(Context c,AnchorDb db) {
        synchronized(RingingAlarms.class) {
            channel(c);RingingAlarms.channel(c);
            SharedPreferences p=RingingAlarms.preferences(c);
            Set<String> previous=new HashSet<>(p.getStringSet("scheduled",Collections.emptySet()));
            Set<String> scheduled=new HashSet<>();
            List<Task> entries=db.range("0001-01-01","9999-12-31");
            Map<Long,Long> pendingDue=new HashMap<>();
            long now=System.currentTimeMillis();
            for(Task t:entries) {
                long at=RingingAlarms.baseAt(t);
                if(at<=0) continue;
                String id=Long.toString(t.id);scheduled.add(id);
                long oldVersion=p.getLong("v"+id,-1),oldBase=p.getLong("base"+id,-1);
                if(!RingingAlarms.reconcileSnoozeClock(c,t,at) && (oldVersion!=t.updatedAt || oldBase!=at)) {
                    // Missing base marks migration from the notification-only implementation.
                    if(oldVersion!=-1 && (oldVersion!=t.updatedAt || oldBase!=-1)) cancel(c,t.id);
                    RingingAlarms.initialize(c,t,at,now);
                }
                RingingAlarms.cancelScheduled(c,t.id);
                long token=RingingAlarms.token(c,t.id);
                if("ringing".equals(p.getString("state"+id,"")) && !RingingAlarmService.isRunning(t.id,token)) {
                    // A killed process or reboot stops audio. Never restart an already delivered ring.
                    RingingAlarms.finished(c,t.id,token,false);
                }
                if("pending".equals(p.getString("state"+id,"")))pendingDue.put(t.id,p.getLong("due"+id,-1));
            }
            for(String id:previous) if(!scheduled.contains(id)) {
                try { cancel(c,Long.parseLong(id)); } catch(NumberFormatException ignored) {}
            }
            // Cancel the old queue before replacing it, including when it was near the OS quota.
            p.edit().putBoolean("queue-error",false).commit();
            for(Task t:AlarmQueue.next(entries,pendingDue,AlarmQueue.LIMIT))RingingAlarms.schedule(c,t);
            p.edit().putStringSet("scheduled",scheduled).commit();
            long active=p.getLong("active_id",-1);
            if(active>=0 && (!scheduled.contains(Long.toString(active)) || !RingingAlarms.canRing(c))) RingingAlarms.stopActive(c,active);
        }
    }
    static void refillAfterDelivery(Context context,BroadcastReceiver.PendingResult result) {
        Context app=context.getApplicationContext();
        REFILL.execute(()->{try(AnchorDb db=new AnchorDb(app)){reconcile(app,db);}catch(RuntimeException failed){android.util.Log.w("Anchor","Reminder queue refill will retry on the next calendar check.");}finally{result.finish();}});
    }
    static boolean shouldDeliver(Task task,long version,long expectedAt) {
        return task!=null && !task.done && task.updatedAt==version && expectedAt>0 && DateRules.reminderAt(task,ZoneId.systemDefault())==expectedAt;
    }
    public static String status(Context c) {
        if(!c.getSystemService(NotificationManager.class).areNotificationsEnabled()) return "Notifications are off. Enable reminders in Android settings.";
        NotificationChannel ch=c.getSystemService(NotificationManager.class).getNotificationChannel(CHANNEL);
        if(ch!=null && ch.getImportance()==NotificationManager.IMPORTANCE_NONE) return "The Calendar reminders channel is off in Android settings.";
        if(Build.VERSION.SDK_INT>=31 && !c.getSystemService(AlarmManager.class).canScheduleExactAlarms()) return "Approximate reminders. Android may delay them; allow Alarms & reminders for precise times.";
        if(RingingAlarms.preferences(c).getBoolean("queue-error",false))return "Android could not schedule every reminder. Reopen Anchor to retry and check alarm access.";
        return "Precise reminders enabled. Force-stopping Anchor pauses alarms and reminders until you reopen it.";
    }
    public static void requestAccess(Activity a) {
        channel(a);
        if(Build.VERSION.SDK_INT>=33 && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},481);return;
        }
        if(!a.getSystemService(NotificationManager.class).areNotificationsEnabled()) {
            a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,a.getPackageName()));return;
        }
        if(Build.VERSION.SDK_INT>=31 && !a.getSystemService(AlarmManager.class).canScheduleExactAlarms()) {
            try { a.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+a.getPackageName()))); }
            catch(ActivityNotFoundException e) { a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+a.getPackageName()))); }
        } else {
            a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,a.getPackageName()));
        }
    }
}
