package dev.anchor.app;

/** A local calendar entry. Dates/times are floating local wall-clock values. */
public class Task {
    public long id, updatedAt;
    public String title="", date="", time="", notes="", completedDate="";
    public int durationMinutes=30, reminderMinutes=-1;
    public boolean done=false, event=false, flexible=false;
}
