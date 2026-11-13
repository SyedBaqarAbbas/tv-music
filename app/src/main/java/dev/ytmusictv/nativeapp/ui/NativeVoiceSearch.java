package dev.ytmusictv.nativeapp.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One user-requested recognition session. The caller owns permission and visible UI. */
final class NativeVoiceSearch implements AutoCloseable {
    interface Callback {
        void onReady();
        void onSpeech();
        void onLevel(float rms);
        void onResult(List<String> results);
        void onError(int code);
    }

    private static final String TAG="NativeVoice";
    private static final long TIMEOUT_MILLIS=30_000;
    private final Context context;
    private final Callback callback;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final boolean debug;
    private SpeechRecognizer recognizer;
    private Runnable timeout;
    private int generation;
    private boolean closed;
    private float peakRms;

    NativeVoiceSearch(Context context,Callback callback){
        requireMainThread();
        if(context==null||callback==null)throw new IllegalArgumentException("Context and callback are required.");
        Context application=context.getApplicationContext();
        this.context=application==null?context:application;
        this.callback=callback;
        debug=(context.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0;
    }

    static boolean isAvailable(Context context){
        requireMainThread();
        try{return SpeechRecognizer.isRecognitionAvailable(context);}
        catch(RuntimeException unavailable){return false;}
    }

    void start(){
        requireMainThread();
        if(closed)throw new IllegalStateException("Voice search is closed.");
        releaseSession();
        peakRms=0;
        if(!isAvailable(context)){reportStartError(SpeechRecognizer.ERROR_CLIENT);return;}
        final int session=generation;
        try{
            recognizer=SpeechRecognizer.createSpeechRecognizer(context);
            recognizer.setRecognitionListener(new RecognitionListener(){
                @Override public void onReadyForSpeech(Bundle params){
                    if(!current(session))return;
                    if(debug)Log.d(TAG,"ready");
                    callback.onReady();
                }
                @Override public void onBeginningOfSpeech(){
                    if(!current(session))return;
                    if(debug)Log.d(TAG,"beginning");
                    callback.onSpeech();
                }
                @Override public void onRmsChanged(float rms){
                    if(!current(session)||Float.isNaN(rms)||Float.isInfinite(rms))return;
                    peakRms=Math.max(peakRms,rms);
                    callback.onLevel(rms);
                }
                @Override public void onBufferReceived(byte[] buffer){/* No audio is retained. */}
                @Override public void onEndOfSpeech(){/* Wait for the final result or deadline. */}
                @Override public void onError(int error){finishError(session,error);}
                @Override public void onResults(Bundle results){
                    if(!current(session))return;
                    ArrayList<String> values=results==null?null:results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    List<String> spoken=values==null?Collections.emptyList():values;
                    if(debug)Log.d(TAG,"result count="+spoken.size()+" rmsPeak="+peakRms);
                    releaseSession();
                    callback.onResult(spoken);
                }
                @Override public void onPartialResults(Bundle results){/* Only final text changes the search. */}
                @Override public void onEvent(int type,Bundle params){/* No vendor-specific commands. */}
            });
            timeout=()->finishError(session,SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
            main.postDelayed(timeout,TIMEOUT_MILLIS);
            Intent request=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);
            recognizer.startListening(request);
        }catch(SecurityException denied){
            releaseSession();reportStartError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
        }catch(RuntimeException unavailable){
            releaseSession();reportStartError(SpeechRecognizer.ERROR_CLIENT);
        }
    }

    void cancel(){requireMainThread();releaseSession();}

    @Override public void close(){requireMainThread();closed=true;releaseSession();}

    private boolean current(int session){return !closed&&recognizer!=null&&session==generation;}

    private void finishError(int session,int error){
        if(!current(session))return;
        if(debug)Log.d(TAG,"error code="+error+" rmsPeak="+peakRms);
        releaseSession();callback.onError(error);
    }

    private void reportStartError(int error){
        if(debug)Log.d(TAG,"error code="+error);
        callback.onError(error);
    }

    private void releaseSession(){
        generation++;
        if(timeout!=null){main.removeCallbacks(timeout);timeout=null;}
        SpeechRecognizer previous=recognizer;recognizer=null;
        if(previous!=null){
            try{previous.cancel();}catch(RuntimeException ignored){}
            try{previous.destroy();}catch(RuntimeException ignored){}
        }
    }

    private static void requireMainThread(){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Voice search must run on the main thread.");
    }
}
