package dev.anchor.app;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.*;

/** Strict draft schema. Parsing never writes storage or grants arbitrary model tool authority. */
public final class ScheduleProposal {
    private ScheduleProposal() {}
    public static Task parse(String output) {
        if(output==null)return null;
        String value=output.trim();
        if(value.startsWith("```json")&&value.endsWith("```"))value=value.substring(7,value.length()-3).trim();
        else if(value.startsWith("```\n")&&value.endsWith("```"))value=value.substring(3,value.length()-3).trim();
        if(!value.startsWith("{")||!value.endsWith("}")||value.length()>8000)return null;
        try {
            JSONTokener tokenizer=new JSONTokener(value);
            Object parsed=tokenizer.nextValue();
            if(!(parsed instanceof JSONObject)||tokenizer.nextClean()!=0)return null;
            JSONObject object=(JSONObject)parsed;
            if(!"propose_task".equals(object.opt("action")))return null;
            Set<String> allowed=new HashSet<>(Arrays.asList("action","title","date","time","duration_minutes","flexible","event","notes","reminder_minutes"));
            Iterator<String> keys=object.keys();while(keys.hasNext())if(!allowed.contains(keys.next()))return null;
            for(String field:new String[]{"title","date","time"})if(!(object.get(field) instanceof String))return null;
            if(!(object.get("flexible") instanceof Boolean)||!(object.get("event") instanceof Boolean))return null;
            Task t=new Task();t.title=object.getString("title");t.date=object.getString("date");t.time=object.getString("time");
            t.durationMinutes=integer(object.get("duration_minutes"));t.event=object.getBoolean("event");t.flexible=!t.event&&object.getBoolean("flexible");
            if(object.has("notes")){if(!(object.get("notes") instanceof String))return null;t.notes=object.getString("notes");}
            if(object.has("reminder_minutes")){
                t.reminderMinutes=integer(object.get("reminder_minutes"));
                if(!Arrays.asList(-1,0,5,15,30,60,1440).contains(t.reminderMinutes))return null;
                if(t.reminderMinutes>=0&&t.time.isEmpty())return null;
            }
            DateRules.validate(t);return t;
        }catch(Exception invalid){return null;}
    }
    private static int integer(Object value) {
        if(!(value instanceof Number))throw new IllegalArgumentException();
        double number=((Number)value).doubleValue();
        if(!Double.isFinite(number)||number!=Math.rint(number)||number<Integer.MIN_VALUE||number>Integer.MAX_VALUE)throw new IllegalArgumentException();
        return (int)number;
    }
}
