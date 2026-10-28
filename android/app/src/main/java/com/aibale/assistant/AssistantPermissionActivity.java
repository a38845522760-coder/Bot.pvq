package com.aibale.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class AssistantPermissionActivity extends Activity {

    private static final int REQUEST_MICROPHONE = 7101;

    private boolean waitingForSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (hasMicrophonePermission()) {
            finish();
            return;
        }

        buildScreen();
    }

    private boolean hasMicrophonePermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private int dp(float value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(0xFFFFFFFF);

        TextView title = new TextView(this);
        title.setText("فعال‌سازی گفت‌وگوی صوتی");
        title.setTextSize(21);
        title.setTextColor(0xFF202124);
        title.setGravity(Gravity.CENTER);

        root.addView(
                title,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        TextView description = new TextView(this);
        description.setText(
                "برای اینکه بتوانی با AI Bale صحبت کنی و پاسخ صوتی دریافت کنی، " +
                "دسترسی میکروفن لازم است. میکروفن فقط پس از شروع گفت‌وگوی صوتی " +
                "درخواست استفاده خواهد داشت."
        );
        description.setTextSize(15);
        description.setTextColor(0xFF5F6368);
        description.setGravity(Gravity.CENTER);
        description.setPadding(0, dp(16), 0, dp(22));

        root.addView(
                description,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        Button grantButton = new Button(this);
        grantButton.setText("فعال‌کردن میکروفن");
        grantButton.setAllCaps(false);
        grantButton.setOnClickListener(v -> handleGrantClick());

        root.addView(
                grantButton,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        Button cancelButton = new Button(this);
        cancelButton.setText("فعلاً نه");
        cancelButton.setAllCaps(false);
        cancelButton.setOnClickListener(v -> finish());

        LinearLayout.LayoutParams cancelParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        cancelParams.topMargin = dp(8);
        root.addView(cancelButton, cancelParams);

        setContentView(root);
    }

    private void handleGrantClick() {
        if (hasMicrophonePermission()) {
            finish();
            return;
        }

        boolean alreadyAsked = getPreferences(MODE_PRIVATE)
                .getBoolean("microphone_permission_asked", false);

        boolean shouldExplain =
                shouldShowRequestPermissionRationale(
                        Manifest.permission.RECORD_AUDIO
                );

        if (alreadyAsked && !shouldExplain) {
            showSettingsDialog();
            return;
        }

        if (shouldExplain) {
            new AlertDialog.Builder(this)
                    .setTitle("دسترسی میکروفن")
                    .setMessage(
                            "برای گفت‌وگوی زنده، اجازهٔ استفاده از میکروفن لازم است."
                    )
                    .setNegativeButton("لغو", (dialog, which) -> finish())
                    .setPositiveButton(
                            "ادامه",
                            (dialog, which) -> requestMicrophone()
                    )
                    .show();
            return;
        }

        requestMicrophone();
    }

    private void requestMicrophone() {
        getPreferences(MODE_PRIVATE)
                .edit()
                .putBoolean("microphone_permission_asked", true)
                .apply();

        requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO},
                REQUEST_MICROPHONE
        );
    }

    private void showSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("مجوز میکروفن فعال نیست")
                .setMessage(
                        "برای فعال‌کردن گفت‌وگوی صوتی، مجوز میکروفن را " +
                        "از بخش مجوزهای برنامه در تنظیمات اندروید فعال کن."
                )
                .setNegativeButton("بعداً", (dialog, which) -> finish())
                .setPositiveButton(
                        "بازکردن تنظیمات",
                        (dialog, which) -> {
                            waitingForSettings = true;

                            Intent intent = new Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())
                            );

                            startActivity(intent);
                        }
                )
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (waitingForSettings && hasMicrophonePermission()) {
            waitingForSettings = false;

            Toast.makeText(
                    this,
                    "میکروفن فعال شد. دوباره دستیار AI Bale را باز کن.",
                    Toast.LENGTH_LONG
            ).show();

            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode != REQUEST_MICROPHONE) {
            return;
        }

        if (hasMicrophonePermission()) {
            Toast.makeText(
                    this,
                    "دسترسی میکروفن فعال شد. دستیار را دوباره باز کن.",
                    Toast.LENGTH_LONG
            ).show();
        } else {
            Toast.makeText(
                    this,
                    "بدون مجوز میکروفن، گفت‌وگوی صوتی کار نمی‌کند.",
                    Toast.LENGTH_LONG
            ).show();
        }

        finish();
    }
          }
