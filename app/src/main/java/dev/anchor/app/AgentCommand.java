package dev.anchor.app;
import java.util.Locale;

/** Only an explicit user slash command grants a planner tool invocation. */
public final class AgentCommand {
    public final String name,text;
    public final long id;
    private AgentCommand(String name,String text,long id) {this.name=name;this.text=text;this.id=id;}
    public static AgentCommand parse(String input) {
        String value=input==null?"":input.trim();
        if(!value.startsWith("/"))return null;
        String[] parts=value.substring(1).split("\\s+",2);
        String name=parts[0].toLowerCase(Locale.ROOT),arg=parts.length==2?parts[1].trim():"";
        if(name.equals("remember")) {
            if(arg.isEmpty() || arg.length()>4000)throw new IllegalArgumentException("Use /remember followed by 1 to 4000 characters.");
            return new AgentCommand(name,arg,0);
        }
        if(name.equals("complete") || name.equals("undo")) {
            try {
                if(!arg.matches("[0-9]+"))throw new NumberFormatException();
                long id=Long.parseLong(arg);if(id<=0)throw new NumberFormatException();
                return new AgentCommand(name,"",id);
            } catch(NumberFormatException e) {throw new IllegalArgumentException("Use /"+name+" followed by one positive item or report ID.");}
        }
        if(!arg.isEmpty())throw new IllegalArgumentException("This command takes no arguments.");
        if(name.equals("today")||name.equals("tomorrow")||name.equals("progress")||name.equals("plans")||name.equals("replan")||name.equals("reports")||name.equals("help"))
            return new AgentCommand(name,"",0);
        throw new IllegalArgumentException("Unknown local command. Use /help.");
    }
}
