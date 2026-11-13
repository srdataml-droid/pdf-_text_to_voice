package dev.anchor.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import java.time.ZoneId;
import java.util.*;

/** Native alarm state. Snoozing changes an occurrence, never the calendar entry. */
public final class RingingAlarms {
    static final String CHANNEL="anchor_ringing_alarms";
    static final String ACTION_SNOOZE="dev.anchor.app.SNOOZE_ALARM";
    static final String ACTION_DISMISS="dev.anchor.app.DISMISS_ALARM";
    static final int FOREGROUND_ID=0x414e4348;
    static final long MAX_RING_MS=120_000;
    private static final String PREFS="reminders";
    private RingingAlarms() {}

    static SharedPreferences preferences(Context c) { return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE); }
    public static boolean ringingEnabled(Context c) { return preferences(c).getBoolean("ringing_enabled",false); }
    public static synchronized void setRingingEnabled(Context c,boolean enabled) {
        preferences(c).edit().putBoolean("ringing_enabled",enabled).commit();
        channel(c);
        if(!enabled) stopActive(c,-1);
        try(AnchorDb db=new AnchorDb(c)) { Reminders.reconcile(c,db); }
    }
    static void channel(Context c) {
        NotificationChannel ch=new NotificationChannel(CHANNEL,"Ringing alarms",NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Alarms with 5 minute snooze, 10 minute snooze, and dismiss controls");
        // The foreground service owns bounded alarm audio; the notification must not double-play it.
        ch.setSound(null,null);ch.enableVibration(false);ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }
    static boolean notificationsAllowed(Context c,String channel) {
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        if(!nm.areNotificationsEnabled()) return false;
        NotificationChannel ch=nm.getNotificationChannel(channel);
        return ch==null || ch.getImportance()!=NotificationManager.IMPORTANCE_NONE;
    }
    static boolean canRing(Context c) {
        channel(c);
        NotificationChannel ch=c.getSystemService(NotificationManager.class).getNotificationChannel(CHANNEL);
        return ringingEnabled(c) && notificationsAllowed(c,CHANNEL) && ch!=null
            && ch.getImportance()>=NotificationManager.IMPORTANCE_DEFAULT;
    }
    public static String status(Context c) {
        channel(c);
        if(!ringingEnabled(c)) return "Ringing alarms are off. Calendar reminders use notifications.";
        if(!notificationsAllowed(c,CHANNEL)) return "Ringing paused: allow notifications and the Ringing alarms channel so dismiss controls are visible.";
        if(!canRing(c)) return "Ringing paused: increase the Ringing alarms channel importance in Android settings.";
        if(!Reminders.exactAllowed(c)) return "Ringing needs Alarms & reminders access. Approximate notifications are used until it is allowed.";
        return "Ringing on: alarms stop after 2 minutes. Snooze changes only the alarm, not your calendar.";
    }
    static long baseAt(Task task) { return DateRules.reminderAt(task,ZoneId.systemDefault()); }
    static boolean current(Context c,Task t) {
        if(t==null || t.done) return false;
        SharedPreferences p=preferences(c);
        return t.updatedAt==p.getLong("v"+t.id,-1) && baseAt(t)>0 && baseAt(t)==p.getLong("base"+t.id,-1);
    }
    static long token(Context c,long id) { return preferences(c).getLong("token"+id,-1); }
    static long nextToken(SharedPreferences p) { return Math.max(1,p.getLong("sequence",0)+1); }
    static void initialize(Context c,Task t,long at,long now) {
        SharedPreferences p=preferences(c);long token=nextToken(p);
        p.edit().putLong("sequence",token).putLong("v"+t.id,t.updatedAt).putLong("base"+t.id,at)
            .putLong("due"+t.id,at).putLong("token"+t.id,token)
            .putString("state"+t.id,at>now?"pending":"expired").remove("exact"+t.id)
            .remove("snooze_elapsed"+t.id).remove("snooze_boot"+t.id).commit();
    }
    static void removeState(Context c,long id) {
        preferences(c).edit().remove("v"+id).remove("base"+id).remove("due"+id).remove("token"+id)
            .remove("state"+id).remove("exact"+id).remove("snooze_elapsed"+id).remove("snooze_boot"+id).commit();
    }
    static int bootCount(Context c) { return Settings.Global.getInt(c.getContentResolver(),Settings.Global.BOOT_COUNT,-1); }
    /** Keep a five-minute snooze five minutes long when the wall clock or time zone changes. */
    static boolean reconcileSnoozeClock(Context c,Task t,long base) {
        SharedPreferences p=preferences(c);long elapsed=p.getLong("snooze_elapsed"+t.id,-1);
        if(p.getLong("v"+t.id,-1)!=t.updatedAt || elapsed<0 || !"pending".equals(p.getString("state"+t.id,""))) return false;
        long due=p.getLong("due"+t.id,-1);
        int boot=bootCount(c);
        if(boot>=0 && boot==p.getInt("snooze_boot"+t.id,-2)) {
            long rebased=System.currentTimeMillis()+elapsed-SystemClock.elapsedRealtime();
            if(Math.abs(rebased-due)>1500) due=rebased;
        }
        if(base!=p.getLong("base"+t.id,-1) || due!=p.getLong("due"+t.id,-1)) {
            long next=nextToken(p);
            p.edit().putLong("sequence",next).putLong("token"+t.id,next).putLong("base"+t.id,base).putLong("due"+t.id,due).commit();
        }
        return true;
    }
    static Intent occurrenceIntent(Context c,long id,long version,long base,long due,long token,boolean exact) {
        return new Intent(c,ReminderReceiver.class).setData(Uri.parse("anchor://reminder/"+id))
            .putExtra("id",id).putExtra("version",version).putExtra("at",base).putExtra("due",due)
            .putExtra("token",token).putExtra("exact",exact);
    }
    static PendingIntent occurrence(Context c,Intent i) {
        return PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    static void cancelScheduled(Context c,long id) {
        Intent i=new Intent(c,ReminderReceiver.class).setData(Uri.parse("anchor://reminder/"+id));
        PendingIntent pi=PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE);
        if(pi!=null) { c.getSystemService(AlarmManager.class).cancel(pi);pi.cancel(); }
    }
    static void schedule(Context c,Task t) {
        SharedPreferences p=preferences(c);
        if(!current(c,t) || !"pending".equals(p.getString("state"+t.id,""))) return;
        long due=p.getLong("due"+t.id,-1),token=p.getLong("token"+t.id,-1),base=p.getLong("base"+t.id,-1);
        if(due<=0 || token<=0) return;
        AlarmManager am=c.getSystemService(AlarmManager.class);
        long trigger=Math.max(due,System.currentTimeMillis()+500);
        boolean exact=Reminders.exactAllowed(c);
        p.edit().putBoolean("exact"+t.id,exact).commit();
        try {
            PendingIntent pi=occurrence(c,occurrenceIntent(c,t.id,t.updatedAt,base,due,token,exact));
            // Alarm-clock scheduling avoids idle quotas stretching a user-requested 5 minute snooze.
            if(exact && ringingEnabled(c)) am.setAlarmClock(new AlarmManager.AlarmClockInfo(trigger,open(c,t)),pi);
            else if(exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,trigger,pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,trigger,pi);
        } catch(SecurityException denied) {
            p.edit().putBoolean("exact"+t.id,false).commit();
            try {am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,trigger,
                occurrence(c,occurrenceIntent(c,t.id,t.updatedAt,base,due,token,false)));}
            catch(RuntimeException unavailable){p.edit().putBoolean("queue-error",true).commit();}
        } catch(IllegalStateException quota) {
            p.edit().putBoolean("queue-error",true).commit();
        }
    }
    /** Atomically consumes a pending occurrence before showing a notification or starting audio. */
    static synchronized Task consume(Context c,AnchorDb db,Intent i,long now) {
        long id=i.getLongExtra("id",-1);Task t=db.task(id);SharedPreferences p=preferences(c);
        if(!Reminders.shouldDeliver(t,i.getLongExtra("version",-1),i.getLongExtra("at",-1)) || !current(c,t)) return null;
        if(!AlarmRules.acceptOccurrence(p.getLong("v"+id,-1),p.getLong("due"+id,-1),p.getLong("token"+id,-1),
                p.getString("state"+id,""),i.getLongExtra("version",-1),i.getLongExtra("due",-1),i.getLongExtra("token",-1),now)) return null;
        if(!p.edit().putString("state"+id,"delivered").commit()) return null;
        return t;
    }
    static synchronized boolean validAction(Context c,Task t,long token) {
        if(!current(c,t) || token<=0 || token(c,t.id)!=token) return false;
        String state=preferences(c).getString("state"+t.id,"");
        return "delivered".equals(state) || "ringing".equals(state);
    }
    public static synchronized void snooze(Context c,long id,int minutes) {
        long due=AlarmRules.snoozeAt(System.currentTimeMillis(),minutes);
        try(AnchorDb db=new AnchorDb(c)) {
            Task t=db.task(id);
            if(!validAction(c,t,token(c,id))) return;
            snoozeCurrent(c,t,due);
        }
    }
    static void snoozeCurrent(Context c,Task t,long due) {
        SharedPreferences p=preferences(c);long next=nextToken(p);
        stopActive(c,t.id);cancelScheduled(c,t.id);cancelNotification(c,t.id);
        p.edit().putLong("sequence",next).putLong("token"+t.id,next).putLong("due"+t.id,due)
            .putLong("snooze_elapsed"+t.id,SystemClock.elapsedRealtime()+Math.max(0,due-System.currentTimeMillis()))
            .putInt("snooze_boot"+t.id,bootCount(c))
            .putString("state"+t.id,"pending").commit();
        schedule(c,t);
    }
    public static synchronized void dismiss(Context c,long id) {
        cancelScheduled(c,id);cancelNotification(c,id);stopActive(c,id);
        // Keep the revision so reconciliation cannot recreate the dismissed occurrence.
        preferences(c).edit().putString("state"+id,"dismissed").commit();
    }
    static synchronized void handleAction(Context c,Intent i) {
        long id=i.getLongExtra("id",-1);
        try(AnchorDb db=new AnchorDb(c)) {
            Task t=db.task(id);
            if(!validAction(c,t,i.getLongExtra("token",-1))) return;
            if(ACTION_SNOOZE.equals(i.getAction())) {
                long due=AlarmRules.snoozeAt(System.currentTimeMillis(),i.getIntExtra("minutes",0));
                snoozeCurrent(c,t,due);
            } else if(ACTION_DISMISS.equals(i.getAction())) dismiss(c,id);
        }
    }
    static PendingIntent action(Context c,Task t,long token,String action,int minutes) {
        Intent i=new Intent(c,AlarmActionReceiver.class).setAction(action)
            .setData(Uri.parse("anchor://alarm-action/"+t.id+"/"+token+"/"+minutes+"/"+action))
            .putExtra("id",t.id).putExtra("token",token).putExtra("minutes",minutes);
        return PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    static PendingIntent open(Context c,Task t) {
        Intent i=new Intent(c,MainActivity.class).setData(Uri.parse("anchor://entry/"+t.id)).putExtra("date",t.date)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    static Notification notification(Context c,Task t,long token,boolean ringing,String note) {
        String ch=ringing?CHANNEL:Reminders.CHANNEL;
        Notification publicVersion=new Notification.Builder(c,ch).setSmallIcon(R.drawable.ic_anchor)
            .setContentTitle("Anchor reminder").setContentText("An item on your calendar is due.")
            .setContentIntent(open(c,t))
            .addAction(new Notification.Action.Builder(null,"Snooze 5 min",action(c,t,token,ACTION_SNOOZE,5)).build())
            .addAction(new Notification.Action.Builder(null,"Snooze 10 min",action(c,t,token,ACTION_SNOOZE,10)).build())
            .addAction(new Notification.Action.Builder(null,"Dismiss",action(c,t,token,ACTION_DISMISS,0)).build()).build();
        return new Notification.Builder(c,ch).setSmallIcon(R.drawable.ic_anchor).setContentTitle(t.title)
            .setContentText(note).setContentIntent(open(c,t)).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion).setCategory(ringing?Notification.CATEGORY_ALARM:Notification.CATEGORY_REMINDER)
            .setOngoing(ringing).setOnlyAlertOnce(ringing).setAutoCancel(false)
            .setDeleteIntent(action(c,t,token,ACTION_DISMISS,0))
            .addAction(new Notification.Action.Builder(null,"Snooze 5 min",action(c,t,token,ACTION_SNOOZE,5)).build())
            .addAction(new Notification.Action.Builder(null,"Snooze 10 min",action(c,t,token,ACTION_SNOOZE,10)).build())
            .addAction(new Notification.Action.Builder(null,"Dismiss",action(c,t,token,ACTION_DISMISS,0)).build())
            .build();
    }
    static void showReminder(Context c,Task t,long token,String note) {
        Reminders.channel(c);
        if(notificationsAllowed(c,Reminders.CHANNEL)) {
            try { c.getSystemService(NotificationManager.class).notify("entry:"+t.id,0,notification(c,t,token,false,note)); }
            catch(SecurityException denied) { /* Permission may be revoked between check and delivery. */ }
        }
    }
    static void cancelNotification(Context c,long id) {
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        nm.cancel("entry:"+id,0);nm.cancel((int)id); // Remove reminders from earlier app versions too.
    }
    static synchronized void stopActive(Context c,long id) {
        SharedPreferences p=preferences(c);long active=p.getLong("active_id",-1);
        if(active<0 || (id>=0 && active!=id)) return;
        p.edit().remove("active_id").remove("active_token").commit();
        c.stopService(new Intent(c,RingingAlarmService.class));
    }
    static synchronized boolean markRinging(Context c,Task t,long token) {
        if(!validAction(c,t,token) || !canRing(c)) return false;
        return preferences(c).edit().putString("state"+t.id,"ringing").putLong("active_id",t.id).putLong("active_token",token).commit();
    }
    static synchronized boolean stillRinging(Context c,long id,long token) {
        if(!canRing(c)) return false;
        try(AnchorDb db=new AnchorDb(c)) {
            Task t=db.task(id);SharedPreferences p=preferences(c);
            return current(c,t) && token(c,id)==token && p.getLong("active_id",-1)==id
                && p.getLong("active_token",-1)==token && "ringing".equals(p.getString("state"+id,""));
        }
    }
    static synchronized void finished(Context c,long id,long token,boolean showReminder) {
        SharedPreferences p=preferences(c);
        if(p.getLong("active_id",-1)==id && p.getLong("active_token",-1)==token)
            p.edit().remove("active_id").remove("active_token").commit();
        if(token(c,id)!=token || !"ringing".equals(p.getString("state"+id,""))) return;
        p.edit().putString("state"+id,"delivered").commit();
        if(showReminder) try(AnchorDb db=new AnchorDb(c)) {
            Task t=db.task(id);if(current(c,t)) showReminder(c,t,token,"Alarm stopped · snooze or dismiss");
        }
    }
}
