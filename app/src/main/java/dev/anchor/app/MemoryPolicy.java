package dev.anchor.app;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

/** Conservative admission after the HONOR's measured large-model low-memory termination. */
public final class MemoryPolicy {
    private static final long MIB=1024L*1024L;
    private static final String PREFS="memory-policy";
    private MemoryPolicy() {}
    public static void validate(Context context,long modelBytes,long availableBytes,boolean lowMemory) {
        if(lowMemory || availableBytes<512*MIB)
            throw new IllegalStateException("Available RAM is too low to load a model safely. Close other apps and try a smaller model.");
        // The smaller measured models can benefit from reclaimable pages. Large files caused an
        // OS process kill before Java could recover, so require actual headroom before trying them.
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        boolean guard=p.getBoolean("large-model-guard",true);
        if(guard && modelBytes>=1536*MIB && availableBytes<modelBytes+256*MIB)
            throw new IllegalStateException(String.format(Locale.US,
                "This large model needs at least %.2f GiB currently available for a guarded load. Try a smaller model such as LFM3 1.7B or LFM2.5 1.2B. This check cannot guarantee against Android memory pressure. Disable 'Large model guard' in Settings to override.",
                (modelBytes+256*MIB)/1073741824.0));
    }
    public static boolean isGuardEnabled(Context context) {
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean("large-model-guard",true);
    }
    public static void setGuardEnabled(Context context,boolean enabled) {
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean("large-model-guard",enabled).apply();
    }
}
