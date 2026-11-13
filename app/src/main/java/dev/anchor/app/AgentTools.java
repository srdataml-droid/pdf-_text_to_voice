package dev.anchor.app;
import android.content.Context;
import java.time.LocalDate;
import java.time.LocalTime;

/** Validated local tools; model-generated content is never passed to this class. */
public final class AgentTools {
    private AgentTools() {}
    public static String execute(Context context,AnchorDb db,AgentCommand command) {
        switch(command.name) {
            case "plans": {
                StringBuilder out=new StringBuilder("Your saved period plans\n");int count=0;
                for(PeriodPlan.Record r:db.periodPlans()){out.append('#').append(r.id).append(' ').append(r.first).append(" → ").append(r.last).append(" · ").append(r.summary).append(r.undone?" [undone]":r.canUndo?" [Undo in Period plans]":"").append('\n');if(++count==8)break;}
                out.append("Active routines and goals:\n");count=0;for(Routine r:db.routines())if(!r.stopped){out.append(r.category.equals("daily")?"Daily · ":"Challenge · ").append(r.title).append(" · ").append(r.durationMinutes).append(" min · ").append(r.startDate).append(" for ").append(r.days).append(" days\n");if(++count==24){out.append("More routines are available in Routines & goals.\n");break;}}
                if(count==0)out.append("None yet. Open Period plans to build a reviewed list.");return out.toString();
            }
            case "help": return "Local planner tools\n/today · /tomorrow · /progress · /plans\n/replan — arrange today's flexible tasks\n/reports — changes and IDs\n/undo REPORT_ID — restore and pause automatic planning\n/complete TASK_ID — check a task\n/remember YOUR NOTE — save explicit memory\nSay ‘Move reading to tomorrow at 5pm’ to choose and review a session. Say ‘Organize these tasks: …’ for a reviewed period plan. Model suggestions never save without review.";
            case "replan": {
                PlannerSettings s=PlannerSettings.load(context);
                long id=db.replan(LocalDate.now(),LocalTime.now(),s.dayStartMinute,s.dayEndMinute,s.flexiblePercent,"Your /replan command");
                Reminders.reconcile(context,db);
                return id>0?"Local planner tool · Applied report #"+id+". Use /reports to review, /undo "+id+" to restore.":"Local planner tool · No changes needed.";
            }
            case "reports": {
                StringBuilder out=new StringBuilder("Local planner tool · Recent reports\n");
                int n=0;
                for(ScheduleChange r:db.changeReports()) {
                    out.append('#').append(r.id).append(" · ").append(r.date).append(" · ").append(r.summary)
                            .append(r.undone?" [undone]":r.canUndo?" [Undo available]":" [newer changes prevent Undo]").append('\n');
                    if(++n>=10)break;
                }
                if(n==0)out.append("No schedule changes yet.");
                return out.toString();
            }
            case "undo": {
                if(!PlannerObserver.undoAndPause(context,db,command.id))return "Local planner tool · Undo unavailable. The report is missing, already undone, or items changed later.";
                return "Local planner tool · Restored report #"+command.id+". Automatic planning paused; re-enable it in Settings.";
            }
            case "complete": {
                Task t=db.task(command.id);
                if(t==null||t.event)return "Local planner tool · No completable task with that ID.";
                db.complete(t.id,true);PlannerObserver.observe(context,db,"Your /complete command");
                return "Local planner tool · Completed #"+t.id+" · "+t.title+". The task check in your timeline can undo completion.";
            }
            case "remember": {long id=db.remember(command.text);return "Local memory tool · Saved note #"+id+". Review or delete it in Settings.";}
            default: throw new IllegalArgumentException("Use /help for local tools.");
        }
    }
}
