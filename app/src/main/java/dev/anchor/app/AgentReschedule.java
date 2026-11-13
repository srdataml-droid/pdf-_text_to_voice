package dev.anchor.app;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import java.time.*;
import java.util.*;

/** Explicitly reviewed flexible-task moves use the same durable audit/Undo as automatic planning. */
final class AgentReschedule {
    private AgentReschedule(){}
    static long apply(AnchorDb db,long id,long expectedVersion,String date,String time,LocalDateTime now) {
        synchronized(db){SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();try {
            Task t=db.task(id);if(t==null||t.updatedAt!=expectedVersion)throw new IllegalArgumentException("This session changed. Open a fresh review.");
            if(t.done||t.event||!t.flexible)throw new IllegalArgumentException("Only unfinished flexible tasks can move here. Edit protected commitments from their timeline entry.");
            String fromDate=t.date,fromTime=t.time;t.date=date;t.time=time;DateRules.validate(t);
            if(time.isEmpty())throw new IllegalArgumentException("Choose a new start time.");
            LocalDateTime start=LocalDate.parse(date).atTime(LocalTime.parse(time)),end=start.plusMinutes(t.durationMinutes);
            if(!start.isAfter(now))throw new IllegalArgumentException("Choose a future start time.");
            if(fromDate.equals(date)&&fromTime.equals(time))throw new IllegalArgumentException("Choose a different date or time.");
            for(Task other:db.range(start.toLocalDate().minusDays(1).toString(),end.toLocalDate().toString())) {
                if(other.id==id)continue;
                if(other.time.isEmpty()) {
                    if(other.event&&!LocalDate.parse(other.date).isBefore(start.toLocalDate())&&!LocalDate.parse(other.date).isAfter(end.minusNanos(1).toLocalDate()))throw new IllegalArgumentException("That day has an all-day commitment.");
                }else {LocalDateTime at=LocalDate.parse(other.date).atTime(LocalTime.parse(other.time));if(start.isBefore(at.plusMinutes(other.durationMinutes))&&end.isAfter(at))throw new IllegalArgumentException("That time is occupied by "+other.title+". Choose another slot.");}
            }
            db.save(t);ContentValues v=new ContentValues();v.put("created_at",System.currentTimeMillis());v.put("date",date);v.put("reason","Your reschedule request");v.put("summary",t.title+": "+fromDate+" "+(fromTime.isEmpty()?"Anytime":fromTime)+" → "+date+" "+time);v.put("applied_revision",db.calendarRevision());v.put("signature","requested:"+UUID.randomUUID());
            long report=sql.insertOrThrow("schedule_reports",null,v);v=new ContentValues();v.put("report_id",report);v.put("task_id",id);v.put("title",t.title);v.put("from_date",fromDate);v.put("from_time",fromTime);v.put("to_date",date);v.put("to_time",time);v.put("post_updated_at",t.updatedAt);sql.insertOrThrow("schedule_moves",null,v);
            sql.setTransactionSuccessful();return report;
        }finally{sql.endTransaction();}}
    }
}
