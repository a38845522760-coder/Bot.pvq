package com.aibale.assistant;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
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
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    public static final String EXTRA_ASSISTANT_QUERY =
            "com.aibale.assistant.EXTRA_ASSISTANT_QUERY";
    public static final String EXTRA_START_VOICE =
            "com.aibale.assistant.EXTRA_START_VOICE";
    public static final String EXTRA_REQUEST_AUDIO_PERMISSION =
            "com.aibale.assistant.EXTRA_REQUEST_AUDIO_PERMISSION";

    private static final String ASSISTANT_PREFS = "ai_bale_assistant";
    private static final String CLIENT_ID_KEY = "client_id";
    private static final String CLIENT_ID_SOURCE_KEY = "client_id_source";
    private static final int REQUEST_WEB_PERMISSIONS = 101;
    private static final int REQUEST_FILE = 102;
    private static final int REQUEST_CAMERA = 103;
    private static final int REQUEST_CAMERA_PERMISSION = 104;
    private static final int REQUEST_SPEECH_PERMISSION = 105;
    private static final int REQUEST_SPEECH_INPUT = 106;
    private static final int REQUEST_AUDIO_SETUP_PERMISSION = 107;

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingWebPermission;
    private Uri cameraImageUri;
    private String spaceUrl;
    private String trustedSpaceHost;
    private String pendingAssistantQuery;
    private boolean pageLoaded;
    private boolean pendingStartVoiceInput;
    private boolean pendingAudioSetupPermission;
    private boolean rolePromptScheduled;

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
                    WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageLoaded = true;
                synchronizeAssistantWorkspace(view, url);
            }

            @Override
            public void onReceivedSslError(
                    WebView view,
                    SslErrorHandler handler,
                    SslError error) {
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
            public void onPermissionRequestCanceled(PermissionRequest request) {
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
                boolean images = acceptsImages(params.getAcceptTypes());

                // Gallery opens directly for image inputs.
                // Capture inputs launch the camera directly.
                if (images && params.isCaptureEnabled()) {
                    launchCamera();
                } else if (images) {
                    launchImageGallery(params);
                } else {
                    launchGeneralFilePicker(params);
                }
                return true;
            }
        });

        webView.loadUrl(spaceUrl);
        webView.postDelayed(this::maybeOfferAssistantRole, 1100L);
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

        return "https://mohamad1039-bot-gemini.hf.space";
    }

    private void readAssistantIntent(Intent intent) {
        if (intent == null) return;

        String query = intent.getStringExtra(EXTRA_ASSISTANT_QUERY);
        if (query != null && !query.trim().isEmpty()) {
            pendingAssistantQuery = query.trim();
        }

        if (intent.getBooleanExtra(EXTRA_START_VOICE, false)) {
            pendingStartVoiceInput = true;
        }

        if (intent.getBooleanExtra(EXTRA_REQUEST_AUDIO_PERMISSION, false)) {
            pendingAudioSetupPermission = true;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readAssistantIntent(intent);

        if (pageLoaded && webView != null) {
            synchronizeAssistantWorkspace(webView, webView.getUrl());
        }
    }

    private void synchronizeAssistantWorkspace(WebView view, String url) {
        Uri pageUri;

        try {
            pageUri = Uri.parse(url == null ? "" : url);
        } catch (Exception e) {
            runPendingPageActions();
            return;
        }

        if (trustedSpaceHost == null
                || pageUri.getHost() == null
                || !trustedSpaceHost.equalsIgnoreCase(pageUri.getHost())) {
            runPendingPageActions();
            return;
        }

        view.evaluateJavascript(
                "(function(){try{return localStorage.getItem('baleMiniAppClientId')||'';}catch(e){return '';}})()",
                encodedValue -> {
                    if (isFinishing() || view != webView) return;

                    String webId = decodeJavascriptString(encodedValue);
                    SharedPreferences preferences = getSharedPreferences(
                            ASSISTANT_PREFS, MODE_PRIVATE);
                    String savedId = preferences.getString(CLIENT_ID_KEY, "");

                    // Prefer the web app's existing ID to preserve chats.
                    if (isValidClientId(webId)) {
                        if (!webId.equals(savedId)) {
                            preferences.edit()
                                    .putString(CLIENT_ID_KEY, webId)
                                    .putString(CLIENT_ID_SOURCE_KEY, "web")
                                    .apply();
                        }

                        runPendingPageActions();
                        return;
                    }

                    // Restore the assistant's ID only if the web app has none.
                    if (isValidClientId(savedId)) {
                        String setIdScript =
                                "(function(){try{localStorage.setItem('baleMiniAppClientId',"
                                        + JSONObject.quote(savedId)
                                        + ");return true;}catch(e){return false;}})()";

                        view.evaluateJavascript(
                                setIdScript,
                                result -> view.postDelayed(view::reload, 100L));
                        return;
                    }

                    runPendingPageActions();
                }
        );
    }

    private String decodeJavascriptString(String encoded) {
        if (encoded == null || encoded.equals("null") || encoded.isEmpty()) {
            return "";
        }

        try {
            Object value = new JSONTokener(encoded).nextValue();
            return value instanceof String ? ((String) value).trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean isValidClientId(String value) {
        return value != null
                && (value.matches("c[a-fA-F0-9]{32}")
                || value.matches("[a-fA-F0-9]{32}"));
    }

    private void runPendingPageActions() {
        injectPendingAssistantQuery();
        startPendingSpeechInputIfReady();
        requestAudioSetupPermissionIfNeeded();
    }

    private void maybeOfferAssistantRole() {
        if (rolePromptScheduled || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }

        rolePromptScheduled = true;

        SharedPreferences preferences = getSharedPreferences(
                ASSISTANT_PREFS, MODE_PRIVATE);

        if (preferences.getBoolean("assistant_role_prompted", false)) {
            return;
        }

        RoleManager roleManager = getSystemService(RoleManager.class);

        if (roleManager == null
                || !roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
                || roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            return;
        }

        preferences.edit()
                .putBoolean("assistant_role_prompted", true)
                .apply();

        new AlertDialog.Builder(this)
                .setTitle("دستیار دیجیتال AI Bale")
                .setMessage(
                        "برای بازکردن AI Bale با نگه‌داشتن دکمهٔ Home، "
                                + "می‌توانی آن را به‌عنوان دستیار پیش‌فرض انتخاب کنی.")
                .setPositiveButton(
                        "انتخاب دستیار",
                        (dialog, which) -> requestAssistantRole())
                .setNegativeButton("فعلاً نه", null)
                .show();
    }

    private void requestAssistantRole() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager roleManager = getSystemService(RoleManager.class);

                if (roleManager != null
                        && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                    startActivity(
                            roleManager.createRequestRoleIntent(
                                    RoleManager.ROLE_ASSISTANT));
                    return;
                }
            }

            startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
        } catch (Exception error) {
            showToast(
                    "از تنظیمات اندروید، بخش برنامه‌های پیش‌فرض و دستیار دیجیتال را باز کن.");
        }
    }

    private boolean handleUrl(Uri uri) {
        if (uri == null) return true;

        String scheme = uri.getScheme();
        String host = uri.getHost();

        if ("about".equalsIgnoreCase(scheme)) return false;

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
                && normalized.equals(trustedSpaceHost.toLowerCase(Locale.ROOT))) {
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
        if (request == null) return;

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
                            Manifest.permission.RECORD_AUDIO);
                }
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                if (checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    addIfMissing(
                            missingPermissions,
                            Manifest.permission.CAMERA);
                }
            } else {
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
                REQUEST_WEB_PERMISSIONS);
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
        if (acceptTypes == null) return false;

        for (String rawType : acceptTypes) {
            if (rawType == null) continue;

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

    private void launchImageGallery(WebChromeClient.FileChooserParams params) {
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
            WebChromeClient.FileChooserParams params) {
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
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (!missing.isEmpty()) {
            requestPermissions(
                    missing.toArray(new String[0]),
                    REQUEST_CAMERA_PERMISSION);
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
                "ai_bale_" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        cameraImageUri = getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values);

        if (cameraImageUri == null) {
            showToast("ایجاد فایل تصویر ممکن نشد.");
            finishFileSelection(null);
            return;
        }

        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
        cameraIntent.setClipData(
                android.content.ClipData.newUri(
                        getContentResolver(),
                        "AI Bale camera image",
                        cameraImageUri));

                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                cameraIntent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

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
        getContentResolver().update(cameraImageUri, values, null, null);
    }

    private void deleteCameraImage() {
        if (cameraImageUri == null) return;

        try {
            getContentResolver().delete(cameraImageUri, null, null);
        } catch (Exception ignored) {
        }

        cameraImageUri = null;
    }

    private void startPendingSpeechInputIfReady() {
        if (!pageLoaded || !pendingStartVoiceInput) return;

        pendingStartVoiceInput = false;
        requestSpeechInput();
    }

    private void requestAudioSetupPermissionIfNeeded() {
        if (!pageLoaded || !pendingAudioSetupPermission) return;

        pendingAudioSetupPermission = false;

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_AUDIO_SETUP_PERMISSION);
        } else {
            showToast("مجوز میکروفن قبلاً فعال است.");
        }
    }

    private void requestSpeechInput() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_SPEECH_PERMISSION);
            return;
        }

        Intent intent = new Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        try {
            startActivityForResult(intent, REQUEST_SPEECH_INPUT);
        } catch (ActivityNotFoundException e) {
            showToast("سرویس تشخیص گفتار در دسترس نیست.");
        }
    }

    private void injectPendingAssistantQuery() {
        if (!pageLoaded || webView == null
                || pendingAssistantQuery == null) {
            return;
        }

        String query = pendingAssistantQuery;
        pendingAssistantQuery = null;

        String script =
                "(function(){" +
                "var t=" + JSONObject.quote(query) + ";" +
                "var e=document.querySelector('#inp')||" +
                "document.querySelector('textarea')||" +
                "document.querySelector('[contenteditable=true]');" +
                "if(!e)return false;" +
                "e.focus();" +
                "if(e.isContentEditable)e.textContent=t;else e.value=t;" +
                "e.dispatchEvent(new Event('input',{bubbles:true}));" +
                "e.dispatchEvent(new Event('change',{bubbles:true}));" +
                "setTimeout(function(){" +
                "var b=document.querySelector('[data-act=send]');" +
                "if(b&&!b.disabled)b.click();" +
                "},150);" +
                "return true;" +
                "})()";

        webView.evaluateJavascript(script, null);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(
                requestCode, permissions, grantResults);

        if (requestCode == REQUEST_WEB_PERMISSIONS) {
            resolvePendingWebPermission();
        } else if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                launchCameraIntent();
            } else {
                finishFileSelection(null);
                showToast("مجوز دوربین داده نشد.");
            }
        } else if (requestCode == REQUEST_SPEECH_PERMISSION) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                requestSpeechInput();
            }
        } else if (requestCode == REQUEST_AUDIO_SETUP_PERMISSION) {
            showToast(
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                            ? "مجوز میکروفن فعال شد."
                            : "مجوز میکروفن داده نشد.");
        }
    }

    private void resolvePendingWebPermission() {
        PermissionRequest request = pendingWebPermission;
        pendingWebPermission = null;

        if (request == null) return;

        boolean granted = true;

        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                granted &= checkSelfPermission(
                        Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED;
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                granted &= checkSelfPermission(
                        Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED;
            } else {
                granted = false;
            }
        }

        if (granted) {
            request.grant(request.getResources());
        } else {
            request.deny();
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_FILE) {
            Uri[] result = null;

            if (resultCode == RESULT_OK) {
                try {
                    result = WebChromeClient.FileChooserParams.parseResult(
                            resultCode, data);
                } catch (Exception ignored) {
                }

                if ((result == null || result.length == 0)
                        && data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }

            finishFileSelection(result);
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

        if (requestCode == REQUEST_SPEECH_INPUT
                && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(
                    RecognizerIntent.EXTRA_RESULTS);

            if (results != null && !results.isEmpty()) {
                pendingAssistantQuery = results.get(0);
                injectPendingAssistantQuery();
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
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
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
