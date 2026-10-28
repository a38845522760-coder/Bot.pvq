package com.aibale.assistant;

import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

public class AssistantRecognitionService extends RecognitionService {

    @Override
    protected void onStartListening(
            Intent recognizerIntent,
            Callback callback
    ) {
        callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
    }

    @Override
    protected void onStopListening(Callback callback) {
        callback.error(SpeechRecognizer.ERROR_CLIENT);
    }

    @Override
    protected void onCancel(Callback callback) {
        callback.error(SpeechRecognizer.ERROR_CLIENT);
    }
}
