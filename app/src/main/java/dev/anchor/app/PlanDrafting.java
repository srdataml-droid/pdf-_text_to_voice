package dev.anchor.app;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Recoverable interpretation only. The complete source remains available in the editor. */
public final class PlanDrafting {
    public interface Extractor { List<Routine> extract(String source) throws Exception; }
    public static final class Draft {
        public final List<Routine> items;
        public final String explanation,source;
        private Draft(List<Routine> items,String explanation,String source){this.items=items;this.explanation=explanation;this.source=source;}
    }
    private PlanDrafting(){}
    public static Draft create(String source,boolean modelAvailable,Extractor extractor,BooleanSupplier stopped) {
        if(stopped.getAsBoolean())return null;
        List<Routine> items=null;String note;
        if(source.length()>1100)note="This list exceeds the model’s planning context. These suggestions use your complete list; check them against the original request below.";
        else if(!modelAvailable)note="No model is loaded. These editable suggestions use the lines in your list; you can also use the interests template.";
        else {
            try {
                items=extractor.extract(source);
                note=items==null?"The model did not return a complete plan. Review these editable list suggestions against your original request.":"The local model suggested this grouping. Check each title, duration and weekday pattern against your original request before previewing the calendar.";
            }catch(Exception|LinkageError failure){note="The local model could not sort this list. You can still edit these list suggestions and preview your plan.";}
        }
        if(stopped.getAsBoolean())return null;
        if(items==null)try{items=PlanText.suggestions(source);}catch(IllegalArgumentException invalid){items=new ArrayList<>();note+=" "+invalid.getMessage()+" Your complete request is below for editing.";}
        return stopped.getAsBoolean()?null:new Draft(items,note,source);
    }
}
