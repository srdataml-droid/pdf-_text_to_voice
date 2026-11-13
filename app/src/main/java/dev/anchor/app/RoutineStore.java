package dev.anchor.app;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.time.*;
import java.util.*;

/** Atomic routines and session ownership, sharing the calendar's revision guard. */
final class RoutineStore {
    private final AnchorDb db;
    RoutineStore(AnchorDb db){this.db=db;}
    static void createTables(SQLiteDatabase sql) {
        sql.execSQL("CREATE TABLE routines (id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,start_date TEXT NOT NULL,days INTEGER NOT NULL,weekdays INTEGER NOT NULL,duration INTEGER NOT NULL,reminder INTEGER NOT NULL,stopped INTEGER NOT NULL DEFAULT 0,category TEXT NOT NULL DEFAULT 'daily')");
        sql.execSQL("CREATE TABLE routine_entries (routine_id INTEGER NOT NULL REFERENCES routines(id) ON DELETE CASCADE,task_id INTEGER NOT NULL UNIQUE REFERENCES tasks(id) ON DELETE CASCADE,created_version INTEGER NOT NULL,PRIMARY KEY(routine_id,task_id))");
    }
    static void followAutomaticMove(SQLiteDatabase sql,long taskId,long before,long after) {
        ContentValues v=new ContentValues();v.put("created_version",after);
        sql.update("routine_entries",v,"task_id=? AND created_version=?",new String[]{Long.toString(taskId),Long.toString(before)});
    }
    RoutinePlanner.Preview preview(Routine r,LocalDateTime now,int start,int end,int percent) {
        synchronized(db) {
            SQLiteDatabase sql=db.getReadableDatabase();sql.beginTransaction();
            try {
                LocalDate first=LocalDate.parse(r.startDate);
                RoutinePlanner.Preview result=RoutinePlanner.plan(r,db.range(first.minusDays(1).toString(),first.plusDays(Math.min(90,Math.max(1,r.days))-1).toString()),now,start,end,percent,db.calendarRevision());
                sql.setTransactionSuccessful();return result;
            }finally{sql.endTransaction();}
        }
    }
    long save(RoutinePlanner.Preview p,LocalDateTime now,int start,int end,int percent) {
        synchronized(db) {
            SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();
            try {
                if(p.savedRoutineId>0) {sql.setTransactionSuccessful();return p.savedRoutineId;}
                if(p.revision!=db.calendarRevision()||p.startMinute!=start||p.endMinute!=end||p.percent!=percent)
                    throw new IllegalArgumentException("The calendar or planning hours changed. Create a new preview.");
                // Rebuild from the reviewed definition; never trust edited preview Task objects.
                RoutinePlanner.Preview fresh=preview(p.routine,p.builtAt,start,end,percent);
                for(Task t:fresh.tasks) {
                    if(LocalDate.parse(t.date).isBefore(now.toLocalDate())||(!t.time.isEmpty()&&!LocalDate.parse(t.date).atTime(LocalTime.parse(t.time)).isAfter(now)))
                        throw new IllegalArgumentException("A preview time has passed. Create a new preview.");
                }
                long id=insertReviewed(fresh);
                sql.setTransactionSuccessful();p.savedRoutineId=id;return id;
            }finally{sql.endTransaction();}
        }
    }
    /** Caller must hold the calendar transaction and validate its preview revision. */
    long insertReviewed(RoutinePlanner.Preview fresh) {
        SQLiteDatabase sql=db.getWritableDatabase();Routine r=fresh.routine;
        if(!sql.inTransaction())throw new IllegalStateException("A calendar transaction is required.");
        if(!"daily".equals(r.category)&&!"challenge".equals(r.category))throw new IllegalArgumentException("Choose Daily or Challenge.");
        try(Cursor c=sql.rawQuery("SELECT id FROM routines WHERE lower(title)=lower(?) AND start_date=? AND days=? AND weekdays=? AND duration=? AND reminder=? AND stopped=0",new String[]{r.title,r.startDate,""+r.days,""+r.weekdays,""+r.durationMinutes,""+r.reminderMinutes})) {
            if(c.moveToFirst())throw new IllegalArgumentException("This routine already exists. Open Routines to see it.");
        }
        ContentValues v=new ContentValues();v.put("title",r.title);v.put("start_date",r.startDate);v.put("days",r.days);v.put("weekdays",r.weekdays);v.put("duration",r.durationMinutes);v.put("reminder",r.reminderMinutes);v.put("category",r.category);
        long id=sql.insertOrThrow("routines",null,v);
        for(Task t:fresh.tasks){db.save(t);ContentValues e=new ContentValues();e.put("routine_id",id);e.put("task_id",t.id);e.put("created_version",t.updatedAt);sql.insertOrThrow("routine_entries",null,e);}return id;
    }
    List<Routine> list() {
        List<Routine> result=new ArrayList<>();
        try(Cursor c=db.getReadableDatabase().query("routines",null,null,null,null,null,"id DESC")) {
            while(c.moveToNext()) {
                Routine r=new Routine();r.id=c.getLong(c.getColumnIndexOrThrow("id"));r.title=c.getString(c.getColumnIndexOrThrow("title"));r.startDate=c.getString(c.getColumnIndexOrThrow("start_date"));
                r.days=c.getInt(c.getColumnIndexOrThrow("days"));r.weekdays=c.getInt(c.getColumnIndexOrThrow("weekdays"));r.durationMinutes=c.getInt(c.getColumnIndexOrThrow("duration"));r.reminderMinutes=c.getInt(c.getColumnIndexOrThrow("reminder"));r.stopped=c.getInt(c.getColumnIndexOrThrow("stopped"))!=0;r.category=c.getString(c.getColumnIndexOrThrow("category"));result.add(r);
            }
        }return result;
    }
    int stop(long id,LocalDateTime now) {
        synchronized(db) {
            SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();
            try {
                try(Cursor c=sql.rawQuery("SELECT stopped FROM routines WHERE id=?",new String[]{Long.toString(id)})) {
                    if(!c.moveToFirst()||c.getInt(0)!=0){sql.setTransactionSuccessful();return 0;}
                }
                List<Long> remove=new ArrayList<>();
                try(Cursor c=sql.rawQuery("SELECT task_id,created_version FROM routine_entries WHERE routine_id=?",new String[]{Long.toString(id)})) {
                    while(c.moveToNext()) {
                        Task t=db.task(c.getLong(0));if(t==null||t.done||t.updatedAt!=c.getLong(1))continue;
                        LocalDate date=LocalDate.parse(t.date);
                        if(date.isAfter(now.toLocalDate())||(date.equals(now.toLocalDate())&&(t.time.isEmpty()||date.atTime(LocalTime.parse(t.time)).isAfter(now))))remove.add(t.id);
                    }
                }
                for(long task:remove)db.delete(task);
                ContentValues v=new ContentValues();v.put("stopped",1);sql.update("routines",v,"id=?",new String[]{Long.toString(id)});
                db.routineChanged();sql.setTransactionSuccessful();return remove.size();
            }finally{sql.endTransaction();}
        }
    }
}
