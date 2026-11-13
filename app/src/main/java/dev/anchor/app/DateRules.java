package dev.anchor.app;

import java.time.*;
import java.util.Map;

public final class DateRules {
    private DateRules() {}
    public static String today() { return LocalDate.now().toString(); }
    public static String date(LocalDate date) { return date.toString(); }
    public static void validate(Task t) {
        if(t==null) throw new IllegalArgumentException("Entry is missing.");
        if(t.title==null || t.title.trim().isEmpty()) throw new IllegalArgumentException("Give this entry a title.");
        if(t.title.trim().length()>200) throw new IllegalArgumentException("Keep the title under 200 characters.");
        try {
            if(t.date==null || !t.date.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException();
            LocalDate.parse(t.date);
        } catch(Exception e) { throw new IllegalArgumentException("Use a valid date in YYYY-MM-DD format."); }
        if(t.time==null) t.time="";
        if(!t.time.isEmpty()) {
            try { if(!t.time.matches("\\d{2}:\\d{2}")) throw new IllegalArgumentException(); LocalTime.parse(t.time); }
            catch(Exception e) { throw new IllegalArgumentException("Use a time between 00:00 and 23:59."); }
        }
        if(t.durationMinutes<1 || t.durationMinutes>1440) throw new IllegalArgumentException("Duration must be between 1 and 1440 minutes.");
        if(t.reminderMinutes < -1 || t.reminderMinutes>10080) throw new IllegalArgumentException("Reminder must be off, or up to 7 days before.");
        if(t.notes==null) t.notes="";
        if(t.notes.length()>4000) throw new IllegalArgumentException("Keep notes under 4000 characters.");
    }
    public static long reminderAt(Task t, ZoneId zone) {
        if(t.done || t.reminderMinutes<0 || t.time==null || t.time.isEmpty()) return -1;
        return LocalDate.parse(t.date).atTime(LocalTime.parse(t.time)).atZone(zone).minusMinutes(t.reminderMinutes).toInstant().toEpochMilli();
    }
    public static int streak(Map<String,Integer> counts, LocalDate today) {
        LocalDate d=counts.getOrDefault(today.toString(),0)>0 ? today : today.minusDays(1);
        int n=0;
        while(counts.getOrDefault(d.toString(),0)>0) { n++; d=d.minusDays(1); }
        return n;
    }
}
