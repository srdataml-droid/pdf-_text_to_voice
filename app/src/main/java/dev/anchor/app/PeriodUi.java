package dev.anchor.app;
import android.app.*;
import android.content.Context;
import android.widget.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** A model or a typed list proposes definitions; only the complete reviewed preview can be saved. */
final class PeriodUi {
    private static final ExecutorService SAVES=Executors.newSingleThreadExecutor(r->new Thread(r,"anchor-plan-save"));
    private final Activity a;private final AnchorDb db;private final PlannerUi ui;private final Consumer<LocalDate> openDay;
    PeriodUi(Activity a,AnchorDb db,Consumer<LocalDate> openDay){this.a=a;this.db=db;this.openDay=openDay;ui=new PlannerUi(a);}
    void history() {
        LinearLayout content=ui.column();ui.label(content,"Plan daily essentials and challenge goals together. Review their actual time slots before saving.");
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Period plans").setView(ui.scroll(content)).setNegativeButton("Close",null).setPositiveButton("New plan",null).create();
        List<PeriodPlan.Record> records=db.periodPlans();if(records.isEmpty())ui.label(content,"No saved period plans yet.");
        for(PeriodPlan.Record r:records) {
            ui.label(content,r.first+" → "+r.last+"\n"+r.summary+(r.undone?" · Undone":""));
            ui.button(content,"View first day",v->{dialog.dismiss();openDay.accept(LocalDate.parse(r.first));});
            if(r.canUndo)ui.button(content,"Undo adding this plan",v->new AlertDialog.Builder(a).setTitle("Undo this plan?").setMessage("Remove the sessions created by this plan. Undo refuses later calendar edits or completions.").setNegativeButton("Keep",null).setPositiveButton("Undo plan",(d,w)->{
                if(db.undoPeriod(r.id)){Reminders.reconcile(a,db);dialog.dismiss();openDay.accept(LocalDate.now());history();}else ui.toast("The calendar changed later. Edit or stop its routines from Routines & goals.");
            }).show());
            else if(!r.undone)ui.label(content,"Later calendar changes prevent batch Undo. Individual sessions and routines remain editable.");
        }
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{dialog.dismiss();edit(Collections.emptyList(),"Start with your interests, add chores, or paste a list.");}));dialog.show();
    }
    void edit(List<Routine> proposed,String explanation) {
        edit(proposed,explanation,"");
    }
    void edit(List<Routine> proposed,String explanation,String source) {
        LinearLayout form=ui.column();ui.label(form,explanation);ui.label(form,"Session lengths and weekday patterns are editable suggestions. Rest means a daytime break here; your sleeping hours are outside the day’s planning window.");
        LocalDate[] first={PeriodPlan.suggestedStart(LocalDate.now())},last={PeriodPlan.suggestedEnd(LocalDate.now())};
        TextView period=ui.label(form,periodText(first[0],last[0]));
        Button from=ui.button(form,"Start: "+first[0],null),to=ui.button(form,"End: "+last[0],null);
        from.setOnClickListener(v->ui.date(first[0],date->{first[0]=date;from.setText("Start: "+date);period.setText(periodText(first[0],last[0]));}));
        to.setOnClickListener(v->ui.date(last[0],date->{last[0]=date;to.setText("End: "+date);period.setText(periodText(first[0],last[0]));}));
        ui.button(form,"November–December (61 days)",v->{int year=LocalDate.now().getYear();if(LocalDate.now().isAfter(LocalDate.of(year,12,31)))year++;first[0]=LocalDate.of(year,11,1);last[0]=LocalDate.of(year,12,31);from.setText("Start: "+first[0]);to.setText("End: "+last[0]);period.setText(periodText(first[0],last[0]));});
        List<Row> rows=new ArrayList<>();LinearLayout entries=ui.column();
        ui.button(form,"Add reading, library, rest, hackathons & research",v->append(rows,entries,PlanText.starter()));
        ui.button(form,"Add household chores",v->append(rows,entries,PlanText.chores()));
        ui.label(form,source.isEmpty()?"Or paste tasks, one per line. Use the fields below to correct the suggestions.":"Your complete original request. Compare it with the suggestions; edit into task lines here to add anything missing.");EditText lines=new EditText(a);lines.setTypeface(ui.font);lines.setHint("Reading\nLibrary planning\nResearch");lines.setText(source);lines.setMinLines(2);lines.setMaxLines(4);lines.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);form.addView(lines);
        ui.button(form,"Add these lines",v->{try{List<Routine> added=PlanText.suggestions("Tasks:\n"+lines.getText());if(added.isEmpty()){ui.toast("Write at least one task.");return;}append(rows,entries,added);lines.setText("");}catch(RuntimeException invalid){ui.toast(invalid.getMessage());}});
        ui.button(form,"Add a custom item",v->{Routine r=new Routine();r.title="New goal";r.category="challenge";r.durationMinutes=30;append(rows,entries,Arrays.asList(r));});
        form.addView(entries);append(rows,entries,proposed);
        PlannerSettings settings=PlannerSettings.load(a);ui.label(form,"Daily essentials get room first, then challenges. Existing entries keep their times. Planning window: "+clock(settings.dayStartMinute)+"–"+clock(settings.dayEndMinute)+" · "+settings.flexiblePercent+"% flexible allocation. Occasional events are added separately when their dates are known.");
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Plan your weeks").setView(ui.scroll(form)).setNegativeButton("Cancel",null).setPositiveButton("Preview plan",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            List<Routine> chosen=new ArrayList<>();for(Row row:rows)if(row.include.isChecked())chosen.add(row.read());PlannerSettings s=PlannerSettings.load(a);
            PeriodPlan.Preview p=db.previewPeriod(chosen,first[0],last[0],LocalDateTime.now(),s.dayStartMinute,s.dayEndMinute,s.flexiblePercent);preview(p,dialog);
        }catch(RuntimeException invalid){ui.toast(invalid.getMessage());}}));dialog.show();
    }
    private String periodText(LocalDate first,LocalDate last){return first+" → "+last+" · "+(ChronoUnit.DAYS.between(first,last)+1)+" calendar days";}
    private void append(List<Row> rows,LinearLayout entries,List<Routine> items) {
        for(Routine r:items){boolean duplicate=false;for(Row row:rows)if(row.title.getText().toString().equalsIgnoreCase(r.title))duplicate=true;if(duplicate)continue;
            if(rows.size()>=24){ui.toast("Use up to 24 definitions per plan. Remove an unwanted item to make room.");break;}rows.add(new Row(entries,r,rows));}
    }
    private final class Row {
        final CheckBox include;final EditText title,minutes;final Spinner category;int mask;final int reminder;
        Row(LinearLayout parent,Routine source,List<Row> rows) {
            LinearLayout row=ui.column();include=new CheckBox(a);include.setTypeface(ui.font);include.setText("Include "+source.title);include.setChecked(true);row.addView(include);
            title=ui.input(row,"Task or goal",source.title,false);minutes=ui.input(row,"Minutes per session",""+source.durationMinutes,true);category=ui.spinner(row,new String[]{"Daily essential","Challenge goal"},"daily".equals(source.category)?0:1);mask=source.weekdays;reminder=source.reminderMinutes;
            Button days=ui.button(row,weekdayNames(mask),null);days.setOnClickListener(v->{String[] names={"Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"};boolean[] selected=new boolean[7];for(int i=0;i<7;i++)selected[i]=(mask&(1<<i))!=0;new AlertDialog.Builder(a).setTitle("Repeat on").setMultiChoiceItems(names,selected,(d,index,on)->selected[index]=on).setNegativeButton("Cancel",null).setPositiveButton("Set weekdays",(d,w)->{mask=0;for(int i=0;i<7;i++)if(selected[i])mask|=1<<i;days.setText(weekdayNames(mask));}).show();});parent.addView(row);
            ui.button(row,"Remove this item",v->{rows.remove(this);parent.removeView(row);});
        }
        Routine read(){Routine r=new Routine();r.title=title.getText().toString();try{r.durationMinutes=Integer.parseInt(minutes.getText().toString());}catch(NumberFormatException invalid){throw new IllegalArgumentException("Enter whole session minutes for "+r.title+".");}r.category=category.getSelectedItemPosition()==0?"daily":"challenge";r.weekdays=mask;r.reminderMinutes=reminder;return r;}
    }
    private static String weekdayNames(int mask){StringBuilder out=new StringBuilder("Repeat: ");String[] days={"Mon","Tue","Wed","Thu","Fri","Sat","Sun"};for(int i=0;i<7;i++)if((mask&(1<<i))!=0)out.append(days[i]).append(' ');return mask==0?"Choose weekdays":out.toString().trim();}
    private String clock(int minute){return minute==1440?"24:00":String.format(Locale.ROOT,"%02d:%02d",minute/60,minute%60);}
    private void preview(PeriodPlan.Preview p,AlertDialog editor) {
        LinearLayout content=ui.column();ui.label(content,periodText(p.first,p.last)+"\n"+p.routines.size()+" routines/goals · "+p.taskCount+" sessions · "+p.needsRoom+" need room");
        ui.label(content,"No-room sessions remain visible as Anytime tasks. Notifications begin only once they have a time. New sessions stay flexible; protected commitments keep their times.");
        String group="";for(RoutinePlanner.Preview r:p.routines) {
            if(!r.routine.category.equals(group)){group=r.routine.category;ui.label(content,group.equals("daily")?"DAILY ESSENTIALS":"CHALLENGE GOALS");}
            ui.label(content,r.routine.title+" · "+r.routine.durationMinutes+" min · "+r.tasks.size()+" sessions\n"+weekdayNames(r.routine.weekdays)+" · "+r.needsRoom+" need room");
            for(int i=0;i<Math.min(2,r.tasks.size());i++){Task t=r.tasks.get(i);ui.label(content,t.date+" · "+(t.time.isEmpty()?"Needs room":t.time));}
            ui.button(content,"See every session: "+r.routine.title,v->{LinearLayout dates=ui.column();for(Task t:r.tasks)ui.label(dates,t.date+" · "+(t.time.isEmpty()?"Needs room":t.time+"–"+LocalTime.parse(t.time).plusMinutes(t.durationMinutes)));new AlertDialog.Builder(a).setTitle(r.routine.title).setView(ui.scroll(dates)).setPositiveButton("Close",null).show();});
        }
        ui.label(content,Reminders.status(a));TextView state=ui.label(content,"Nothing is saved yet.");
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Review the whole plan").setView(ui.scroll(content)).setNegativeButton("Adjust",null).setPositiveButton("Save plan",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            dialog.setCancelable(false);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);state.setText("Saving sessions and preparing reminders…");Context app=a.getApplicationContext();
            SAVES.execute(()->{String error=null,warning="";try(AnchorDb storage=new AnchorDb(app)){
                PlannerSettings s=PlannerSettings.load(app);long id=storage.savePeriod(p,LocalDateTime.now(),s.dayStartMinute,s.dayEndMinute,s.flexiblePercent);
                storage.message("assistant","Saved plan #"+id+": "+p.first+" to "+p.last+", "+p.taskCount+" sessions. "+p.needsRoom+" need room. Period plans has guarded Undo.");
                try{Reminders.reconcile(app,storage);}catch(RuntimeException alarmError){warning=" Sessions are saved; reminder setup needs a retry from Settings.";}
            }catch(RuntimeException failed){error=failed.getMessage();}final String failure=error,note=warning;
                a.runOnUiThread(()->{if(a.isFinishing()||a.isDestroyed())return;if(failure!=null){dialog.setCancelable(true);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);state.setText(failure);}else{dialog.dismiss();editor.dismiss();openDay.accept(p.first);ui.toast("Plan saved. "+p.needsRoom+" sessions need room."+note);}});
            });
        }));dialog.show();
    }
}
