package com.aibale.assistant;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
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

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    public static final String EXTRA_ASSISTANT_QUERY =
            "com.aibale.assistant.EXTRA_ASSISTANT_QUERY";

    public static final String EXTRA_START_VOICE =
            "com.aibale.assistant.EXTRA_START_VOICE";

    private static final int REQUEST_WEB_PERMISSIONS = 101;
    private static final int REQUEST_FILE = 102;
    private static final int REQUEST_CAMERA = 103;
    private static final int REQUEST_CAMERA_PERMISSION = 104;
    private static final int REQUEST_SPEECH_PERMISSION = 105;
    private static final int REQUEST_SPEECH_INPUT = 106;

    private WebView webView;

    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingWebPermission;

    private Uri cameraImageUri;

    private String spaceUrl;
    private String trustedSpaceHost;

    private String pendingAssistantQuery;
    private boolean pageLoaded;
    private boolean pendingStartVoiceInput;

    @SuppressLint({"SetJavaScriptEnabled", "QueryAllPackages"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        readAssistantIntent(getIntent());

        spaceUrl = resolveSpaceUrl();
        trustedSpaceHost = Uri.parse(spaceUrl).getHost();

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

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

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                pageLoaded = true;

                injectPendingAssistantQuery();
                startPendingSpeechInputIfReady();
            }

            @Override
            public void onReceivedSslError(
                    WebView view,
                    SslErrorHandler handler,
                    SslError error
            ) {
                // Never bypass certificate errors.
                handler.cancel();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleWebPermissionRequest(request));
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
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params
            ) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                }

                fileCallback = callback;

                if (acceptsImages(params.getAcceptTypes())) {
                    if (params.isCaptureEnabled()) {
                        launchCamera();
                    } else {
                        showImagePickerDialog(params);
                    }
                } else {
                    launchGeneralFilePicker(params);
                }

                return true;
            }
        });

        webView.loadUrl(spaceUrl);
    }

    private String resolveSpaceUrl() {
        String configured = BuildConfig.HF_SPACE_URL;

        if (configured != null) {
            configured = configured.trim();
        }

        if (configured != null
                && configured.startsWith("https://")
                && Uri.parse(configured).getHost() != null) {
            return configured;
        }

        return "https://mohamad1039-bot-Gemini.hf.space";
    }

    private void readAssistantIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        String query = intent.getStringExtra(EXTRA_ASSISTANT_QUERY);

        if (query != null && !query.trim().isEmpty()) {
            pendingAssistantQuery = query.trim();
        }

        if (intent.getBooleanExtra(EXTRA_START_VOICE, false)) {
            pendingStartVoiceInput = true;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        readAssistantIntent(intent);

        if (pageLoaded) {
            injectPendingAssistantQuery();
            startPendingSpeechInputIfReady();
        }
    }

    private boolean handleUrl(Uri uri) {
        if (uri == null) {
            return true;
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();

        if ("about".equalsIgnoreCase(scheme)) {
            return false;
        }

        if ("https".equalsIgnoreCase(scheme)
                && host != null
                && isAllowedNavigationHost(host)) {
            return false;
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            showToast("برنامه‌ای برای بازکردن این لینک پیدا نشد.");
        }

        return true;
    }

    private boolean isAllowedNavigationHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);

        if (trustedSpaceHost != null
                && normalized.equals(
                        trustedSpaceHost.toLowerCase(Locale.ROOT))) {
            return true;
        }

        return normalized.endsWith(".hf.space")
                || normalized.equals("huggingface.co")
                || normalized.endsWith(".huggingface.co")
                || normalized.equals("tapi.bale.ai");
    }

    private boolean isTrustedMediaOrigin(Uri origin) {
        if (origin == null
                || !"https".equalsIgnoreCase(origin.getScheme())
                || origin.getHost() == null
                || trustedSpaceHost == null) {
            return false;
        }

        return trustedSpaceHost.equalsIgnoreCase(origin.getHost());
    }

    // ---------------------------------------------------------
    // WebView microphone and camera permissions
    // ---------------------------------------------------------

    private void handleWebPermissionRequest(PermissionRequest request) {
        if (request == null) {
            return;
        }

        if (!isTrustedMediaOrigin(request.getOrigin())) {
            request.deny();
            return;
        }

        String[] resources = request.getResources();

        if (resources == null || resources.length == 0) {
            request.deny();
            return;
        }

        List<String> missingPermissions = new ArrayList<>();

        for (String resource : resources) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    addIfMissing(
                            missingPermissions,
                            Manifest.permission.RECORD_AUDIO
                    );
                }

            } else if (
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
            ) {
                if (checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    addIfMissing(
                            missingPermissions,
                            Manifest.permission.CAMERA
                    );
                }

            } else {
                // Do not grant unknown WebView resources.
                request.deny();
                return;
            }
        }

        if (missingPermissions.isEmpty()) {
            request.grant(resources);
            return;
        }

        if (pendingWebPermission != null) {
            pendingWebPermission.deny();
        }

        pendingWebPermission = request;

        requestPermissions(
                missingPermissions.toArray(new String[0]),
                REQUEST_WEB_PERMISSIONS
        );
    }

    private void addIfMissing(List<String> list, String permission) {
        if (!list.contains(permission)) {
            list.add(permission);
        }
    }

    // ---------------------------------------------------------
    // Camera and gallery
    // ---------------------------------------------------------

    private boolean acceptsImages(String[] acceptTypes) {
        if (acceptTypes == null) {
            return false;
        }

        for (String rawType : acceptTypes) {
            if (rawType == null) {
                continue;
            }

            String type = rawType.toLowerCase(Locale.ROOT).trim();

            if (type.startsWith("image/")
                    || type.endsWith(".jpg")
                    || type.endsWith(".jpeg")
                    || type.endsWith(".png")
                    || type.endsWith(".webp")
                    || type.endsWith(".gif")
                    || type.endsWith(".heic")) {
                return true;
            }
        }

        return false;
    }

    private void showImagePickerDialog(
            WebChromeClient.FileChooserParams params
    ) {
        String[] options = {
                "گرفتن عکس با دوربین",
                "انتخاب تصویر از گالری"
        };

        new AlertDialog.Builder(this)
                .setTitle("انتخاب تصویر")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        launchCamera();
                    } else {
                        launchImageGallery(params);
                    }
                })
                .setOnCancelListener(dialog -> finishFileSelection(null))
                .show();
    }

    private void launchImageGallery(
            WebChromeClient.FileChooserParams params
    ) {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");

            if (params.getMode()
                    == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }

            startActivityForResult(intent, REQUEST_FILE);

        } catch (ActivityNotFoundException e) {
            showToast("گالری یا انتخابگر تصویر در دسترس نیست.");
            finishFileSelection(null);
        }
    }

    private void launchGeneralFilePicker(
            WebChromeClient.FileChooserParams params
    ) {
        try {
            Intent intent = params.createIntent();
            startActivityForResult(intent, REQUEST_FILE);

        } catch (Exception e) {
            showToast("بازکردن انتخابگر فایل ممکن نشد.");
            finishFileSelection(null);
        }
    }

    private void launchCamera() {
        List<String> missing = new ArrayList<>();

        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (!missing.isEmpty()) {
            requestPermissions(
                    missing.toArray(new String[0]),
                    REQUEST_CAMERA_PERMISSION
            );
            return;
        }

        launchCameraIntent();
    }

    private void launchCameraIntent() {
        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

        if (cameraIntent.resolveActivity(getPackageManager()) == null) {
            showToast("برنامهٔ دوربین در دسترس نیست.");
            finishFileSelection(null);
            return;
        }

        ContentValues values = new ContentValues();
        values.put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "ai_bale_" + System.currentTimeMillis() + ".jpg"
        );
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        cameraImageUri = getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
        );

        if (cameraImageUri == null) {
            showToast("ایجاد فایل تصویر ممکن نشد.");
            finishFileSelection(null);
            return;
        }

        cameraIntent.putExtra(
                MediaStore.EXTRA_OUTPUT,
                cameraImageUri
        );

        cameraIntent.setClipData(
                android.content.ClipData.newUri(
                        getContentResolver(),
                        "AI Bale camera image",
                        cameraImageUri
                )
        );

        cameraIntent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        );

        try {
            startActivityForResult(cameraIntent, REQUEST_CAMERA);

        } catch (ActivityNotFoundException e) {
            deleteCameraImage();
            showToast("بازکردن دوربین ممکن نشد.");
            finishFileSelection(null);
        }
    }

    private void publishCameraImage() {
        if (cameraImageUri == null
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);

        getContentResolver().update(
                cameraImageUri,
                values,
                null,
                null
        );
    }

    private void deleteCameraImage() {
        if (cameraImageUri != null) {
            try {
                getContentResolver().delete(
                        cameraImageUri,
                        null,
                        null
                );
            } catch (Exception ignored) {
                // Ignore cleanup errors.
            }

            cameraImageUri = null;
        }
    }

    // ---------------------------------------------------------
    // Native voice input for the assistant overlay
    // ---------------------------------------------------------

    private void startPendingSpeechInputIfReady() {
        if (!pageLoaded || !pendingStartVoiceInput) {
            return;
        }

        pendingStartVoiceInput = false;
        requestSpeechInput();
    }

    private void requestSpeechInput() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_SPEECH_PERMISSION
            );
            return;
        }

        launchSpeechRecognition();
    }

    private void launchSpeechRecognition() {
        Intent intent = new Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        );

        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );

        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                "fa-IR"
        );

        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                "fa-IR"
        );

        intent.putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                1
        );

        intent.putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "پیامت را بگو"
        );

        try {
            startActivityForResult(intent, REQUEST_SPEECH_INPUT);
        } catch (ActivityNotFoundException e) {
            showToast("سرویس تشخیص گفتار در این دستگاه در دسترس نیست.");
        }
    }

    // ---------------------------------------------------------
    // Put assistant text into the visible web chat input
    // ---------------------------------------------------------

    private void injectPendingAssistantQuery() {
        if (!pageLoaded
                || webView == null
                || pendingAssistantQuery == null
                || pendingAssistantQuery.trim().isEmpty()) {
            return;
        }

        String query = pendingAssistantQuery;
        pendingAssistantQuery = null;

        final String quotedText = JSONObject.quote(query);

        String javascript =
                "(function() {" +
                " var text = " + quotedText + ";" +
                " var tries = 0;" +
                " var timer = setInterval(function() {" +
                "   var visible = function(e) {" +
                "     return e && e.getClientRects().length > 0" +
                "       && getComputedStyle(e).visibility !== 'hidden';" +
                "   };" +
                "   var areas = Array.from(document.querySelectorAll('textarea'))" +
                "       .filter(visible);" +
                "   var editors = Array.from(document.querySelectorAll(" +
                "       '[contenteditable=\"true\"], [role=\"textbox\"]'))" +
                "       .filter(visible);" +
                "   var inputs = Array.from(document.querySelectorAll(" +
                "       'input[type=\"text\"], input:not([type])'))" +
                "       .filter(visible);" +
                "   var el = areas[areas.length - 1]" +
                "       || editors[editors.length - 1]" +
                "       || inputs[inputs.length - 1];" +
                "   if (el) {" +
                "     clearInterval(timer);" +
                "     el.focus();" +
                "     if (el.isContentEditable || el.getAttribute('role') === 'textbox') {" +
                "       el.textContent = text;" +
                "     } else {" +
                "       var d = Object.getOwnPropertyDescriptor(" +
                "           Object.getPrototypeOf(el), 'value');" +
                "       if (d && d.set) d.set.call(el, text);" +
                "       else el.value = text;" +
                "     }" +
                "     el.dispatchEvent(new Event('input', {bubbles:true}));" +
                "     el.dispatchEvent(new Event('change', {bubbles:true}));" +
                "   }" +
                "   if (++tries >= 24) clearInterval(timer);" +
                " }, 250);" +
                "})();";

        webView.evaluateJavascript(javascript, null);
    }

    // ---------------------------------------------------------
    // Permission results
    // ---------------------------------------------------------

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

        if (requestCode == REQUEST_WEB_PERMISSIONS) {
            resolvePendingWebPermission();
            return;
        }

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            boolean granted =
                    checkSelfPermission(Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED;

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                granted = granted
                        && checkSelfPermission(
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED;
            }

            if (granted) {
                launchCameraIntent();
            } else {
                showToast("برای گرفتن عکس باید مجوز دوربین داده شود.");
                finishFileSelection(null);
            }

            return;
        }

        if (requestCode == REQUEST_SPEECH_PERMISSION) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                launchSpeechRecognition();
            } else {
                showToast("برای دیکته‌کردن پیام، مجوز میکروفون لازم است.");
            }
        }
    }

    private void resolvePendingWebPermission() {
        PermissionRequest request = pendingWebPermission;
        pendingWebPermission = null;

        if (request == null) {
            return;
        }

        boolean allGranted = isTrustedMediaOrigin(request.getOrigin());

        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                allGranted = allGranted
                        && checkSelfPermission(
                                Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED;

            } else if (
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
            ) {
                allGranted = allGranted
                        && checkSelfPermission(
                                Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED;

            } else {
                allGranted = false;
            }
        }

        if (allGranted) {
            request.grant(request.getResources());
        } else {
            request.deny();
        }
    }

    // ---------------------------------------------------------
    // Activity results
    // ---------------------------------------------------------

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_FILE) {
            if (resultCode != RESULT_OK) {
                finishFileSelection(null);
                return;
            }

            Uri[] results = null;

            try {
                results = WebChromeClient.FileChooserParams.parseResult(
                        resultCode,
                        data
                );
            } catch (Exception ignored) {
                // Some providers return a URI in a different format.
            }

            if ((results == null || results.length == 0)
                    && data != null
                    && data.getData() != null) {
                results = new Uri[]{data.getData()};
            }

            finishFileSelection(results);
            return;
        }

        if (requestCode == REQUEST_CAMERA) {
            if (resultCode == RESULT_OK && cameraImageUri != null) {
                Uri result = cameraImageUri;

                publishCameraImage();
                cameraImageUri = null;

                finishFileSelection(new Uri[]{result});

            } else {
                deleteCameraImage();
                finishFileSelection(null);
            }

            return;
        }

        if (requestCode == REQUEST_SPEECH_INPUT) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> results = data.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS
                );

                if (results != null
                        && !results.isEmpty()
                        && results.get(0) != null
                        && !results.get(0).trim().isEmpty()) {
                    pendingAssistantQuery = results.get(0).trim();
                    injectPendingAssistantQuery();
                }
            }
        }
    }

    private void finishFileSelection(Uri[] result) {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    private void showToast(String message) {
        Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
        ).show();
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
