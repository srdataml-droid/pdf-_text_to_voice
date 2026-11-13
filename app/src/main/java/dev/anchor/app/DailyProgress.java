package dev.anchor.app;

import java.time.*;
import java.util.*;

/** Equal task shares: completed green, overdue red, not-yet-due neutral. Events are excluded. */
public final class DailyProgress {
    private DailyProgress() {}
    public static final class Day {
        public int total,completed,missed,pending;
        public float greenFraction(){return total==0?0:(float)completed/total;}
        public float redFraction(){return total==0?0:(float)missed/total;}
        public String description(){return completed+" completed, "+missed+" missed, "+pending+" not yet due out of "+total+" tasks";}
    }
    public static Day forDay(List<Task> tasks,LocalDate date,LocalDateTime now) {
        Day result=new Day();
        for(Task t:tasks) {
            if(t.event||!t.date.equals(date.toString()))continue;
            result.total++;
            if(t.done){result.completed++;continue;}
            LocalDateTime due=t.time.isEmpty()?date.plusDays(1).atStartOfDay():date.atTime(LocalTime.parse(t.time)).plusMinutes(t.durationMinutes);
            if(!now.isBefore(due))result.missed++;else result.pending++;
        }
        return result;
    }
    public static Map<String,Day> window(List<Task> tasks,LocalDate first,int days,LocalDateTime now) {
        Map<String,Day> out=new HashMap<>();
        for(int i=0;i<days;i++){LocalDate d=first.plusDays(i);out.put(d.toString(),forDay(tasks,d,now));}return out;
    }
}
