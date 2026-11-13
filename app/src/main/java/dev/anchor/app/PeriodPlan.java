package dev.anchor.app;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** One reviewed list, one horizon and one shared deterministic calendar budget. */
public final class PeriodPlan {
    private PeriodPlan(){}
    public static final class Preview {
        public final LocalDate first,last;
        public final LocalDateTime builtAt;
        public final int startMinute,endMinute,percent,taskCount,needsRoom;
        public final long revision;
        public final List<RoutinePlanner.Preview> routines;
        private final List<Routine> definitions;
        long savedId;
        private Preview(List<Routine> defs,List<RoutinePlanner.Preview> rs,LocalDate a,LocalDate b,LocalDateTime now,int start,int end,int allocation,long rev) {
            definitions=new ArrayList<>();for(Routine r:defs)definitions.add(r.copy());routines=Collections.unmodifiableList(rs);
            first=a;last=b;builtAt=now;startMinute=start;endMinute=end;percent=allocation;revision=rev;
            int total=0,waiting=0;for(RoutinePlanner.Preview p:rs){total+=p.tasks.size();waiting+=p.needsRoom;}taskCount=total;needsRoom=waiting;
        }
        public List<Routine> definitions(){List<Routine> copy=new ArrayList<>();for(Routine r:definitions)copy.add(r.copy());return copy;}
    }
    public static final class Record {
        public long id;public String first,last,summary;public boolean undone,canUndo;
    }
    public static LocalDate suggestedStart(LocalDate today){return today;}
    public static LocalDate suggestedEnd(LocalDate today){return today.plusDays(29);}
    public static Preview preview(List<Routine> source,LocalDate first,LocalDate last,List<Task> existing,LocalDateTime now,int start,int end,int percent,long revision) {
        Objects.requireNonNull(source);Scheduler.validateWindow(start,end,percent);
        long days=ChronoUnit.DAYS.between(first,last)+1;
        if(days<1||days>90)throw new IllegalArgumentException("Choose a period of 1 to 90 calendar days.");
        if(source.isEmpty()||source.size()>24)throw new IllegalArgumentException("Choose 1 to 24 routines or goals.");
        List<Routine> definitions=new ArrayList<>();Set<String> titles=new HashSet<>();
        for(Routine item:source) {
            if(item==null||item.id!=0||item.stopped)throw new IllegalArgumentException("Use new routine definitions for this plan.");
            Routine r=item.copy();r.title=r.title==null?"":r.title.trim();r.startDate=first.toString();r.days=(int)days;
            if(!"daily".equals(r.category)&&!"challenge".equals(r.category))throw new IllegalArgumentException("Choose Daily or Challenge for each item.");
            if(!titles.add(r.title.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Give each routine a distinct name.");definitions.add(r);
        }
        definitions.sort(Comparator.comparingInt(r->"daily".equals(r.category)?0:1));
        List<Task> occupied=new ArrayList<>(existing);List<RoutinePlanner.Preview> plans=new ArrayList<>();
        for(Routine r:definitions) {
            RoutinePlanner.Preview p=RoutinePlanner.plan(r,occupied,now,start,end,percent,revision);plans.add(p);occupied.addAll(p.tasks);
        }
        return new Preview(definitions,plans,first,last,now,start,end,percent,revision);
    }
}
