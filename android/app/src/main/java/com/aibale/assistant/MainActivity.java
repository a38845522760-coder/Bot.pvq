package com.aibale.assistant;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQUEST_PERMISSIONS = 101;
    private static final int REQUEST_FILE = 102;
    private static final int REQUEST_CAMERA = 103;

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraImageUri;
    private PermissionRequest pendingWebPermission;

    private final String[] appPermissions = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
    };

    @SuppressLint({"SetJavaScriptEnabled", "QueryAllPackages"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {
                return handleUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    String url
            ) {
                return handleUrl(Uri.parse(url));
            }

            private boolean handleUrl(Uri uri) {
                String host = uri.getHost();

                if ("https".equalsIgnoreCase(uri.getScheme())
                        && host != null
                        && (host.equals("mohamad1039-bot-Gemini.hf.space")
                        || host.equals("huggingface.co")
                        || host.endsWith(".hf.space")
                        || host.equals("tapi.bale.ai"))) {
                    return false;
                }

                if ("about".equalsIgnoreCase(uri.getScheme())) {
                    return false;
                }

                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                    Toast.makeText(
                            MainActivity.this,
                            "برنامه‌ای برای بازکردن این لینک پیدا نشد.",
                            Toast.LENGTH_SHORT
                    ).show();
                }

                return true;
            }

            @Override
            public void onReceivedSslError(
                    WebView view,
                    SslErrorHandler handler,
                    SslError error
            ) {
                handler.cancel();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean needsAudio = false;
                    boolean needsVideo = false;

                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE
                                .equals(resource)) {
                            needsAudio = true;
                        }

                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE
                                .equals(resource)) {
                            needsVideo = true;
                        }
                    }

                    boolean audioGranted = !needsAudio
                            || checkSelfPermission(
                                    Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED;

                    boolean cameraGranted = !needsVideo
                            || checkSelfPermission(
                                    Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED;

                    if (audioGranted && cameraGranted) {
                        request.grant(request.getResources());
                    } else {
                        pendingWebPermission = request;
                        requestPermissions(
                                appPermissions,
                                REQUEST_PERMISSIONS
                        );
                    }
                });
            }

            @Override
            public void onPermissionRequestCanceled(
                    PermissionRequest request
            ) {
                if (pendingWebPermission == request) {
                    pendingWebPermission = null;
                }
            }

            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params
            ) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }

                fileCallback = callback;

                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, REQUEST_FILE);
                    return true;
                } catch (Exception ignored) {
                    fileCallback = null;
                    Toast.makeText(
                            MainActivity.this,
                            "بازکردن انتخاب‌گر فایل ممکن نشد.",
                            Toast.LENGTH_SHORT
                    ).show();
                    return false;
                }
            }
        });

        requestRuntimePermissionsIfNeeded();

        String spaceUrl = BuildConfig.HF_SPACE_URL;

        if (spaceUrl != null
                && spaceUrl.startsWith("https://")
                && !spaceUrl.trim().isEmpty()) {
            webView.loadUrl(spaceUrl);
        } else {
            webView.loadUrl(
                    "https://mohamad1039-bot-Gemini.hf.space"
            );
        }
    }

    private void requestRuntimePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean audioMissing = checkSelfPermission(
                    Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED;

            boolean cameraMissing = checkSelfPermission(
                    Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED;

            if (audioMissing || cameraMissing) {
                requestPermissions(appPermissions, REQUEST_PERMISSIONS);
            }
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

        if (requestCode == REQUEST_PERMISSIONS) {
            if (pendingWebPermission != null) {
                boolean audioGranted = checkSelfPermission(
                        Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED;

                boolean cameraGranted = checkSelfPermission(
                        Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED;

                boolean allGranted = true;

                for (String resource :
                        pendingWebPermission.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE
                            .equals(resource) && !audioGranted) {
                        allGranted = false;
                    }

                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE
                            .equals(resource) && !cameraGranted) {
                        allGranted = false;
                    }
                }

                if (allGranted) {
                    pendingWebPermission.grant(
                            pendingWebPermission.getResources()
                    );
                } else {
                    pendingWebPermission.deny();
                }

                pendingWebPermission = null;
            }
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_FILE) {
            if (fileCallback != null) {
                Uri[] results = null;

                if (resultCode == RESULT_OK && data != null) {
                    Uri uri = data.getData();

                    if (uri != null) {
                        results = new Uri[]{uri};
                    } else if (Build.VERSION.SDK_INT
                            >= Build.VERSION_CODES.LOLLIPOP) {
                        results = WebChromeClient.FileChooserParams
                                .parseResult(resultCode, data);
                    }
                }

                fileCallback.onReceiveValue(results);
                fileCallback = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }

        if (pendingWebPermission != null) {
            pendingWebPermission.deny();
            pendingWebPermission = null;
        }

        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
        }
