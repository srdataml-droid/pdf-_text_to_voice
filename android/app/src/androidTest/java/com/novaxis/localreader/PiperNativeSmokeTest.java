package com.novaxis.localreader;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real device/JNI generation using synthetic text only. Does not open the library or play sound. */
@RunWith(AndroidJUnit4.class)
public class PiperNativeSmokeTest {
    @Test public void typedCallbackGeneratesRealSpeechWithoutAborting() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getCacheDir(), "piper-synthetic-smoke-" + System.nanoTime());
        OfflineTts engine = null;
        try {
            copy(context, "native-piper", root);
            OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
            vits.setModel(new File(root, "en_US-lessac-medium.onnx").getAbsolutePath());
            vits.setTokens(new File(root, "tokens.txt").getAbsolutePath());
            vits.setDataDir(new File(root, "espeak-ng-data").getAbsolutePath());
            OfflineTtsModelConfig model = new OfflineTtsModelConfig();
            model.setVits(vits); model.setNumThreads(2); model.setProvider("cpu"); model.setDebug(false);
            OfflineTtsConfig config = new OfflineTtsConfig(); config.setModel(model); config.setMaxNumSentences(1); config.setSilenceScale(.2f);
            engine = new OfflineTts(null, config);
            AtomicInteger callbacks = new AtomicInteger();
            PiperSynthesisCallback callback = new PiperSynthesisCallback(() -> { callbacks.incrementAndGet(); return true; });
            assertEquals(Integer.class, callback.getClass().getMethod("invoke", float[].class).getReturnType());
            for (String text : new String[] { "This is a local voice check.", "The next passage can follow.", "The callback still works." }) {
                GeneratedAudio audio = engine.generateWithCallback(text, 0, 1f, callback);
                assertEquals(22050, audio.getSampleRate());
                assertTrue("Real speech samples expected", audio.getSamples().length > 2205);
                for (float sample : audio.getSamples()) assertTrue("Finite audio expected", Float.isFinite(sample));
            }
            assertTrue("The native callback must actually run", callbacks.get() >= 3);
        } finally {
            if (engine != null) engine.release();
            remove(root);
        }
    }
    private static void copy(Context context, String source, File target) throws IOException {
        String[] children = context.getAssets().list(source);
        if (children != null && children.length > 0) {
            if (!target.mkdirs() && !target.isDirectory()) throw new IOException("Test storage unavailable");
            for (String child : children) copy(context, source + "/" + child, new File(target, child));
        } else {
            try (InputStream in = context.getAssets().open(source); OutputStream out = new FileOutputStream(target)) {
                byte[] bytes = new byte[65536]; int count;
                while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
            }
        }
    }
    private static void remove(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        file.delete();
    }
}
