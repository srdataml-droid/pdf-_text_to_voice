package dev.anchor.app;
import org.json.*;
import java.util.*;

/** Compact model suggestions only; the user chooses the horizon and reviews every definition. */
public final class PlanProposal {
    public static final String SYSTEM_PROMPT="Sort the user's routine/goal list. Return ONLY {\"action\":\"propose_plan\",\"items\":[[\"title\",30,127,\"challenge\"]]}. Each item is [title,minutes,weekday_mask,category]. Category is daily for rest/chores, challenge for reading/learning/projects. Masks: daily127,Mon-Fri31,weekends96,Mon-Wed-Fri21,Tue-Thu10,Saturday32,Sunday64. Max12 items. Preserve named activities and explicit durations. Use30 minutes only when unspecified. No dates: the user reviews the period separately. Exclude undated occasional events; never invent event dates. No prose, no code fences, no extra keys. Proposals never save without user review.";
    private PlanProposal(){}
    public static List<Routine> parse(String output) {
        if(output==null)return null;String value=output.trim();
        if(value.startsWith("```json")&&value.endsWith("```"))value=value.substring(7,value.length()-3).trim();
        if(!value.startsWith("{")||!value.endsWith("}")||value.length()>10000)return null;
        try {
            JSONTokener tokener=new JSONTokener(value);Object parsed=tokener.nextValue();if(!(parsed instanceof JSONObject)||tokener.nextClean()!=0)return null;
            JSONObject root=(JSONObject)parsed;if(root.length()!=2||!"propose_plan".equals(root.opt("action"))||!(root.get("items") instanceof JSONArray))return null;
            JSONArray items=root.getJSONArray("items");if(items.length()<1||items.length()>12)return null;List<Routine> result=new ArrayList<>();Set<String> names=new HashSet<>();
            for(int i=0;i<items.length();i++) {
                if(!(items.get(i) instanceof JSONArray))return null;JSONArray row=items.getJSONArray(i);if(row.length()!=4||!(row.get(0) instanceof String)||!(row.get(3) instanceof String))return null;
                Routine r=new Routine();r.title=row.getString(0).trim();r.durationMinutes=integer(row.get(1));r.weekdays=integer(row.get(2));r.category=row.getString(3);
                if(r.title.isEmpty()||r.title.length()>200||r.durationMinutes<1||r.durationMinutes>1440||r.weekdays<1||r.weekdays>127||(!r.category.equals("daily")&&!r.category.equals("challenge"))||!names.add(r.title.toLowerCase(Locale.ROOT)))return null;result.add(r);
            }return result;
        }catch(Exception invalid){return null;}
    }
    private static int integer(Object value){if(!(value instanceof Number))throw new IllegalArgumentException();double n=((Number)value).doubleValue();if(!Double.isFinite(n)||n!=Math.rint(n)||n<Integer.MIN_VALUE||n>Integer.MAX_VALUE)throw new IllegalArgumentException();return(int)n;}
}
