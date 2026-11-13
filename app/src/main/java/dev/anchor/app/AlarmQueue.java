package dev.anchor.app;
import java.util.*;

/** Only the nearest pending alarms occupy Android alarm slots; the calendar keeps every session. */
public final class AlarmQueue {
    public static final int LIMIT=200;
    private AlarmQueue(){}
    public static List<Task> next(List<Task> tasks,Map<Long,Long> due,int limit) {
        List<Task> result=new ArrayList<>();
        for(Task t:tasks)if(due.getOrDefault(t.id,-1L)>0)result.add(t);
        result.sort(Comparator.comparingLong((Task t)->due.get(t.id)).thenComparingLong(t->t.id));
        return new ArrayList<>(result.subList(0,Math.min(Math.max(0,limit),result.size())));
    }
}
