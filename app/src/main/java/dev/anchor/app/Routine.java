package dev.anchor.app;

/** A bounded local routine; weekday bits run Monday through Sunday. */
public final class Routine {
    public long id;
    public String title="",startDate="",category="daily";
    public int days=30,weekdays=127,durationMinutes=60,reminderMinutes=0;
    public boolean stopped;
    public Routine copy() {
        Routine r=new Routine();r.id=id;r.title=title;r.startDate=startDate;r.days=days;r.weekdays=weekdays;
        r.durationMinutes=durationMinutes;r.reminderMinutes=reminderMinutes;r.stopped=stopped;r.category=category;return r;
    }
}
