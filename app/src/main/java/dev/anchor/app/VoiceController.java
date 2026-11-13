package dev.anchor.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Tap-to-talk through Android's explicitly on-device service and installed local TTS voices. */
public final class VoiceController implements AutoCloseable {
    public interface ListeningCallback {
        void onListeningStart();
        void onPartialResult(String partial);
        void onListeningEnd();
        void onError(String error);
    }
    private static final int MICROPHONE_REQUEST = 8407;
    private static final String LANGUAGE_TAG = "en-US";
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, SpeechJob> utterances = new HashMap<>();
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private Consumer<String> transcriptCallback;
    private Consumer<String> recognitionError;
    private ListeningCallback listeningCallback;
    private PendingSpeech pendingSpeech;
    private boolean closed;
    private boolean listening;
    private boolean waitingForMicrophone;
    private boolean ttsInitialized;
    private boolean localVoiceReady;
    private String ttsUnavailable = "Offline speech output is starting. Try again in a moment.";
    private int recognitionGeneration;
    private long speechGeneration;
    private boolean partialResultsEnabled = true;

    public VoiceController(Activity activity) {
        this.activity = activity;
        // Constructor callbacks are posted so the tts field is assigned before initialization completes.
        runOnMain(() -> {
            if (closed) return;
            try {
                tts = new TextToSpeech(activity.getApplicationContext(),
                        status -> main.post(() -> finishTtsInitialization(status)));
            } catch (RuntimeException exception) {
                ttsInitialized = true;
                ttsUnavailable = "Android could not start offline speech output. Text replies remain available.";
            }
        });
    }

    public void listen(Consumer<String> transcript, Consumer<String> error) {
        listen(transcript, error, null);
    }
    public void listen(Consumer<String> transcript, Consumer<String> error, ListeningCallback callback) {
        runOnMain(() -> {
            if (closed) { deliver(error, "Voice is closed. Reopen Anchor to use it."); return; }
            if (listening) { deliver(error, "Already listening. Finish speaking or wait a moment."); return; }
            if (Build.VERSION.SDK_INT < 31) {
                deliver(error, "Verified on-device speech input needs Android 12 or newer. You can still type here.");
                return;
            }
            try {
                if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
                    deliver(error, "On-device speech input is unavailable on this phone. You can still type here.");
                    return;
                }
            } catch (RuntimeException exception) {
                deliver(error, "Android could not check on-device speech input. You can still type here.");
                return;
            }
            pendingSpeech = null;
            listening = true;
            transcriptCallback = transcript;
            recognitionError = error;
            listeningCallback = callback;
            recognitionGeneration++;
            if (callback != null) main.post(callback::onListeningStart);
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                waitingForMicrophone = true;
                activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_REQUEST);
                return;
            }
            beginRecognition(recognitionGeneration);
        });
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != MICROPHONE_REQUEST) return;
        runOnMain(() -> {
            if (closed || !waitingForMicrophone || !listening) return;
            waitingForMicrophone = false;
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                finishRecognition(null, "Microphone permission is off. Allow it in Android app settings or type here.");
                return;
            }
            beginRecognition(recognitionGeneration);
        });
    }

    private void beginRecognition(int generation) {
        if (closed || !listening || generation != recognitionGeneration) return;
        if (Build.VERSION.SDK_INT < 31) {
            finishRecognition(null, "On-device speech input needs Android 12 or newer. You can still type here.");
            return;
        }
        if (tts != null) tts.stop();
        utterances.clear();
        try {
            // Never substitute createSpeechRecognizer(): its provider can send audio to a server.
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onPartialResults(Bundle partialResults) {
                        if (generation != recognitionGeneration || !listening || closed) return;
                        ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null && !matches.isEmpty() && listeningCallback != null) {
                            main.post(() -> listeningCallback.onPartialResult(matches.get(0)));
                        }
                    }
                @Override public void onEvent(int eventType, Bundle params) { }
                @Override public void onError(int code) {
                    if (generation == recognitionGeneration && listening && !closed)
                        finishRecognition(null, recognitionMessage(code));
                }
                @Override public void onResults(Bundle results) {
                    if (generation != recognitionGeneration || !listening || closed) return;
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = matches == null || matches.isEmpty() ? null : matches.get(0);
                    if (text == null || text.trim().isEmpty())
                        finishRecognition(null, "No speech was recognized. Try a short sentence or type here.");
                    else finishRecognition(text.trim(), null);
                }
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResultsEnabled)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            // The on-device factory is the privacy boundary; the extra alone is only a provider hint.
            if (Build.VERSION.SDK_INT >= 33) {
                recognizer.checkRecognitionSupport(intent, activity.getMainExecutor(), new RecognitionSupportCallback() {
                    @Override public void onSupportResult(RecognitionSupport support) {
                        if (generation != recognitionGeneration || !listening || closed) return;
                        List<String> installed = support.getInstalledOnDeviceLanguages();
                        List<String> available = support.getSupportedOnDeviceLanguages();
                        List<String> pending = support.getPendingOnDeviceLanguages();
                        if (!supportsEnglish(installed) && (supportsEnglish(available) || supportsEnglish(pending))) {
                            finishRecognition(null, "The offline English speech model is not installed yet. Download it in Android speech settings, then try again.");
                            return;
                        }
                        startListening(intent, generation);
                    }
                    @Override public void onError(int code) {
                        if (generation != recognitionGeneration || !listening || closed) return;
                        if (code == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
                            startListening(intent, generation); // Still the explicitly on-device recognizer.
                        else finishRecognition(null, recognitionMessage(code));
                    }
                });
            } else startListening(intent, generation);
            main.postDelayed(() -> {
                if (!closed && listening && generation == recognitionGeneration)
                    finishRecognition(null, "Listening timed out. Tap the microphone and try a short sentence.");
            }, 25000);
        } catch (RuntimeException exception) {
            finishRecognition(null, "On-device speech input could not start. Check your offline speech service or type here.");
        }
    }

    private static boolean supportsEnglish(List<String> languages) {
        if (languages == null) return false;
        for (String tag : languages)
            if (tag != null && "en".equals(Locale.forLanguageTag(tag).getLanguage())) return true;
        return false;
    }

    private void startListening(Intent intent, int generation) {
        if (closed || !listening || generation != recognitionGeneration || recognizer == null) return;
        try { recognizer.startListening(intent); }
        catch (RuntimeException exception) {
            finishRecognition(null, "On-device speech input could not start. You can still type here.");
        }
    }

    private void finishRecognition(String text, String error) {
        Consumer<String> transcript = transcriptCallback;
        Consumer<String> failure = recognitionError;
        ListeningCallback callback = listeningCallback;
        transcriptCallback = null;
        recognitionError = null;
        listeningCallback = null;
        listening = false;
        waitingForMicrophone = false;
        recognitionGeneration++;
        if (recognizer != null) {
            try { recognizer.cancel(); recognizer.destroy(); } catch (RuntimeException ignored) { }
            recognizer = null;
        }
        if (callback != null) main.post(callback::onListeningEnd);
        if (closed) return;
        if (error != null) deliver(failure, error); else deliver(transcript, text);
    }

    public void speak(String text, Consumer<String> error) {
        runOnMain(() -> {
            if (closed) { deliver(error, "Voice is closed. Reopen Anchor to use it."); return; }
            if (text == null || text.trim().isEmpty()) return;
            if (listening) {
                deliver(error, "Finish voice input before playing a spoken reply.");
                return;
            }
            if (!ttsInitialized) {
                pendingSpeech = new PendingSpeech(text, error);
                return;
            }
            if (!localVoiceReady || tts == null) { deliver(error, ttsUnavailable); return; }
            speakLocally(text, error);
        });
    }

    private void finishTtsInitialization(int status) {
        if (closed) return;
        ttsInitialized = true;
        localVoiceReady = false;
        ttsUnavailable = "No installed offline English voice is available. Install an offline voice in Android Text-to-speech settings, then reopen Anchor.";
        if (status == TextToSpeech.SUCCESS && tts != null) {
            try {
                Set<Voice> voices = tts.getVoices();
                Voice selected = null;
                if (voices != null) for (Voice voice : voices) {
                    if (voice == null || voice.isNetworkConnectionRequired()
                            || !"en".equals(voice.getLocale().getLanguage())) continue;
                    Set<String> features = voice.getFeatures();
                    if (features != null && features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                    if (selected == null || voiceScore(voice) > voiceScore(selected)) selected = voice;
                }
                if (selected != null && tts.setVoice(selected) == TextToSpeech.SUCCESS) {
                    Voice active = tts.getVoice();
                    localVoiceReady = active != null && !active.isNetworkConnectionRequired();
                }
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { }
                    @Override public void onDone(String utteranceId) {
                        main.post(() -> utterances.remove(utteranceId));
                    }
                    @Override public void onError(String utteranceId) { failUtterance(utteranceId); }
                    @Override public void onError(String utteranceId, int errorCode) { failUtterance(utteranceId); }
                });
            } catch (RuntimeException exception) {
                localVoiceReady = false;
                ttsUnavailable = "Android could not prepare an offline voice. Text replies remain available.";
            }
        }
        PendingSpeech request = pendingSpeech;
        pendingSpeech = null;
        if (request != null) {
            if (localVoiceReady) speakLocally(request.text, request.error);
            else deliver(request.error, ttsUnavailable);
        }
    }

    private static int voiceScore(Voice voice) {
        return ("US".equals(voice.getLocale().getCountry()) ? 10000 : 0)
                + voice.getQuality() * 10 - voice.getLatency();
    }

    private void speakLocally(String text, Consumer<String> error) {
        try {
            Voice voice = tts.getVoice();
            if (voice == null || voice.isNetworkConnectionRequired()
                    || (voice.getFeatures() != null && voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))) {
                localVoiceReady = false;
                deliver(error, ttsUnavailable);
                return;
            }
            tts.stop();
            utterances.clear();
            long generation = ++speechGeneration;
            SpeechJob job = new SpeechJob(error);
            int maxLength = Math.max(1, TextToSpeech.getMaxSpeechInputLength() - 1);
            int start = 0;
            int chunk = 0;
            while (start < text.length()) {
                int end = Math.min(text.length(), start + maxLength);
                if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                String id = "anchor-" + generation + "-" + chunk;
                utterances.put(id, job);
                int result = tts.speak(text.substring(start, end), chunk == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, id);
                if (result == TextToSpeech.ERROR) {
                    utterances.clear();
                    tts.stop();
                    deliver(error, "The installed offline voice could not speak this reply. The text is still available.");
                    return;
                }
                start = end;
                chunk++;
            }
        } catch (RuntimeException exception) {
            utterances.clear();
            deliver(error, "Offline speech output failed. The text is still available.");
        }
    }

    private void failUtterance(String id) {
        main.post(() -> {
            if (closed) return;
            SpeechJob job = utterances.remove(id);
            if (job == null || job.failed) return;
            job.failed = true;
            deliver(job.error, "The installed offline voice could not finish this reply. The text is still available.");
        });
    }

    private static String recognitionMessage(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_AUDIO: return "The microphone could not record. Check whether another app is using it.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Microphone permission is off. Allow it in Android app settings or type here.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "The on-device speech service is busy. Wait a moment and try again.";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "No speech was heard. Tap the microphone and try again.";
            case SpeechRecognizer.ERROR_NO_MATCH: return "No speech was recognized. Try a short sentence or type here.";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: return "This on-device service does not support English. You can still type here.";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "The offline English speech model is missing. Download it in Android speech settings, then try again.";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "The on-device service reported a network error. Online speech is disabled; type here instead.";
            default: return "On-device speech input stopped (Android error " + code + "). Try again or type here.";
        }
    }

    private void runOnMain(Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) task.run(); else main.post(task);
    }

    private static void deliver(Consumer<String> callback, String value) {
        if (callback != null) callback.accept(value);
    }

    /** Cancel current input/output silently while leaving the local TTS engine reusable. */
    public void stop() {
        runOnMain(() -> {
            if (!closed) stopOnMain();
        });
    }

    private void stopOnMain() {
        // Invalidate listeners before touching services: cancel/stop can trigger callbacks.
        recognitionGeneration++;
        speechGeneration++;
        ListeningCallback callback = listeningCallback;
        listeningCallback = null;
        listening = false;
        waitingForMicrophone = false;
        transcriptCallback = null;
        recognitionError = null;
        pendingSpeech = null;
        utterances.clear();
        SpeechRecognizer old = recognizer;
        recognizer = null;
        if (old != null) {
            try { old.cancel(); } catch (RuntimeException ignored) { }
            try { old.destroy(); } catch (RuntimeException ignored) { }
        }
        if (tts != null) {
            try { tts.stop(); } catch (RuntimeException ignored) { }
        }
        if (callback != null) main.post(callback::onListeningEnd);
        // Keep the TTS initialization callback queued so a later speak() can still work.
    }

    @Override public void close() {
        runOnMain(() -> {
            if (closed) return;
            stopOnMain();
            closed = true;
            main.removeCallbacksAndMessages(null);
            if (tts != null) {
                try { tts.shutdown(); } catch (RuntimeException ignored) { }
                tts = null;
            }
        });
    }

    private static final class PendingSpeech {
        final String text;
        final Consumer<String> error;
        PendingSpeech(String text, Consumer<String> error) { this.text = text; this.error = error; }
    }

    private static final class SpeechJob {
        final Consumer<String> error;
        boolean failed;
        SpeechJob(Consumer<String> error) { this.error = error; }
    }
}
