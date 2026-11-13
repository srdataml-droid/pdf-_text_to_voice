package dev.anchor.app;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Deterministic local wall-clock scheduling. It has no network, AI, storage or alarm authority. */
public final class Scheduler {
    private Scheduler() {}
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT);

    public static final class Move {
        public final long taskId;
        public final String title,fromDate,fromTime,toDate,toTime;
        public Move(long taskId,String title,String fromDate,String fromTime,String toDate,String toTime) {
            this.taskId=taskId;this.title=title;this.fromDate=fromDate;this.fromTime=fromTime;this.toDate=toDate;this.toTime=toTime;
        }
    }
    public static final class Plan {
        public final List<Move> moves;
        public final List<Long> unscheduled;
        public final String summary;
        public final int flexibleBudgetMinutes,scheduledMinutes;
        private Plan(List<Move> moves,List<Long> unscheduled,int budget,int scheduled) {
            this.moves=Collections.unmodifiableList(moves);this.unscheduled=Collections.unmodifiableList(unscheduled);
            flexibleBudgetMinutes=budget;scheduledMinutes=scheduled;
            summary=moves.size()+" flexible "+(moves.size()==1?"entry":"entries")+" changed; "+unscheduled.size()+" "+(unscheduled.size()==1?"entry needs":"entries need")+" room. "+scheduled+" of "+budget+" flexible minutes allocated.";
        }
    }
    private static final class Block {
        int start,end;
        Block(int start,int end) { this.start=start;this.end=end; }
    }
    public static void validateWindow(int start,int end,int percent) {
        if(start<0 || start>1439 || end<1 || end>1440 || end<=start) throw new IllegalArgumentException("Day end must be after day start within the same day.");
        if(percent<0 || percent>100) throw new IllegalArgumentException("Flexible allocation must be between 0 and 100 percent.");
    }
    /**
     * Plans only incomplete flexible tasks belonging to date. Fixed tasks, events, completed tasks,
     * and currently running flexible tasks remain where they are. Include yesterday's entries to
     * protect timed commitments that cross midnight. Minutes are floating local calendar minutes,
     * deliberately independent of timezone/DST; the alarm layer resolves them into instants.
     */
    public static Plan plan(List<Task> tasks,LocalDate date,LocalTime now,int dayStartMinute,int dayEndMinute,int flexiblePercent) {
        Objects.requireNonNull(tasks,"tasks");Objects.requireNonNull(date,"date");Objects.requireNonNull(now,"now");
        validateWindow(dayStartMinute,dayEndMinute,flexiblePercent);
        int nowFloor=now.getHour()*60+now.getMinute();
        int earliest=Math.max(dayStartMinute,nowFloor+((now.getSecond()>0 || now.getNano()>0)?1:0));
        int budget=(dayEndMinute-dayStartMinute)*flexiblePercent/100,used=0;
        List<Block> blocked=new ArrayList<>();List<Task> pending=new ArrayList<>(),runningTasks=new ArrayList<>();
        List<Move> moves=new ArrayList<>();List<Long> unscheduled=new ArrayList<>();
        for(Task t:tasks) {
            long dayOffset=ChronoUnit.DAYS.between(date,LocalDate.parse(t.date));
            if(dayOffset < -1 || dayOffset > 0)continue;
            int offset=(int)dayOffset*1440;
            boolean sameDay=offset==0;
            boolean movable=sameDay && t.flexible && !t.event && !t.done;
            if(t.time==null || t.time.isEmpty()) {
                if(t.event && sameDay) blocked.add(new Block(0,1440));
                else if(movable) pending.add(t);
                continue;
            }
            LocalTime at=LocalTime.parse(t.time);
            int start=offset+at.getHour()*60+at.getMinute(),end=start+t.durationMinutes;
            boolean running=movable && start<=nowFloor && end>nowFloor;
            if(running)runningTasks.add(t);
            else if(movable)pending.add(t);
            else if(end>0 && start<1440) {
                blocked.add(new Block(Math.max(0,start),Math.min(1440,end)));
                if(t.flexible && !t.event)used+=Math.max(0,Math.min(end,dayEndMinute)-Math.max(start,dayStartMinute));
            }
        }
        merge(blocked);
        runningTasks.sort(Comparator.comparing((Task t)->t.time).thenComparingLong(t->t.id));
        for(Task t:runningTasks) {
            LocalTime at=LocalTime.parse(t.time);int start=at.getHour()*60+at.getMinute(),end=start+t.durationMinutes;
            // Existing fixed commitments have priority even when the flexible entry has started.
            if(free(blocked,start,end)) {
                blocked.add(new Block(start,end));merge(blocked);
                used+=Math.max(0,Math.min(end,dayEndMinute)-Math.max(start,dayStartMinute));
            } else pending.add(t);
        }
        pending.sort(Comparator.comparing((Task t)->t.time==null || t.time.isEmpty()?"24:00":t.time).thenComparingLong(t->t.id));
        for(Task t:pending) {
            int slot=-1;
            if(used+t.durationMinutes<=budget) {
                if(t.time!=null && !t.time.isEmpty()) {
                    LocalTime at=LocalTime.parse(t.time);int preferred=at.getHour()*60+at.getMinute();
                    if(preferred>=earliest && preferred+t.durationMinutes<=dayEndMinute && free(blocked,preferred,preferred+t.durationMinutes))slot=preferred;
                }
                if(slot<0)slot=firstGap(blocked,earliest,dayEndMinute,t.durationMinutes);
            }
            if(slot<0) {
                unscheduled.add(t.id);
                // Removing a stale placement makes overload visible without leaving a collision.
                if(t.time!=null && !t.time.isEmpty())moves.add(new Move(t.id,t.title,t.date,t.time,t.date,""));
                continue;
            }
            String to=LocalTime.of(slot/60,slot%60).format(TIME);
            if(!to.equals(t.time))moves.add(new Move(t.id,t.title,t.date,t.time==null?"":t.time,date.toString(),to));
            blocked.add(new Block(slot,slot+t.durationMinutes));merge(blocked);used+=t.durationMinutes;
        }
        return new Plan(moves,unscheduled,budget,used);
    }
    private static boolean free(List<Block> blocked,int start,int end) {
        for(Block b:blocked)if(start<b.end && end>b.start)return false;
        return true;
    }
    private static int firstGap(List<Block> blocked,int earliest,int end,int duration) {
        int candidate=earliest;
        for(Block b:blocked) {
            if(b.end<=candidate)continue;
            if(candidate+duration<=b.start)return candidate+duration<=end?candidate:-1;
            candidate=Math.max(candidate,b.end);
        }
        return candidate+duration<=end?candidate:-1;
    }
    private static void merge(List<Block> blocks) {
        blocks.sort(Comparator.comparingInt(b->b.start));
        int write=0;
        for(Block b:blocks) {
            if(write>0 && blocks.get(write-1).end>=b.start)blocks.get(write-1).end=Math.max(blocks.get(write-1).end,b.end);
            else blocks.set(write++,b);
        }
        while(blocks.size()>write)blocks.remove(blocks.size()-1);
    }
}
