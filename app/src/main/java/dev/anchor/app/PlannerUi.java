package dev.anchor.app;
import android.app.*;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.util.function.Consumer;

/** Small shared native controls for reviewed planning tools. */
final class PlannerUi {
    final Activity a;final Typeface font;
    PlannerUi(Activity a){this.a=a;font=a.getResources().getFont(R.font.anchor_sans);}
    int dp(int value){return Math.round(value*a.getResources().getDisplayMetrics().density);}
    LinearLayout column(){LinearLayout c=new LinearLayout(a);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(18),dp(8),dp(18),dp(12));return c;}
    ScrollView scroll(LinearLayout c){ScrollView s=new ScrollView(a);s.addView(c);return s;}
    TextView label(LinearLayout c,String text){TextView t=new TextView(a);t.setTypeface(font);t.setText(text);t.setTextSize(14);t.setPadding(0,dp(7),0,dp(7));c.addView(t);return t;}
    Button button(LinearLayout c,String text,View.OnClickListener action){Button b=new Button(a);b.setTypeface(font);b.setText(text);b.setAllCaps(false);b.setMinHeight(dp(48));b.setOnClickListener(action);c.addView(b);return b;}
    EditText input(LinearLayout c,String hint,String value,boolean numeric){label(c,hint);EditText e=new EditText(a);e.setTypeface(font);e.setTextSize(15);e.setHint(hint);e.setContentDescription(hint);e.setText(value);e.setSingleLine(true);e.setInputType(numeric?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);e.setMinHeight(dp(48));c.addView(e);return e;}
    Spinner spinner(LinearLayout c,String[] labels,int selected){Spinner s=new Spinner(a);s.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,labels){
        @Override public View getView(int p,View v,ViewGroup parent){View r=super.getView(p,v,parent);if(r instanceof TextView)((TextView)r).setTypeface(font);return r;}
        @Override public View getDropDownView(int p,View v,ViewGroup parent){View r=super.getDropDownView(p,v,parent);if(r instanceof TextView)((TextView)r).setTypeface(font);return r;}
    });s.setSelection(selected);c.addView(s);return s;}
    void date(LocalDate initial,Consumer<LocalDate> result){new DatePickerDialog(a,(d,y,m,day)->result.accept(LocalDate.of(y,m+1,day)),initial.getYear(),initial.getMonthValue()-1,initial.getDayOfMonth()).show();}
    void toast(String text){Toast.makeText(a,text==null?"Check the plan details.":text,Toast.LENGTH_LONG).show();}
}
