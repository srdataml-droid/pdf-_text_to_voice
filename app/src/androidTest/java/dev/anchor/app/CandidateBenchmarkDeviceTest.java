package dev.anchor.app;

import android.app.ActivityManager;
import android.content.Context;
import android.os.*;
import android.provider.Settings;
import android.test.InstrumentationTestCase;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;

/** Explicitly staged model only. Does not access calendar, chat, or the person's imported model. */
public class CandidateBenchmarkDeviceTest extends InstrumentationTestCase {
    public void testStagedCandidate() throws Exception {
        Context context=getInstrumentation().getTargetContext();
        File config=new File(context.getFilesDir(),"candidate-benchmark-config.json");
        if(!config.isFile())return; // Normal regression runs do not load a large model.
        JSONObject input=new JSONObject(new String(Files.readAllBytes(config.toPath()),StandardCharsets.UTF_8));
        File model=new File(context.getFilesDir(),"candidate-benchmark.gguf");
        JSONObject result=new JSONObject();JSONArray runs=new JSONArray();
        result.put("candidate",input.getString("candidate")).put("model_sha256",input.getString("sha256"))
                .put("device",Build.MANUFACTURER+" "+Build.MODEL).put("android_api",Build.VERSION.SDK_INT)
                .put("context_tokens",1024).put("threads",2).put("output_token_limit",128)
                .put("started_at_epoch_ms",System.currentTimeMillis()).put("runtime_commit","d05706b4f32a545814856e98ca518292585443b9")
                .put("scope","Packaged JNI chat path; 3 short throughput runs and 1 scheduling response. OS file cache uncontrolled; no sustained qualification.")
                .put("airplane_before",Settings.Global.getInt(context.getContentResolver(),"airplane_mode_on",0)==1);
        Metrics metrics=new Metrics(context);ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor();
        long handle=0;String error="";
        try {
            assertTrue("No staged model",model.isFile());NativeLlama.resetCancellation();metrics.sample();
            sampler.scheduleAtFixedRate(metrics::sample,250,500,TimeUnit.MILLISECONDS);
            long start=SystemClock.elapsedRealtime();handle=NativeLlama.load(model.getAbsolutePath(),1024,2);
            result.put("load_ms",SystemClock.elapsedRealtime()-start).put("description",NativeLlama.description(handle));
            for(int i=0;i<3;i++) {
                NativeLlama.resetCancellation();
                NativeLlama.Result output=NativeLlama.generate(handle,new String[]{"system","user"},new String[]{
                    "You are a concise assistant. Follow the user's request.",
                    "Write a numbered list of 30 practical steps for planning a productive week. Give a short concrete explanation for each step. Start immediately with step 1."},128,(bytes,tokens)->{});
                runs.put(run(output));metrics.sample();if(output.cancelled)break;
            }
            NativeLlama.resetCancellation();
            NativeLlama.Result answer=NativeLlama.generate(handle,new String[]{"system","user"},new String[]{
                "You are an offline personal planning assistant. Discuss proposals only; never claim to execute changes.",
                "It is 09:00. A protected meeting is 10:00 to 11:00. Reading is flexible and needs 30 minutes. Suggest a valid reading time and explain why the meeting stays unchanged. Be brief."},96,(bytes,tokens)->{});
            result.put("scheduling_response",run(answer));
        } catch(Throwable failure) {error=failure.toString();}
        finally {
            sampler.shutdownNow();NativeLlama.free(handle);NativeLlama.resetCancellation();metrics.sample();
            result.put("runs",runs).put("error",error).put("peak_process_pss_kb",metrics.peakPss)
                    .put("minimum_available_ram_bytes",metrics.minimumAvailable).put("physical_accessible_ram_bytes",metrics.totalMemory)
                    .put("low_memory_observed",metrics.lowMemory).put("maximum_thermal_status",metrics.maxThermal)
                    .put("airplane_after",Settings.Global.getInt(context.getContentResolver(),"airplane_mode_on",0)==1);
            try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),"candidate-benchmark-result.json"))) {
                out.write(result.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();
            }
        }
        assertTrue("Candidate benchmark failed: "+error,error.isEmpty());
        assertEquals("All throughput runs must complete",3,runs.length());
    }
    private JSONObject run(NativeLlama.Result output) throws JSONException {
        return new JSONObject().put("text",output.text).put("prompt_tokens",output.promptTokens).put("generated_tokens",output.outputTokens)
                .put("first_token_ms",output.firstTokenMs).put("generation_ms_after_first_token",output.generationMs)
                .put("tokens_per_second_after_first_token",output.tokensPerSecond()).put("cancelled",output.cancelled);
    }
    private static final class Metrics {
        final Context context;long peakPss,minimumAvailable=Long.MAX_VALUE,totalMemory;boolean lowMemory;int maxThermal=-1;
        Metrics(Context c){context=c;}
        synchronized void sample() {
            try {
                ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();context.getSystemService(ActivityManager.class).getMemoryInfo(memory);
                Debug.MemoryInfo pss=new Debug.MemoryInfo();Debug.getMemoryInfo(pss);peakPss=Math.max(peakPss,pss.getTotalPss());
                minimumAvailable=Math.min(minimumAvailable,memory.availMem);totalMemory=memory.totalMem;lowMemory|=memory.lowMemory;
                if(Build.VERSION.SDK_INT>=29)maxThermal=Math.max(maxThermal,context.getSystemService(PowerManager.class).getCurrentThermalStatus());
                if(memory.lowMemory)NativeLlama.cancel();
            }catch(RuntimeException ignored){}
        }
    }
}
