package dev.anchor.app;

import android.test.AndroidTestCase;
import java.io.File;
import java.io.FileOutputStream;

/** Exercises the packaged JNI runtime without requiring large model weights. */
public class NativeRuntimeDeviceTest extends AndroidTestCase {
    public void testPackagedRuntimeRejectsMalformedGguf() throws Exception {
        assertTrue("Packaged native runtime must load: " + NativeLlama.unavailableReason(), NativeLlama.available());
        File invalid = File.createTempFile("invalid-model-", ".gguf", getContext().getCacheDir());
        try {
            try (FileOutputStream out = new FileOutputStream(invalid)) {
                byte[] content = new byte[4096];
                content[0] = 'G'; content[1] = 'G'; content[2] = 'U'; content[3] = 'F';
                out.write(content);
            }
            NativeLlama.resetCancellation();
            try {
                long handle = NativeLlama.load(invalid.getAbsolutePath(), 1024, 2);
                NativeLlama.free(handle);
                fail("A malformed GGUF must be rejected.");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("Could not load"));
            }
        } finally { invalid.delete(); NativeLlama.resetCancellation(); }
    }
    public void testUnloadedHandleIsSafe() {
        assertTrue(NativeLlama.available());
        try { NativeLlama.description(-1); fail("Unknown model handle must fail safely."); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("unloaded")); }
        NativeLlama.free(-1);
        NativeLlama.cancel();
        NativeLlama.resetCancellation();
    }
}
