package com.aibale.assistant;

import android.content.Intent;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.os.RemoteException;

public class AssistantRecognitionService extends RecognitionService {

    @Override
    protected void onStartListening(
            Intent recognizerIntent,
            Callback callback
    ) {
        sendError(callback, SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
    }

    @Override
    protected void onStopListening(Callback callback) {
        sendError(callback, SpeechRecognizer.ERROR_CLIENT);
    }

    @Override
    protected void onCancel(Callback callback) {
        sendError(callback, SpeechRecognizer.ERROR_CLIENT);
    }

    private void sendError(Callback callback, int error) {
        try {
            callback.error(error);
        } catch (RemoteException e) {
            android.util.Log.e(
                    "AI Bale",
                    "Could not report speech recognition error",
                    e
            );
        }
    }
}
