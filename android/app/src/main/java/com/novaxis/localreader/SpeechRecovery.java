package com.novaxis.localreader;

import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

/** Retry one failed operation after resetting its engine. Never retries cancellation or VM errors. */
final class SpeechRecovery {
    static <T> T run(Callable<T> operation, Runnable reset, BooleanSupplier active) throws Exception {
        for (int attempt = 0; ; attempt++) {
            if (!active.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try { return operation.call(); }
            catch (InterruptedException cancelled) { throw cancelled; }
            catch (Exception error) {
                if (attempt == 1 || !active.getAsBoolean() || Thread.currentThread().isInterrupted()) throw error;
                reset.run();
            }
        }
    }
}
