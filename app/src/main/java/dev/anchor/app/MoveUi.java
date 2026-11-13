package dev.anchor.app;
import android.app.*;
import android.widget.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

final class MoveUi {
    private final Activity a;private final AnchorDb db;private final PlannerUi ui;private final Consumer<LocalDate> openDay;
    MoveUi(Activity a,AnchorDb db,Consumer<LocalDate> openDay){this.a=a;this.db=db;this.openDay=openDay;ui=new PlannerUi(a);}
    void show(String text) {
        MoveRequest r=MoveRequest.parse(text,LocalDateTime.now());if(r==null){ui.toast("Use Move followed by the task name or #ID.");return;}
        List<Task> matches=new ArrayList<>();
        for(Task t:db.range(LocalDate.now().toString(),LocalDate.now().plusDays(90).toString()))if(!t.done&&(r.id>0?t.id==r.id:!r.query.isEmpty()&&t.title.toLowerCase(Locale.ROOT).contains(r.query)))matches.add(t);
        if(matches.size()==1){review(matches.get(0),r);return;}
        LinearLayout list=ui.column();ui.label(list,matches.isEmpty()?"No matching upcoming session. Try its name or #ID from /today or /tomorrow.":"Choose the exact session to move. Repeating sessions stay separate.");
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Which session?").setView(ui.scroll(list)).setNegativeButton("Cancel",null).create();
        int shown=0;for(Task t:matches){if(shown++>=30)break;ui.button(list,t.date+" · "+(t.time.isEmpty()?"Anytime":t.time)+" · "+t.title+" · #"+t.id,v->{dialog.dismiss();review(t,r);});}
        if(matches.size()>30)ui.label(list,"Showing the first 30 matches. Use a specific #ID for a later session.");dialog.show();
    }
    private void review(Task selected,MoveRequest r) {
        if(selected.done||selected.event||!selected.flexible){new AlertDialog.Builder(a).setTitle("This commitment is protected").setMessage("Open its timeline entry to edit it explicitly. Flexible work can be rescheduled here.").setNegativeButton("Cancel",null).setPositiveButton("Open timeline",(d,w)->openDay.accept(LocalDate.parse(selected.date))).show();return;}
        LinearLayout form=ui.column();ui.label(form,"Move "+selected.title+"\nFrom "+selected.date+" · "+(selected.time.isEmpty()?"Anytime":selected.time)+" · "+selected.durationMinutes+" minutes");
        if(r.needsDetails)ui.label(form,"Choose the exact date and time below; the request’s time wording was not fully understood.");
        LocalDate[] date={r.date.isEmpty()?LocalDate.parse(selected.date):LocalDate.parse(r.date)};
        String[] time={r.time.isEmpty()?(selected.time.isEmpty()?"09:00":selected.time):r.time};
        Button day=ui.button(form,"New date: "+date[0],null);day.setOnClickListener(v->ui.date(date[0],picked->{date[0]=picked;day.setText("New date: "+picked);}));
        Button clock=ui.button(form,"New time: "+time[0],null);clock.setOnClickListener(v->{LocalTime initial=LocalTime.parse(time[0]);new TimePickerDialog(a,(p,h,m)->{time[0]=String.format(Locale.ROOT,"%02d:%02d",h,m);clock.setText("New time: "+time[0]);},initial.getHour(),initial.getMinute(),true).show();});
        ui.label(form,"Other entries keep their times. This session remains flexible. You can review and Undo the move in Change reports.");
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Review reschedule").setView(ui.scroll(form)).setNegativeButton("Cancel",null).setPositiveButton("Move session",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            long report=db.requestMove(selected.id,selected.updatedAt,date[0].toString(),time[0],LocalDateTime.now());Reminders.reconcile(a,db);db.message("assistant","Moved "+selected.title+" to "+date[0]+" at "+time[0]+". Change report #"+report+" has Undo.");dialog.dismiss();openDay.accept(date[0]);ui.toast("Session moved. Change reports has Undo.");
        }catch(RuntimeException invalid){ui.toast(invalid.getMessage());}}));dialog.show();
    }
}
