package dev.anchor.app;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.time.*;
import java.util.*;

/** One transaction per reviewed period plan, including its durable creation audit and Undo. */
final class PeriodPlanStore {
    private final AnchorDb db;
    PeriodPlanStore(AnchorDb db){this.db=db;}
    static void createTables(SQLiteDatabase sql) {
        sql.execSQL("CREATE TABLE period_plans (id INTEGER PRIMARY KEY AUTOINCREMENT,first_date TEXT NOT NULL,last_date TEXT NOT NULL,summary TEXT NOT NULL,applied_revision INTEGER NOT NULL,undone INTEGER NOT NULL DEFAULT 0)");
        sql.execSQL("CREATE TABLE period_plan_routines (plan_id INTEGER NOT NULL REFERENCES period_plans(id) ON DELETE CASCADE,routine_id INTEGER NOT NULL UNIQUE REFERENCES routines(id),PRIMARY KEY(plan_id,routine_id))");
    }
    PeriodPlan.Preview preview(List<Routine> items,LocalDate first,LocalDate last,LocalDateTime now,int start,int end,int percent) {
        synchronized(db){SQLiteDatabase sql=db.getReadableDatabase();sql.beginTransaction();try {
            PeriodPlan.Preview p=PeriodPlan.preview(items,first,last,db.range(first.minusDays(1).toString(),last.toString()),now,start,end,percent,db.calendarRevision());sql.setTransactionSuccessful();return p;
        }finally{sql.endTransaction();}}
    }
    long save(PeriodPlan.Preview p,LocalDateTime now,int start,int end,int percent) {
        synchronized(db){SQLiteDatabase sql=db.getWritableDatabase();long id;sql.beginTransaction();try {
            if(p.savedId>0){sql.setTransactionSuccessful();return p.savedId;}
            if(p.revision!=db.calendarRevision()||p.startMinute!=start||p.endMinute!=end||p.percent!=percent)throw new IllegalArgumentException("The calendar or settings changed. Make a fresh preview.");
            PeriodPlan.Preview fresh=preview(p.definitions(),p.first,p.last,p.builtAt,start,end,percent);
            for(RoutinePlanner.Preview r:fresh.routines)for(Task t:r.tasks) {
                if(LocalDate.parse(t.date).isBefore(now.toLocalDate())||(!t.time.isEmpty()&&!LocalDate.parse(t.date).atTime(LocalTime.parse(t.time)).isAfter(now)))throw new IllegalArgumentException("A preview time passed. Make a fresh preview.");
            }
            ContentValues values=new ContentValues();values.put("first_date",p.first.toString());values.put("last_date",p.last.toString());
            values.put("summary",fresh.routines.size()+" routines/goals · "+fresh.taskCount+" sessions · "+fresh.needsRoom+" need room");values.put("applied_revision",0);id=sql.insertOrThrow("period_plans",null,values);
            RoutineStore routines=new RoutineStore(db);
            for(RoutinePlanner.Preview r:fresh.routines){long routine=routines.insertReviewed(r);ContentValues link=new ContentValues();link.put("plan_id",id);link.put("routine_id",routine);sql.insertOrThrow("period_plan_routines",null,link);}
            values=new ContentValues();values.put("applied_revision",db.calendarRevision());sql.update("period_plans",values,"id=?",new String[]{Long.toString(id)});sql.setTransactionSuccessful();
        }finally{sql.endTransaction();}p.savedId=id;return id;}
    }
    private boolean untouched(SQLiteDatabase sql,long id) {
        try(Cursor c=sql.rawQuery("SELECT COUNT(*) FROM period_plan_routines p JOIN routine_entries e ON e.routine_id=p.routine_id LEFT JOIN tasks t ON t.id=e.task_id WHERE p.plan_id=? AND (t.id IS NULL OR t.updated_at<>e.created_version OR t.done<>0)",new String[]{Long.toString(id)})){return c.moveToFirst()&&c.getInt(0)==0;}
    }
    List<PeriodPlan.Record> records() {
        synchronized(db){SQLiteDatabase sql=db.getReadableDatabase();List<PeriodPlan.Record> result=new ArrayList<>();sql.beginTransaction();try {
            long revision=db.calendarRevision();
            try(Cursor c=sql.query("period_plans",null,null,null,null,null,"id DESC")){while(c.moveToNext()){
                PeriodPlan.Record r=new PeriodPlan.Record();r.id=c.getLong(c.getColumnIndexOrThrow("id"));r.first=c.getString(c.getColumnIndexOrThrow("first_date"));r.last=c.getString(c.getColumnIndexOrThrow("last_date"));r.summary=c.getString(c.getColumnIndexOrThrow("summary"));r.undone=c.getInt(c.getColumnIndexOrThrow("undone"))!=0;r.canUndo=!r.undone&&revision==c.getLong(c.getColumnIndexOrThrow("applied_revision"))&&untouched(sql,r.id);result.add(r);
            }}sql.setTransactionSuccessful();return result;
        }finally{sql.endTransaction();}}
    }
    boolean undo(long id) {
        synchronized(db){SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();try {
            try(Cursor c=sql.rawQuery("SELECT applied_revision,undone FROM period_plans WHERE id=?",new String[]{Long.toString(id)})){if(!c.moveToFirst()||c.getInt(1)!=0||c.getLong(0)!=db.calendarRevision()||!untouched(sql,id))return false;}
            List<Long> tasks=new ArrayList<>();try(Cursor c=sql.rawQuery("SELECT e.task_id FROM routine_entries e JOIN period_plan_routines p ON p.routine_id=e.routine_id WHERE p.plan_id=?",new String[]{Long.toString(id)})){while(c.moveToNext())tasks.add(c.getLong(0));}
            for(long task:tasks)db.delete(task);
            sql.execSQL("UPDATE routines SET stopped=1 WHERE id IN (SELECT routine_id FROM period_plan_routines WHERE plan_id=?)",new Object[]{id});
            ContentValues v=new ContentValues();v.put("undone",1);sql.update("period_plans",v,"id=?",new String[]{Long.toString(id)});db.routineChanged();sql.setTransactionSuccessful();return true;
        }finally{sql.endTransaction();}}
    }
}
