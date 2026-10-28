package com.aibale.assistant;

import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class AssistantSession extends VoiceInteractionSession {

    public AssistantSession(
            android.content.Context context
    ) {
        super(context);
    }

    @Override
    public View onCreateContentView() {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);

        TextView title = new TextView(getContext());
        title.setText("دستیار هوش مصنوعی بله");
        title.setTextSize(22);
        layout.addView(title);

        TextView description = new TextView(getContext());
        description.setText(
                "برای ادامه، برنامه AI Bale را باز کنید."
        );
        description.setTextSize(16);
        layout.addView(description);

        Button openButton = new Button(getContext());
        openButton.setText("باز کردن دستیار");

        openButton.setOnClickListener(v -> {
            Intent intent = new Intent(
                    getContext(),
                    MainActivity.class
            );
            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            );
            getContext().startActivity(intent);
            finish();
        });

        layout.addView(openButton);
        return layout;
    }
}
