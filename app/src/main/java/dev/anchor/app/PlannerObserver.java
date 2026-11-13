package dev.anchor.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

/** Deterministic observation only; never starts inference or records microphone audio. */
public final class PlannerObserver {
    private PlannerObserver() {}
    public static final String WORK_NAME="anchor-planner-check";
    public static final String CHANNEL="anchor_changes";
    public static synchronized long observe(Context context,AnchorDb db,String reason) {
        PlannerSettings settings=PlannerSettings.load(context);
        long report=0;
        if(settings.automaticPlanning) report=db.replan(LocalDate.now(),LocalTime.now(),settings.dayStartMinute,
                settings.dayEndMinute,settings.flexiblePercent,reason);
        Reminders.reconcile(context,db);
        if(report>0) notifyChange(context);
        return report;
    }
    /** Share the observation gate so an in-flight worker cannot reapply an accepted Undo. */
    public static synchronized boolean undoAndPause(Context context,AnchorDb db,long reportId) {
        PlannerSettings settings=PlannerSettings.load(context);
        boolean previous=settings.automaticPlanning;
        settings.automaticPlanning=false;
        settings.save(context); // If persistence fails, do not restore a plan we cannot keep paused.
        boolean undone=false;
        try { undone=db.undoReport(reportId);return undone; }
        finally {
            if(!undone) {settings.automaticPlanning=previous;settings.save(context);}
            schedule(context);Reminders.reconcile(context,db);
        }
    }
    public static void schedule(Context context) {
        WorkManager manager=WorkManager.getInstance(context);
        if(!PlannerSettings.load(context).automaticPlanning) { manager.cancelUniqueWork(WORK_NAME);return; }
        PeriodicWorkRequest request=new PeriodicWorkRequest.Builder(PlannerWorker.class,15,TimeUnit.MINUTES)
                .setInitialDelay(15,TimeUnit.MINUTES).addTag(WORK_NAME).build();
        manager.enqueueUniquePeriodicWork(WORK_NAME,ExistingPeriodicWorkPolicy.KEEP,request);
    }
    public static String status(Context context) {
        return PlannerSettings.load(context).automaticPlanning
                ? "Checks after planner changes, on app open, and about every 15 minutes when Android allows. No language model runs in the background."
                : "Automatic planning is off. You can still use Replan today.";
    }
    private static void notifyChange(Context context) {
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Schedule changes",NotificationManager.IMPORTANCE_DEFAULT));
        if(!manager.areNotificationsEnabled())return;
        Intent intent=new Intent(context,MainActivity.class).putExtra("show_reports",true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending=PendingIntent.getActivity(context,9201,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_anchor)
                .setContentTitle("Anchor updated your plan").setContentText("Open the change report to review or Undo.")
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true).build();
        try {manager.notify(9201,notification);}catch(SecurityException ignored) { /* Report remains visible in the app. */ }
    }
}
