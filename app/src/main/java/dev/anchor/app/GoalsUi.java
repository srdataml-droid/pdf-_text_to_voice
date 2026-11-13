package dev.anchor.app;

import android.app.*;
import android.widget.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Offline timeline over existing routine/session ownership; no new database or model. */
final class GoalsUi {
    private final Activity a;private final AnchorDb db;private final Consumer<LocalDate> openDay;private final PlannerUi ui;
    GoalsUi(Activity activity,AnchorDb storage,Consumer<LocalDate> day){a=activity;db=storage;openDay=day;ui=new PlannerUi(a);}
    void show() {
        LinearLayout content=ui.column();
        ui.label(content,"Set a goal’s start and target date, then schedule small sessions. Progress counts sessions you mark complete; it does not certify the goal’s outcome. Works without an AI model.");
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Goals timeline").setView(ui.scroll(content)).setNegativeButton("Close",null).setPositiveButton("+ New goal",null).create();
        List<Routine> goals=db.routines();goals.sort(Comparator.comparing(r->LocalDate.parse(r.startDate).plusDays(r.days-1)));
        if(goals.isEmpty())ui.label(content,"No goals yet. Try Python practice, reading, hackathon work or customer outreach. You choose the dates and session length before saving.");
        for(Routine goal:goals) {
            List<Task> sessions=db.routineTasks(goal.id);GoalTimeline.Summary s=GoalTimeline.summarize(goal,sessions,LocalDateTime.now());
            ui.label(content,goal.title+" · "+("challenge".equals(goal.category)?"Goal":"Daily routine")+"\n"+s.first+" → "+s.deadline+" · "+s.status);
            ProgressBar progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgress(s.percent());progress.setContentDescription(goal.title+": "+s.completed+" of "+s.planned+" sessions complete");content.addView(progress);
            ui.label(content,s.completed+" / "+s.planned+" planned sessions complete · "+s.percent()+"%\n"+s.overdue+" overdue · "+s.needsRoom+" need a time"+(s.removed>0?" · "+s.removed+" removed":""));
            if(s.next!=null){Task next=s.next;ui.button(content,"Next: "+next.date+" · "+(next.time.isEmpty()?"Anytime":next.time),v->{dialog.dismiss();openDay.accept(LocalDate.parse(next.date));});}
            ui.button(content,"View sessions",v->{
                LinearLayout list=ui.column();ui.label(list,"Tap a session to open its day, adjust it or mark it complete.");
                if(sessions.isEmpty())ui.label(list,"No saved sessions remain. Removed sessions are not counted as completed.");
                AlertDialog detail=new AlertDialog.Builder(a).setTitle(goal.title).setView(ui.scroll(list)).setNegativeButton("Close",null).create();
                for(Task t:sessions)ui.button(list,(t.done?"Done · ":"")+t.date+" · "+(t.time.isEmpty()?"Anytime":t.time)+" · "+t.title,clicked->{detail.dismiss();dialog.dismiss();openDay.accept(LocalDate.parse(t.date));});
                detail.show();
            });
        }
        ui.button(content,"Manage routines and stop future sessions",v->{dialog.dismiss();new RoutinesUi(a,db,openDay).show();});
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{dialog.dismiss();new RoutinesUi(a,db,openDay).createGoal();}));dialog.show();
    }
}
