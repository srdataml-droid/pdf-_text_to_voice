package dev.anchor.app;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Small, deterministic action path for explicit scheduling requests. Unclear prose goes to chat. */
public final class ChatScheduleRequest {
    private ChatScheduleRequest() {}
    public static boolean isRoutineRequest(String input) {
        return isCreationRequest(input)&&input.toLowerCase(Locale.ROOT).matches("(?s).*\\b(daily|weekly|every|each|weekdays|days|weeks|months|monthly|month)\\b.*");
    }
    public static boolean isCreationRequest(String input) {
        String s=input==null?"":input.trim().toLowerCase(Locale.ROOT);
        if(s.matches("(?s).*\\b(don't|don’t|do not|never|without|example|hypothetical)\\b.*"))return false;
        if(s.matches("(?s).*\\b(poem|poetry|story|email|recipe|code|json|image|picture|song|article|explain|explanation|drawing)\\b.*"))return false;
        if(s.matches("(?s)^(?:(?:can|could|would|will) you(?: please)?\\s+|please\\s+|i (?:want|need) (?:you to )?)?(?:create|set|plan)\\b.*")
                &&!s.matches("(?s).*\\b(task|event|meeting|appointment|schedule|time|reminder|alarm|session|routine|day|week)\\b.*"))return false;
        return s.matches("(?s)^(?:(?:can|could|would|will) you(?: please)?\\s+|please\\s+|i (?:want|need) (?:you to )?)?(?:create|schedule|add|set|plan|remind me to)\\b.*");
    }
    public static Task resolve(String input,LocalDateTime now) {
        if(!isCreationRequest(input)||input.length()>2000||isRoutineRequest(input))return null;
        String lower=input.toLowerCase(Locale.ROOT);
        if(lower.matches("(?s).*\\b(and|or|yesterday|weekly|daily|every|until|finish|deadline|utc|gmt|pst|pdt|est|edt|cet|cest|wat|bst|ist|timezone)\\b.*"))return null;
        if(lower.matches("(?s).*\\b(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|oct|nov|dec)\\b.*"))return null;
        if(lower.matches("(?s).*\\b(?:in|after)\\s+(?:\\d+|one|two|three|a|an|few)\\b.*")||lower.matches("(?s).*\\b\\d+(?:st|nd|rd|th)\\b.*"))return null;
        if(lower.matches("(?s).*\\b\\d{1,4}[/.]\\d{1,2}\\b.*")||lower.matches("(?s).*\\b(?:day|week)\\s+(?:after|before)\\b.*"))return null;
        if(lower.matches("(?s).*\\b(?:one|two|three|four|half|a|an)\\s+(?:hours?|minutes?)\\b.*")||lower.matches("(?s).*\\d+\\.\\d+\\s*(?:hours?|hrs?|minutes?|mins?)\\b.*"))return null;
        Matcher weekdays=Pattern.compile("(?i)\\b(?:(next|this|on)\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b").matcher(input);
        boolean hasWeekday=weekdays.find();
        if(!hasWeekday&&lower.matches("(?s).*\\b(next|this)\\b.*"))return null;
        if(lower.matches("(?s).*\\b(?:am|pm)\\s*(?:to|[-–])\\s*\\d.*")||lower.matches("(?s).*\\b\\d{1,2}:\\d{2}\\s*(?:to|[-–])\\s*\\d.*"))return null;
        Matcher clock=Pattern.compile("(?i)\\b(?:at|by)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b").matcher(input);
        if(!clock.find())return null;
        Matcher moreClocks=Pattern.compile("(?i)\\b\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)\\b|\\b\\d{1,2}:\\d{2}\\b").matcher(input);
        if(moreClocks.find()&&moreClocks.find())return null;
        try {
            int hour=Integer.parseInt(clock.group(1)),minute=clock.group(2)==null?0:Integer.parseInt(clock.group(2));
            String half=clock.group(3);if(half!=null){if(hour<1||hour>12)return null;hour=hour%12+(half.equalsIgnoreCase("pm")?12:0);}
            LocalTime time=LocalTime.of(hour,minute);
            Task t=new Task();t.time=time.toString();t.durationMinutes=30;
            Matcher duration=Pattern.compile("(?i)(-?\\d+)\\s*(?:-\\s*)?(minutes?|mins?|hours?|hrs?|m|h)\\b").matcher(input);
            if(duration.find()){t.durationMinutes=Math.multiplyExact(Integer.parseInt(duration.group(1)),duration.group(2).toLowerCase(Locale.ROOT).startsWith("h")?60:1);}
            else {
                if(lower.matches("(?s).*\\b(minutes?|mins?|hours?|hrs?)\\b.*"))return null;
                t.notes="Used a 30-minute duration because none was specified.";
            }
            if(duration.find())return null;
            LocalDate date=now.toLocalDate();boolean explicit=false;
            Matcher iso=Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b").matcher(input);
            if(iso.find()){date=LocalDate.parse(iso.group());explicit=true;}
            else if(lower.contains("tomorrow")){date=date.plusDays(1);explicit=true;}
            else if(lower.contains("today"))explicit=true;
            if(hasWeekday) {
                if(explicit)return null;
                DayOfWeek weekday=DayOfWeek.valueOf(weekdays.group(2).toUpperCase(Locale.ROOT));
                date=date.with("next".equalsIgnoreCase(weekdays.group(1))?java.time.temporal.TemporalAdjusters.next(weekday):java.time.temporal.TemporalAdjusters.nextOrSame(weekday));
                if(!date.atTime(time).isAfter(now))date=date.plusWeeks(1);explicit=true;
            }
            if(!explicit&&!date.atTime(time).isAfter(now))date=date.plusDays(1);
            if(explicit&&!date.atTime(time).isAfter(now))return null;
            t.date=date.toString();t.flexible=lower.matches("(?s).*\\bflexible\\b.*");
            t.event=lower.matches("(?s).*\\b(event|meeting|appointment)\\b.*");if(t.event)t.flexible=false;
            Matcher name=Pattern.compile("(?i)\\b(?:called|named)\\s+(.+?)(?=\\s+(?:tomorrow|today|at|by|for)\\b|$)").matcher(input);
            if(name.find())t.title=name.group(1).trim();
            else if(lower.matches("(?s).*\\b(read|reading)\\b.*"))t.title="Reading";
            else {
                String title=input.substring(0,clock.start()).replaceFirst("(?i)^(?:(?:can|could|would|will) you(?: please)?\\s+|please\\s+)?(?:create|schedule|add|set|plan)\\s+","");
                title=title.replaceAll("(?i)\\b(?:a|an|the|flexible|task|event|schedule|today|tomorrow|for me|please)\\b","")
                    .replaceAll("(?i)\\d+\\s*-?\\s*(?:minutes?|mins?|hours?|hrs?)\\b","").trim().replaceAll("\\s+"," ");
                if(title.isEmpty())return null;t.title=Character.toUpperCase(title.charAt(0))+title.substring(1);
            }
            t.reminderMinutes=0;DateRules.validate(t);return t;
        }catch(RuntimeException invalid){return null;}
    }
}
