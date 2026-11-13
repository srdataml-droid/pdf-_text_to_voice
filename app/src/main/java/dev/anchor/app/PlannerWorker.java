package dev.anchor.app;
import android.content.Context;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Deferrable checks; exact task delivery stays with AlarmManager. */
public final class PlannerWorker extends Worker {
    public PlannerWorker(Context context,WorkerParameters parameters) {super(context,parameters);}
    @Override public Result doWork() {
        try(AnchorDb db=new AnchorDb(getApplicationContext())) {
            PlannerObserver.observe(getApplicationContext(),db,"Periodic check");return Result.success();
        } catch(android.database.sqlite.SQLiteException e) { return Result.retry(); }
    }
}
