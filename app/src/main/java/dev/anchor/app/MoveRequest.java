package dev.anchor.app;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** A request to open a move review, never authority to silently choose a recurring occurrence. */
public final class MoveRequest {
    public long id;public String query="",date="",time="";public boolean needsDetails;
    private MoveRequest(){}
    public static MoveRequest parse(String input,LocalDateTime now) {
        String s=input==null?"":input.trim();
        if(s.toLowerCase(Locale.ROOT).matches("(?s).*\\b(don't|don’t|do not|never|example|hypothetical)\\b.*"))return null;
        Matcher intent=Pattern.compile("(?is)^(?:(?:can|could|would|will) you(?: please)?\\s+|please\\s+|i (?:want|need) you to\\s+)?(?:move|reschedule|shift)\\s+(.+)$").matcher(s);
        if(!intent.matches())return null;String args=intent.group(1).trim();MoveRequest r=new MoveRequest();
        Matcher id=Pattern.compile("(?:#|(?i:\\btask\\s+))([1-9]\\d*)").matcher(args);
        if(id.find()){try{r.id=Long.parseLong(id.group(1));}catch(NumberFormatException bad){return null;}}
        String[] pieces=args.split("(?i)\\s+to\\s+",2);String source=pieces[0].split("(?i)\\s+at\\s+",2)[0];
        r.query=source.replaceAll("(?i)\\b(today|tomorrow|the|task|session)\\b","").replaceAll("#[0-9]+","").trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
        String target=pieces.length>1?pieces[1]:args;String lower=target.toLowerCase(Locale.ROOT);
        if(lower.contains("evening"))target=target.replaceAll("(?i)(?:the\\s+)?evening","")+" at 17:00";
        else if(lower.contains("morning"))target=target.replaceAll("(?i)(?:the\\s+)?morning","")+" at 09:00";
        Task parsed=ChatScheduleRequest.resolve("Schedule a task called Rescheduled task "+target,now);
        if(parsed!=null) {
            r.time=parsed.time;
            if(lower.matches("(?s).*\\b(today|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\\d{4}-\\d{2}-\\d{2})\\b.*"))r.date=parsed.date;
        }else r.needsDetails=true;
        return r;
    }
}
