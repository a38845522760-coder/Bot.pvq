package com.aibale.assistant;

import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

public class AssistantSessionService
        extends VoiceInteractionSessionService {

    @Override
    public VoiceInteractionSession onNewSession(
            android.os.Bundle args
    ) {
        return new AssistantSession(this);
    }
        }
