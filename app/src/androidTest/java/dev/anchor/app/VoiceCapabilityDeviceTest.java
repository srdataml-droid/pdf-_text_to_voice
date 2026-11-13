package dev.anchor.app;
import android.content.Context;
import android.os.Build;
import android.speech.SpeechRecognizer;
import android.speech.tts.*;
import android.test.InstrumentationTestCase;
import java.util.concurrent.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Inspects installed offline services; never opens a microphone or plays audio. */
public class VoiceCapabilityDeviceTest extends InstrumentationTestCase {
    public void testRecordOfflineCapabilities() throws Exception {
        Context context=getInstrumentation().getTargetContext();JSONObject report=new JSONObject();
        report.put("on_device_recognition_available",Build.VERSION.SDK_INT>=31&&SpeechRecognizer.isOnDeviceRecognitionAvailable(context));
        CountDownLatch initialized=new CountDownLatch(1);TextToSpeech[] engine={null};int[] status={TextToSpeech.ERROR};
        getInstrumentation().runOnMainSync(()->engine[0]=new TextToSpeech(context,value->{status[0]=value;initialized.countDown();}));
        try {
            boolean ready=initialized.await(15,TimeUnit.SECONDS);int offline=0;
            if(ready&&status[0]==TextToSpeech.SUCCESS&&engine[0].getVoices()!=null)for(Voice voice:engine[0].getVoices())
                if(!voice.isNetworkConnectionRequired()&&"en".equals(voice.getLocale().getLanguage())
                    &&(voice.getFeatures()==null||!voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)))offline++;
            report.put("tts_initialized",ready&&status[0]==TextToSpeech.SUCCESS).put("installed_offline_english_voices",offline)
                .put("microphone_opened",false).put("speech_transcription_tested",false).put("audible_tts_tested",false);
            try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),"voice-capabilities.json"))) {out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
            assertTrue("Voice capability check must finish",ready);
        } finally {getInstrumentation().runOnMainSync(()->{if(engine[0]!=null)engine[0].shutdown();});}
    }
}
