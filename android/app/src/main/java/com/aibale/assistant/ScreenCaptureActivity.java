package com.aibale.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class ScreenCaptureActivity extends Activity {

    public static final String ACTION_CAPTURE_RESULT =
            "com.aibale.assistant.SCREEN_CAPTURE_RESULT";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);

        TextView title = new TextView(this);
        title.setText("اشتراک‌گذاری صفحه با دستیار");
        title.setTextSize(20);
        layout.addView(title);

        TextView description = new TextView(this);
        description.setText(
                "برای ادامه، اجازهٔ اندروید برای ضبط صفحه را تأیید کنید."
        );
        layout.addView(description);

        Button startButton = new Button(this);
        startButton.setText("ادامه و درخواست مجوز");
        layout.addView(startButton);

        startButton.setOnClickListener(v -> requestCapturePermission());

        setContentView(layout);
    }

    private void requestCapturePermission() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(
                        Context.MEDIA_PROJECTION_SERVICE
                );

        if (manager == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        startActivityForResult(
                manager.createScreenCaptureIntent(),
                701
        );
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 701 && resultCode == RESULT_OK && data != null) {
            Intent result = new Intent(ACTION_CAPTURE_RESULT);
            result.setPackage(getPackageName());
            result.putExtra("resultCode", resultCode);
            result.putExtra("resultData", data);
            sendBroadcast(result);
            setResult(RESULT_OK);
        } else {
            setResult(RESULT_CANCELED);
        }

        finish();
    }
}
