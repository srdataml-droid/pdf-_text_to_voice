package dev.anchor.app;

import java.time.*;
import java.util.*;

/** Goal progress comes from completed calendar sessions, never from elapsed time. */
public final class GoalTimeline {
    private GoalTimeline() {}
    public static final class Summary {
        public LocalDate first,deadline;
        public int planned,completed,overdue,needsRoom,removed;
        public Task next;
        public String status;
        public int percent(){return planned==0?0:Math.min(100,completed*100/planned);}
    }
    public static Summary summarize(Routine goal,List<Task> sessions,LocalDateTime now) {
        Summary s=new Summary();s.first=LocalDate.parse(goal.startDate);s.deadline=s.first.plusDays(goal.days-1);
        for(int i=0;i<goal.days;i++)if((goal.weekdays&(1<<(s.first.plusDays(i).getDayOfWeek().getValue()-1)))!=0)s.planned++;
        int present=0;
        for(Task t:sessions) {
            if(t.event)continue;present++;
            if(t.done){s.completed++;continue;}
            LocalDate date=LocalDate.parse(t.date);
            LocalDateTime start=date.atTime(t.time.isEmpty()?LocalTime.MIN:LocalTime.parse(t.time));
            LocalDateTime due=t.time.isEmpty()?date.plusDays(1).atStartOfDay():start.plusMinutes(t.durationMinutes);
            if(!now.isBefore(due)){s.overdue++;continue;}
            if(t.time.isEmpty())s.needsRoom++;
            if(s.next==null||start.isBefore(LocalDate.parse(s.next.date).atTime(s.next.time.isEmpty()?LocalTime.MIN:LocalTime.parse(s.next.time))))s.next=t;
        }
        s.removed=Math.max(0,s.planned-present);
        s.status=goal.stopped?"Stopped":s.planned>0&&s.completed>=s.planned?"All sessions complete":now.toLocalDate().isAfter(s.deadline)?"Period ended":now.toLocalDate().isBefore(s.first)?"Upcoming":"In progress";
        return s;
    }
}
