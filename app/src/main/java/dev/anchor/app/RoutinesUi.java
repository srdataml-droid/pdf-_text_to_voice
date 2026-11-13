package dev.anchor.app;

import android.app.*;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Explicit, offline routine setup. Suggestions never create personal tasks until Save. */
final class RoutinesUi {
    private final Activity activity;
    private final AnchorDb db;
    private final Consumer<LocalDate> openDay;
    private static final String[] PRESETS={"Choose a suggestion…","Reading","Wash dishes","Sweep the house","Wash clothes","Mop the house","Plan my goals","Learning / videos","Job applications","Find customers"};
    private static final int[] MINUTES={30,60,15,20,60,30,20,60,60,60};
    RoutinesUi(Activity a,AnchorDb db,Consumer<LocalDate> day){activity=a;this.db=db;openDay=day;}
    void show() {
        LinearLayout content=column();
        label(content,"Create a routine once. Anchor finds room in your planning hours, with a session on each selected day.");
        Button period=button("Plan a whole task list");content.addView(period);period.setOnClickListener(v->new PeriodUi(activity,db,openDay).history());
        label(content,"Sessions appear in Anchor’s calendar and timeline. Reminders use your notification settings; ringing uses your existing alarm switch.");
        List<Routine> routines=db.routines();
        if(routines.isEmpty())label(content,"No routines yet. Start with reading, chores, learning, applications or customer outreach.");
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Routines & goals").setView(scroll(content)).setNegativeButton("Close",null).setPositiveButton("+ New routine",null).create();
        for(Routine r:routines) {
            label(content,("challenge".equals(r.category)?"Challenge · ":"Daily · ")+r.title+"\n"+r.startDate+" → "+LocalDate.parse(r.startDate).plusDays(r.days-1)+" · "+r.durationMinutes+" min/session"+(r.stopped?" · Stopped":""));
            Button view=button("View first day");content.addView(view);view.setOnClickListener(v->{dialog.dismiss();openDay.accept(LocalDate.parse(r.startDate));});
            if(!r.stopped) {
                Button stop=button("Stop upcoming sessions");content.addView(stop);
                stop.setOnClickListener(v->new AlertDialog.Builder(activity).setTitle("Stop "+r.title+"?")
                    .setMessage("Remove upcoming sessions that you haven’t edited or completed. Past, completed and individually edited sessions stay in your calendar.")
                    .setNegativeButton("Keep routine",null).setPositiveButton("Stop routine",(d,w)->{
                        int removed=db.stopRoutine(r.id,LocalDateTime.now());Reminders.reconcile(activity,db);dialog.dismiss();
                        Toast.makeText(activity,removed+" upcoming sessions removed.",Toast.LENGTH_LONG).show();openDay.accept(LocalDate.now());show();
                    }).show());
            }
        }
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{dialog.dismiss();create();}));dialog.show();
    }
    void create() {create(false);}
    void createGoal() {create(true);}
    private void create(boolean goal) {
        LinearLayout form=column();
        label(form,"Start from a suggestion, then adjust the name and duration. Nothing is saved until you review the preview.");
        Spinner presets=new Spinner(activity);presets.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,PRESETS));form.addView(presets);
        EditText title=input(form,"Routine or goal name","",false);
        EditText minutes=input(form,"Minutes per session","60",true);
        final LocalDate[] first={LocalDate.now()};
        Button date=button("Start: "+first[0]);form.addView(date);date.setOnClickListener(v->{
            DatePickerDialog picker=new DatePickerDialog(activity,(p,y,m,d)->{first[0]=LocalDate.of(y,m+1,d);date.setText("Start: "+first[0]);},first[0].getYear(),first[0].getMonthValue()-1,first[0].getDayOfMonth());
            picker.getDatePicker().setMinDate(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());picker.show();
        });
        EditText days=input(form,"Calendar days (1–90)","30",true);
        Button target=button("Choose target date");form.addView(target);
        target.setOnClickListener(v->new PlannerUi(activity).date(first[0].plusDays(29),end->{long count=java.time.temporal.ChronoUnit.DAYS.between(first[0],end)+1;if(count<1||count>90){Toast.makeText(activity,"Choose a target within 1–90 days of the start.",Toast.LENGTH_LONG).show();return;}days.setText(Long.toString(count));target.setText("Target: "+end);}));
        LinearLayout periods=new LinearLayout(activity);
        for(int n:new int[]{30,60,90}){Button b=button(n+" days");b.setOnClickListener(v->days.setText(""+n));periods.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));}form.addView(periods);
        label(form,"Repeat on");CheckBox[] checks=new CheckBox[7];String[] names={"Mon","Tue","Wed","Thu","Fri","Sat","Sun"};
        for(int row=0;row<2;row++) {
            LinearLayout line=new LinearLayout(activity);
            for(int i=row*4;i<Math.min(7,row*4+4);i++){checks[i]=new CheckBox(activity);checks[i].setText(names[i]);checks[i].setChecked(true);line.addView(checks[i],new LinearLayout.LayoutParams(0,dp(48),1));}form.addView(line);
        }
        Switch reminder=new Switch(activity);reminder.setText("Notify me at each session’s start");reminder.setChecked(true);reminder.setMinHeight(dp(48));form.addView(reminder);
        PlannerSettings settings=PlannerSettings.load(activity);
        label(form,"Anchor will use free time between "+clock(settings.dayStartMinute)+" and "+clock(settings.dayEndMinute)+", within your "+settings.flexiblePercent+"% flexible allocation. Adjust planning hours in Settings. You can edit each session on its day.");
        presets.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int at,long id){if(at>0){title.setText(PRESETS[at]);minutes.setText(""+MINUTES[at]);}}
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(goal?"Set a timeline goal":"Build a routine").setView(scroll(form)).setNegativeButton("Cancel",null).setPositiveButton("Preview",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                Routine r=new Routine();r.category=goal?"challenge":"daily";r.title=title.getText().toString();r.startDate=first[0].toString();r.durationMinutes=Integer.parseInt(minutes.getText().toString());r.days=Integer.parseInt(days.getText().toString());r.reminderMinutes=reminder.isChecked()?0:-1;r.weekdays=0;
                for(int i=0;i<7;i++)if(checks[i].isChecked())r.weekdays|=1<<i;
                PlannerSettings current=PlannerSettings.load(activity);
                RoutinePlanner.Preview preview=db.previewRoutine(r,LocalDateTime.now(),current.dayStartMinute,current.dayEndMinute,current.flexiblePercent);
                preview(preview,dialog);
            }catch(RuntimeException invalid){Toast.makeText(activity,invalid.getMessage()==null?"Check the name, days and session minutes.":invalid.getMessage(),Toast.LENGTH_LONG).show();}
        }));dialog.show();
    }
    private void preview(RoutinePlanner.Preview p,AlertDialog form) {
        LinearLayout content=column();
        label(content,p.tasks.size()+" sessions · "+p.routine.durationMinutes+" minutes each · "+p.needsRoom+" need room");
        label(content,"Existing entries keep their times. Flexible sessions may move later as your day changes. Days without a slot remain visible as Anytime tasks; they have no alarm until they get a time.");
        for(Task t:p.tasks)label(content,LocalDate.parse(t.date).getDayOfWeek().toString().substring(0,3)+" "+t.date+" · "+(t.time.isEmpty()?"Needs room":t.time+"–"+LocalTime.parse(t.time).plusMinutes(t.durationMinutes)));
        label(content,Reminders.status(activity));
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Preview: "+p.routine.title).setView(scroll(content)).setNegativeButton("Adjust",null).setPositiveButton("Save "+p.tasks.size()+" sessions",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                PlannerSettings current=PlannerSettings.load(activity);
                db.saveRoutine(p,LocalDateTime.now(),current.dayStartMinute,current.dayEndMinute,current.flexiblePercent);
                Reminders.reconcile(activity,db);dialog.dismiss();form.dismiss();openDay.accept(LocalDate.parse(p.tasks.get(0).date));
                Toast.makeText(activity,p.tasks.size()+" sessions saved. "+p.needsRoom+" need room.",Toast.LENGTH_LONG).show();
            }catch(RuntimeException invalid){Toast.makeText(activity,invalid.getMessage(),Toast.LENGTH_LONG).show();dialog.dismiss();}
        }));dialog.show();
    }
    private String clock(int minute){return minute==1440?"24:00":String.format(Locale.ROOT,"%02d:%02d",minute/60,minute%60);}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout x=new LinearLayout(activity);x.setOrientation(LinearLayout.VERTICAL);x.setPadding(dp(20),dp(10),dp(20),dp(10));return x;}
    private ScrollView scroll(LinearLayout x){ScrollView s=new ScrollView(activity);s.addView(x);return s;}
    private void label(LinearLayout x,String value){TextView t=new TextView(activity);t.setTypeface(activity.getResources().getFont(R.font.anchor_sans));t.setText(value);t.setTextSize(14);t.setPadding(0,dp(8),0,dp(8));x.addView(t);}
    private EditText input(LinearLayout x,String hint,String value,boolean number){label(x,hint);EditText t=new EditText(activity);t.setTypeface(activity.getResources().getFont(R.font.anchor_sans));t.setHint(hint);t.setContentDescription(hint);t.setText(value);t.setSingleLine(true);t.setInputType(number?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);t.setMinHeight(dp(48));x.addView(t);return t;}
    private Button button(String text){Button b=new Button(activity);b.setTypeface(activity.getResources().getFont(R.font.anchor_sans));b.setText(text);b.setAllCaps(false);b.setMinHeight(dp(48));return b;}
}
