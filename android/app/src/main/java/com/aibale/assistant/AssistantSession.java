package com.aibale.assistant;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class AssistantSession extends VoiceInteractionSession {

    private EditText promptInput;

    public AssistantSession(Context context) {
        super(context);
    }

    @Override
    public View onCreateContentView() {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 28, 32, 24);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(getContext());
        title.setText("AI Bale");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(45, 45, 55));
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(getContext());
        subtitle.setText("دستیار هوش مصنوعی");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.rgb(100, 100, 110));
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = 4;
        subtitleParams.bottomMargin = 20;
        root.addView(subtitle, subtitleParams);

        promptInput = new EditText(getContext());
        promptInput.setTextSize(16);
        promptInput.setHint("پیامت را بنویس...");
        promptInput.setGravity(Gravity.TOP | Gravity.RIGHT);
        promptInput.setMinLines(2);
        promptInput.setMaxLines(4);
        promptInput.setPadding(18, 14, 18, 14);
        promptInput.setBackgroundColor(Color.rgb(245, 245, 248));

        LinearLayout.LayoutParams inputParams = matchWrap();
        inputParams.bottomMargin = 14;
        root.addView(promptInput, inputParams);

        Button sendButton = new Button(getContext());
        sendButton.setText("ادامه در گفت‌وگوی AI Bale");
        root.addView(sendButton, matchWrap());

        sendButton.setOnClickListener(v -> {
            String query = promptInput.getText().toString().trim();
            openMainActivity(query, false);
        });

        Button voiceButton = new Button(getContext());
        voiceButton.setText("🎙 گفتن پیام با میکروفون");

        LinearLayout.LayoutParams voiceParams = matchWrap();
        voiceParams.topMargin = 8;
        root.addView(voiceButton, voiceParams);

        voiceButton.setOnClickListener(v -> openMainActivity("", true));

        Button openButton = new Button(getContext());
        openButton.setText("باز کردن دستیار");

        LinearLayout.LayoutParams openParams = matchWrap();
        openParams.topMargin = 8;
        root.addView(openButton, openParams);

        openButton.setOnClickListener(v -> openMainActivity("", false));

        return root;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private void openMainActivity(String query, boolean startVoice) {
        Intent intent = new Intent(getContext(), MainActivity.class);

        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );

        if (query != null && !query.trim().isEmpty()) {
            intent.putExtra(
                    MainActivity.EXTRA_ASSISTANT_QUERY,
                    query.trim()
            );
        }

        if (startVoice) {
            intent.putExtra(MainActivity.EXTRA_START_VOICE, true);
        }

        getContext().startActivity(intent);
        finish();
    }
}
