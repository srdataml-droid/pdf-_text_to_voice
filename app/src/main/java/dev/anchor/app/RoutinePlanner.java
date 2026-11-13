package dev.anchor.app;

import java.time.*;
import java.util.*;

/** Adds sessions into gaps without moving any existing calendar entry. */
public final class RoutinePlanner {
    private RoutinePlanner() {}
    public static final class Preview {
        public final Routine routine;
        public final List<Task> tasks;
        public final int needsRoom,startMinute,endMinute,percent;
        public final long revision;
        public final LocalDateTime builtAt;
        long savedRoutineId;
        private Preview(Routine r,List<Task> ts,int waiting,LocalDateTime now,int start,int end,int allocation,long rev) {
            routine=r.copy();tasks=Collections.unmodifiableList(ts);needsRoom=waiting;builtAt=now;
            startMinute=start;endMinute=end;percent=allocation;revision=rev;
        }
    }
    public static Preview plan(Routine source,List<Task> existing,LocalDateTime now,int start,int end,int percent,long revision) {
        Objects.requireNonNull(source);Objects.requireNonNull(now);Scheduler.validateWindow(start,end,percent);
        Routine r=source.copy();r.title=r.title==null?"":r.title.trim();
        if(r.days<1||r.days>90)throw new IllegalArgumentException("Choose 1 to 90 calendar days.");
        if(r.weekdays<1||r.weekdays>127)throw new IllegalArgumentException("Choose at least one weekday.");
        LocalDate first;
        try {first=LocalDate.parse(r.startDate);}catch(RuntimeException bad){throw new IllegalArgumentException("Choose a valid start date.");}
        if(first.isBefore(now.toLocalDate()))throw new IllegalArgumentException("Start today or on a future date.");
        List<Task> sessions=new ArrayList<>();int waiting=0;
        for(int i=0;i<r.days;i++) {
            LocalDate date=first.plusDays(i);
            if((r.weekdays & (1<<(date.getDayOfWeek().getValue()-1)))==0)continue;
            Task t=new Task();t.title=r.title;t.date=date.toString();t.durationMinutes=r.durationMinutes;
            t.flexible=true;t.reminderMinutes=r.reminderMinutes;DateRules.validate(t);
            List<int[]> blocks=new ArrayList<>();int used=0;
            for(Task old:existing) {
                long offset=java.time.temporal.ChronoUnit.DAYS.between(date,LocalDate.parse(old.date));
                if(offset < -1 || offset > 0)continue;
                if(old.time.isEmpty()) {if(old.event&&offset==0)blocks.add(new int[]{0,1440});continue;}
                LocalTime at=LocalTime.parse(old.time);int a=(int)offset*1440+at.getHour()*60+at.getMinute(),b=a+old.durationMinutes;
                if(b<=0||a>=1440)continue;
                blocks.add(new int[]{Math.max(0,a),Math.min(1440,b)});
                if(old.flexible&&!old.event)used+=Math.max(0,Math.min(end,b)-Math.max(start,a));
            }
            blocks.sort(Comparator.comparingInt(b->b[0]));
            int candidate=start;
            if(date.equals(now.toLocalDate()))candidate=Math.max(candidate,now.getHour()*60+now.getMinute()+1);
            for(int[] b:blocks) {if(candidate+r.durationMinutes<=b[0])break;if(b[1]>candidate)candidate=b[1];}
            if(used+r.durationMinutes<=(end-start)*percent/100 && candidate+r.durationMinutes<=end) {
                t.time=String.format(Locale.ROOT,"%02d:%02d",candidate/60,candidate%60);
            } else waiting++;
            sessions.add(t);
        }
        if(sessions.isEmpty())throw new IllegalArgumentException("No selected weekday occurs in this period.");
        return new Preview(r,sessions,waiting,now,start,end,percent,revision);
    }
}
