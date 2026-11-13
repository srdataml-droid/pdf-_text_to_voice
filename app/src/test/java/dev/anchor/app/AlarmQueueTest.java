package dev.anchor.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class AlarmQueueTest {
    @Test public void hundredsOfRoutineSessionsUseABoundedEarliestFirstQueue() {
        List<Task> tasks=new ArrayList<>();Map<Long,Long> due=new HashMap<>();
        for(int i=800;i>0;i--){Task t=new Task();t.id=i;tasks.add(t);due.put(t.id,(long)i*1000);}
        List<Task> selected=AlarmQueue.next(tasks,due,200);assertEquals(200,selected.size());assertEquals(1,selected.get(0).id);assertEquals(200,selected.get(199).id);assertEquals(800,tasks.get(0).id);
        due.remove(1L);selected=AlarmQueue.next(tasks,due,200);assertEquals(2,selected.get(0).id);assertEquals(201,selected.get(199).id);
    }
    @Test public void snoozedDueTimeWinsOverOriginalBaseAndMissingEntriesAreExcluded() {
        Task a=new Task();a.id=1;Task b=new Task();b.id=2;Task c=new Task();c.id=3;
        Map<Long,Long> due=new HashMap<>();due.put(1L,5000L);due.put(2L,1000L);
        assertEquals(2,AlarmQueue.next(Arrays.asList(a,b,c),due,200).get(0).id);assertEquals(2,AlarmQueue.next(Arrays.asList(a,b,c),due,200).size());
    }
}
