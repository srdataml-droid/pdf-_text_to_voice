package dev.anchor.app;
import android.app.ActivityManager;
import android.content.Context;
import android.os.*;
import android.test.InstrumentationTestCase;
import org.json.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit opt-in only: loads the already imported private model with synthetic planning input. */
public class PlanModelDeviceTest extends InstrumentationTestCase {
    public void testRealLocalPlanExtraction() throws Exception {
        Bundle args=((android.test.InstrumentationTestRunner)getInstrumentation()).getArguments();
        if(!"true".equals(args.getString("run_plan_model")))return;
        Context c=getInstrumentation().getTargetContext();File model=new File(c.getFilesDir(),"models/current.gguf");assertTrue("Import the supported local model first",model.isFile());
        ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();c.getSystemService(ActivityManager.class).getMemoryInfo(memory);MemoryPolicy.validate(c,model.length(),memory.availMem,memory.lowMemory);
        long handle=0,start=SystemClock.elapsedRealtime();JSONObject report=new JSONObject();
        try {
            NativeLlama.resetCancellation();handle=NativeLlama.load(model.getAbsolutePath(),1024,2);report.put("load_ms",SystemClock.elapsedRealtime()-start);
            String input="Organize these routines and goals: Reading about health, 30 minutes every day; daily rest, 45 minutes every day; hackathon work, 60 minutes Monday to Friday. Use exactly these three activities.";
            NativeLlama.Result generated=NativeLlama.generate(handle,new String[]{"system","user"},new String[]{PlanProposal.SYSTEM_PROMPT,input},384,null);
            report.put("response",generated.text).put("prompt_tokens",generated.promptTokens).put("output_tokens",generated.outputTokens).put("first_token_ms",generated.firstTokenMs).put("generation_ms",generated.generationMs).put("pss_after_kb",Debug.getPss()).put("cancelled",generated.cancelled);
            List<Routine> parsed=PlanProposal.parse(generated.text);assertNotNull("Model output must be a validated editable plan",parsed);assertEquals(3,parsed.size());
            boolean health=false,rest=false,hackathon=false;
            for(Routine r:parsed){String name=r.title.toLowerCase(Locale.ROOT);if(name.contains("health")){health=true;assertEquals(30,r.durationMinutes);assertEquals(127,r.weekdays);assertEquals("challenge",r.category);}if(name.contains("rest")){rest=true;assertEquals(45,r.durationMinutes);assertEquals("daily",r.category);}if(name.contains("hackathon")){hackathon=true;assertEquals(60,r.durationMinutes);assertEquals(31,r.weekdays);assertEquals("challenge",r.category);}}
            assertTrue(health&&rest&&hackathon);report.put("passed",true);
        }catch(Throwable failure){report.put("passed",false).put("error",failure.toString());throw failure;}
        finally{NativeLlama.free(handle);NativeLlama.resetCancellation();Files.write(new File(c.getFilesDir(),"plan-model-check.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));}
    }
}
