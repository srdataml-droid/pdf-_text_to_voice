package dev.anchor.app;
import java.util.*;
import java.util.regex.*;

/** Deterministic, editable fallback suggestions. It never pretends to have interpreted a full discussion. */
public final class PlanText {
    private PlanText(){}
    public static boolean isPlanRequest(String input) {
        String s=input==null?"":input.trim().toLowerCase(Locale.ROOT);
        s=s.replaceFirst("^(?:(?:okay|ok|so|um)[, ]+)+","");
        if(s.matches("(?s).*\\b(don't|don’t|do not|never|poem|story|example|explain)\\b.*"))return false;
        boolean action=s.matches("(?s)^(?:(?:can|could|would|will) you(?: please)?\\s+|please\\s+|i (?:want|need) (?:you to )?)?(?:organize|organise|arrange|sort|plan|build|create|make)\\b.*");
        if(s.matches("(?s)^i (?:want|need|would like)\\b.*\\b(routines|goals|plan|schedule)\\b.*")&&!s.matches("(?s).*\\b(know|understand|learn about|explanation)\\b.*"))action=true;
        return action&&(s.contains("\n")||s.contains(",")||s.matches("(?s).*\\b(list|tasks|goals|routines|period)\\b.*"));
    }
    private static Routine item(String title,String kind,int minutes,int weekdays) {
        Routine r=new Routine();r.title=title;r.category=kind;r.durationMinutes=minutes;r.weekdays=weekdays;return r;
    }
    public static List<Routine> starter() {
        return new ArrayList<>(Arrays.asList(item("Daily rest","daily",45,127),item("Self-development reading","challenge",30,21),item("Countries reading","challenge",30,10),item("Health reading","challenge",30,96),item("Organise my library","challenge",45,64),item("Hackathon work","challenge",60,31),item("Research","challenge",45,42)));
    }
    public static List<Routine> chores() {
        return new ArrayList<>(Arrays.asList(item("Wash dishes","daily",15,127),item("Sweep the house","daily",20,21),item("Wash clothes","daily",60,32),item("Mop the house","daily",30,34)));
    }
    public static List<Routine> suggestions(String text) {
        String s=text==null?"":text.trim();int colon=s.indexOf(':');
        if(colon>=0&&colon<120)s=s.substring(colon+1);
        if(!s.contains("\n")&&!s.contains(",")&&!s.contains(";"))return new ArrayList<>();
        // A comma introducing session minutes belongs to the preceding activity.
        s=s.replaceAll("(?i),\\s*(?=\\d+\\s*(?:minutes?|mins?|hours?|hrs?)\\b)"," ");
        String separator=s.contains("\n")||s.contains(";")?"[\\n;]+":",";
        List<Routine> out=new ArrayList<>();
        for(String raw:s.split(separator)) {
            String title=raw.replaceFirst("^\\s*(?:[-*•]|\\d+[.)])\\s*","").trim();if(title.isEmpty())continue;
            String lower=title.toLowerCase(Locale.ROOT);String kind=lower.matches("(?s).*\\b(wash|dishes|sweep|mop|rest|laundry|meal|clean)\\b.*")?"daily":"challenge";
            int minutes=lower.contains("hackathon")?60:lower.contains("research")||lower.contains("rest")?45:30;
            Matcher duration=Pattern.compile("(?i)\\b(\\d+)\\s*(minutes?|mins?|hours?|hrs?)\\b").matcher(title);
            if(duration.find()){minutes=Integer.parseInt(duration.group(1))*(duration.group(2).toLowerCase(Locale.ROOT).startsWith("h")?60:1);title=(title.substring(0,duration.start())+title.substring(duration.end())).replaceFirst("(?i)\\s+for\\s*$","").trim();}
            if(title.isEmpty()||title.length()>200||minutes<1||minutes>1440)throw new IllegalArgumentException("Check the task name and session minutes.");
            out.add(item(title,kind,minutes,127));if(out.size()>12)throw new IllegalArgumentException("Use up to 12 items in one plan.");
        }
        return out;
    }
}
