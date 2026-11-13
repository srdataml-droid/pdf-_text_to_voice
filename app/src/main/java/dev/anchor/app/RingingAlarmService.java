package dev.anchor.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.net.Uri;
import android.os.*;

/** Bounded, user-stoppable alarm audio. Android owns delivery; no AI runtime is involved. */
public final class RingingAlarmService extends Service {
    private static volatile RingingAlarmService running;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private PowerManager.WakeLock wakeLock;
    private AudioFocusRequest focus;
    private long activeId=-1,activeToken=-1,deadline;
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if(activeId<0) return;
            if(SystemClock.elapsedRealtime()>=deadline || !RingingAlarms.stillRinging(RingingAlarmService.this,activeId,activeToken)) {
                finishCurrent(true);stopSelf();return;
            }
            handler.postDelayed(this,1000);
        }
    };
    static boolean isRunning(long id,long token) {
        RingingAlarmService s=running;return s!=null && s.activeId==id && s.activeToken==token;
    }
    @Override public void onCreate() { super.onCreate();running=this; }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public int onStartCommand(Intent i,int flags,int startId) {
        if(i==null) { stopSelf(startId);return START_NOT_STICKY; }
        long id=i.getLongExtra("id",-1),token=i.getLongExtra("token",-1);
        Task t;
        try(AnchorDb db=new AnchorDb(this)) { t=db.task(id); }
        if(!RingingAlarms.validAction(this,t,token) || !RingingAlarms.canRing(this)
                || !i.getBooleanExtra("exact",false) || !AlarmRules.mayRing(i.getLongExtra("due",-1),System.currentTimeMillis())) {
            if(t!=null && RingingAlarms.validAction(this,t,token)) RingingAlarms.showReminder(this,t,token,"Reminder · snooze or dismiss");
            if(activeId<0) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if(activeId==id && activeToken==token) return START_NOT_STICKY;
        finishCurrent(true);
        activeId=id;activeToken=token;
        try {
            Notification n=RingingAlarms.notification(this,t,token,true,"Ringing · stops after 2 minutes");
            if(Build.VERSION.SDK_INT>=29) startForeground(RingingAlarms.FOREGROUND_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(RingingAlarms.FOREGROUND_ID,n);
            if(!RingingAlarms.markRinging(this,t,token)) { finishCurrent(false);stopSelf();return START_NOT_STICKY; }
            RingingAlarms.cancelNotification(this,id);
            deadline=SystemClock.elapsedRealtime()+RingingAlarms.MAX_RING_MS;
            wakeLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Anchor:alarm");
            wakeLock.setReferenceCounted(false);wakeLock.acquire(RingingAlarms.MAX_RING_MS+5000);
            handler.post(tick);
            startAudio();
        } catch(Exception unavailable) {
            finishCurrent(true);
            RingingAlarms.showReminder(this,t,token,"Alarm audio unavailable · snooze or dismiss");
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    private void startAudio() throws Exception {
        final long audioId=activeId,audioToken=activeToken;
        AudioAttributes audio=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audio).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(change->{
                if(activeId==audioId && activeToken==audioToken
                        && (change==AudioManager.AUDIOFOCUS_LOSS || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
                    finishCurrent(true);stopSelf();
                }
            },handler).build();
        if(getSystemService(AudioManager.class).requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw new IllegalStateException("Alarm audio focus unavailable");
        Uri sound=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if(sound==null) sound=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if(sound==null) throw new IllegalStateException("No alarm sound configured");
        player=new MediaPlayer();player.setAudioAttributes(audio);player.setDataSource(this,sound);player.setLooping(true);
        player.setOnErrorListener((mp,what,extra)->{ finishCurrent(true);stopSelf();return true; });
        player.setOnPreparedListener(mp->{
            if(activeId==audioId && activeToken==audioToken && RingingAlarms.stillRinging(this,activeId,activeToken)) {
                try { mp.start(); } catch(RuntimeException unavailable) { finishCurrent(true);stopSelf(); }
            }
            else { finishCurrent(false);stopSelf(); }
        });
        player.prepareAsync();
    }
    private void finishCurrent(boolean showReminder) {
        handler.removeCallbacks(tick);
        if(player!=null) {
            player.setOnPreparedListener(null);player.setOnErrorListener(null);
            try { player.release(); } catch(RuntimeException ignored) {}
            player=null;
        }
        if(focus!=null) { getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);focus=null; }
        if(wakeLock!=null) { if(wakeLock.isHeld()) wakeLock.release();wakeLock=null; }
        if(activeId>=0) RingingAlarms.finished(this,activeId,activeToken,showReminder);
        activeId=-1;activeToken=-1;stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public void onDestroy() {
        finishCurrent(true);if(running==this) running=null;super.onDestroy();
    }
    @Override public void onTimeout(int startId,int fgsType) { finishCurrent(true);stopSelf(); }
}
