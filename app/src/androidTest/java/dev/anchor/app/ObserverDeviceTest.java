package dev.anchor.app;
import android.content.*;
import android.test.AndroidTestCase;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** A disposable database and preference namespace; no private calendar rows are read. */
public class ObserverDeviceTest extends AndroidTestCase {
    public void testUndoWaitsForObserverAndCannotBeReapplied() throws Exception {
        Context isolated=new ContextWrapper(getContext()) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {return super.getSharedPreferences("observer-test-"+name,mode);}
        };
        String name="observer-verification.db"; isolated.deleteDatabase(name);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),undoDone=new CountDownLatch(1);
        AtomicBoolean pause=new AtomicBoolean(false),undone=new AtomicBoolean(false);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        AnchorDb db=new AnchorDb(isolated,name) {
            @Override public synchronized long replan(LocalDate date,LocalTime now,int start,int end,int percent,String reason) {
                if(pause.get()) {entered.countDown();try {if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Observer release timed out");}catch(InterruptedException e){throw new AssertionError(e);}}
                return super.replan(date,now,start,end,percent,reason);
            }
        };
        Thread observer=null,undo=null;
        try {
            PlannerSettings settings=new PlannerSettings();settings.automaticPlanning=true;settings.dayStartMinute=0;settings.dayEndMinute=1440;settings.flexiblePercent=100;settings.save(isolated);
            Task fixed=new Task();fixed.title="Observer test fixed";fixed.date=DateRules.today();fixed.time="08:00";fixed.durationMinutes=60;db.save(fixed);
            Task flexible=new Task();flexible.title="Observer test flexible";flexible.date=DateRules.today();flexible.time="08:00";flexible.durationMinutes=30;flexible.flexible=true;db.save(flexible);
            long report=db.replan(LocalDate.now(),LocalTime.MIDNIGHT,0,1440,100,"Test setup");assertTrue(report>0);
            pause.set(true);
            observer=new Thread(()->{try {PlannerObserver.observe(isolated,db,"Concurrent observer");}catch(Throwable t){failure.set(t);}});
            observer.start();assertTrue(entered.await(3,TimeUnit.SECONDS));
            undo=new Thread(()->{try {undone.set(PlannerObserver.undoAndPause(isolated,db,report));}catch(Throwable t){failure.set(t);}finally{undoDone.countDown();}});
            undo.start();
            assertFalse("Undo must share the observation gate",undoDone.await(150,TimeUnit.MILLISECONDS));
            release.countDown();observer.join(5000);undo.join(5000);
            assertFalse(observer.isAlive());assertFalse(undo.isAlive());if(failure.get()!=null)throw new AssertionError(failure.get());
            // If current wall time required a newer plan, stale Undo may refuse. A successful Undo must stay restored.
            assertFalse(PlannerSettings.load(isolated).automaticPlanning && undone.get());
            if(undone.get()) {assertEquals("08:00",db.task(flexible.id).time);PlannerObserver.observe(isolated,db,"After Undo");assertEquals("08:00",db.task(flexible.id).time);}
        } finally {
            release.countDown();if(observer!=null)observer.join(5000);if(undo!=null)undo.join(5000);
            db.close();isolated.deleteDatabase(name);
            isolated.getSharedPreferences("planner-settings",0).edit().clear().commit();
            isolated.getSharedPreferences("reminders",0).edit().clear().commit();
            PlannerObserver.schedule(getContext());
        }
    }
}
