package dev.anchor.app;

import java.util.ArrayList;
import java.util.List;

/** A durable explanation of one local planner run and its safe Undo availability. */
public final class ScheduleChange {
    public long id,createdAt;
    public String date="",reason="",summary="";
    public boolean undone,canUndo;
    public final List<Scheduler.Move> moves=new ArrayList<>();
    public final List<Long> unscheduled=new ArrayList<>();
}
