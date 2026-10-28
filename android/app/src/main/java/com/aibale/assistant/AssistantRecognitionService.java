package com.aibale.assistant;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;

public class AssistantRecognitionService extends RecognitionService {
    private static final String TAG = "AI Bale Speech";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer delegate;
    private Callback activeCallback;

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback callback) {
        mainHandler.post(() -> startDelegate(recognizerIntent, callback));
    }

    @Override
    protected void onStopListening(Callback callback) {
        mainHandler.post(() -> {
            if (delegate != null) {
                try {
                    delegate.stopListening();
                } catch (RuntimeException e) {
                    Log.w(TAG, "Could not stop speech recognizer", e);
                    sendError(activeCallback != null ? activeCallback : callback,
                            SpeechRecognizer.ERROR_CLIENT);
                    destroyDelegate();
                }
            } else {
                sendError(callback, SpeechRecognizer.ERROR_CLIENT);
            }
        });
    }

    @Override
    protected void onCancel(Callback callback) {
        mainHandler.post(this::destroyDelegate);
    }

    private void startDelegate(Intent recognizerIntent, Callback callback) {
        destroyDelegate();
        activeCallback = callback;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            sendError(callback, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
            destroyDelegate();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendError(callback, SpeechRecognizer.ERROR_CLIENT);
            destroyDelegate();
            return;
        }
        try {
            delegate = SpeechRecognizer.createSpeechRecognizer(this);
            delegate.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    send(activeCallback, c -> c.readyForSpeech(params));
                }
                @Override public void onBeginningOfSpeech() {
                    send(activeCallback, Callback::beginningOfSpeech);
                }
                @Override public void onRmsChanged(float rmsdB) {
                    send(activeCallback, c -> c.rmsChanged(rmsdB));
                }
                @Override public void onBufferReceived(byte[] buffer) {
                    send(activeCallback, c -> c.bufferReceived(buffer));
                }
                @Override public void onEndOfSpeech() {
                    send(activeCallback, Callback::endOfSpeech);
                }
                @Override public void onError(int error) {
                    Callback notify = activeCallback;
                    sendError(notify, error);
                    destroyDelegate();
                }
                @Override public void onResults(Bundle results) {
                    Callback notify = activeCallback;
                    send(notify, c -> c.results(results));
                    destroyDelegate();
                }
                @Override public void onPartialResults(Bundle partialResults) {
                    send(activeCallback, c -> c.partialResults(partialResults));
                }
                @Override public void onEvent(int eventType, Bundle params) {
                    send(activeCallback, c -> c.event(eventType, params));
                }
            });
            delegate.startListening(recognizerIntent == null ? new Intent() : new Intent(recognizerIntent));
        } catch (RuntimeException e) {
            Log.e(TAG, "System speech recognizer did not start", e);
            sendError(activeCallback, SpeechRecognizer.ERROR_CLIENT);
            destroyDelegate();
        }
    }

    private interface CallbackOperation {
        void run(Callback callback) throws RemoteException;
    }

    private void send(Callback callback, CallbackOperation operation) {
        if (callback == null) return;
        try {
            operation.run(callback);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Recognition callback failed", e);
        }
    }

    private void sendError(Callback callback, int error) {
        send(callback, c -> c.error(error));
    }

    private void destroyDelegate() {
        SpeechRecognizer old = delegate;
        delegate = null;
        activeCallback = null;
        if (old != null) {
            try { old.cancel(); } catch (RuntimeException ignored) {}
            try { old.destroy(); } catch (RuntimeException ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        mainHandler.post(this::destroyDelegate);
        super.onDestroy();
    }
        }
