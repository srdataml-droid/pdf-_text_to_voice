package dev.anchor.app;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/** A fixed 60-day challenge. Missing a day never resets its start date. */
public final class Consistency {
    private Consistency() {}
    public static final class Snapshot {
        public int day,activeDays,completedTasks,currentStreak;
        public boolean finished;
    }
    public static Snapshot snapshot(LocalDate start,LocalDate today,Map<String,Integer> counts) {
        Snapshot s=new Snapshot();
        s.day=(int)Math.max(0,Math.min(60,ChronoUnit.DAYS.between(start,today)+1));
        s.finished=!today.isBefore(start.plusDays(60));
        LocalDate end=today.isBefore(start.plusDays(59))?today:start.plusDays(59);
        for(LocalDate d=start;!d.isAfter(end);d=d.plusDays(1)) {
            int n=Math.max(0,counts.getOrDefault(d.toString(),0));
            s.completedTasks+=n;if(n>0)s.activeDays++;
        }
        LocalDate streakDay=counts.getOrDefault(today.toString(),0)>0?today:today.minusDays(1);
        while(!streakDay.isBefore(start) && !streakDay.isAfter(end) && counts.getOrDefault(streakDay.toString(),0)>0) {
            s.currentStreak++;streakDay=streakDay.minusDays(1);
        }
        return s;
    }
}
