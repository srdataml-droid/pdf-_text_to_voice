package com.novaxis.localreader;

import java.util.function.BooleanSupplier;
import kotlin.jvm.functions.Function1;

/**
 * sherpa-onnx 1.13.8 JNI resolves invoke(float[]): Integer by its exact descriptor.
 * A Java lambda only exposes erased invoke(Object): Object and aborts CheckJNI.
 * Keep this concrete overload and its name when shrinking/obfuscating the app.
 */
public final class PiperSynthesisCallback implements Function1<float[], Integer> {
    private final BooleanSupplier active;
    public PiperSynthesisCallback(BooleanSupplier active) { this.active = active; }
    @Override public Integer invoke(float[] samples) { return active.getAsBoolean() ? 1 : 0; }
}
