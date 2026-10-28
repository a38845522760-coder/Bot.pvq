package com.aibale.assistant;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public final class AssistantSession extends VoiceInteractionSession {

    private static final String FALLBACK_SPACE_URL =
            "https://mohamad1039-bot-gemini.hf.space";

    private LinearLayout root;
    private WebView webView;
    private TextView statusView;

    private String spaceUrl;
    private String trustedSpaceHost;

    private boolean pageLoaded;
    private boolean permissionActivityLaunched;

    public AssistantSession(Context context) {
        super(context);
    }

    @SuppressLint({"SetJavaScriptEnabled"})
    @Override
    public View onCreateContentView() {
        root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(Color.WHITE);
        root.setClipToPadding(false);

        // نوار باریک خود دستیار اندروید
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        header.setPadding(dp(12), dp(4), dp(8), dp(4));
        header.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(getContext());
        title.setText("AI Bale");
        title.setTextColor(Color.rgb(32, 33, 36));
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f
                );

        header.addView(title, titleParams);

        Button fullAppButton = createButton("برنامه کامل");
        fullAppButton.setOnClickListener(v -> openFullApp());

        header.addView(
                fullAppButton,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(42)
                )
        );

        Button closeButton = createButton("✕");
        closeButton.setContentDescription("بستن دستیار");
        closeButton.setOnClickListener(v -> {
            stopLiveConversation();
            finish();
        });

        LinearLayout.LayoutParams closeParams =
                new LinearLayout.LayoutParams(dp(44), dp(42));
        closeParams.setMargins(dp(4), 0, 0, 0);

        header.addView(closeButton, closeParams);

        root.addView(
                header,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(54)
                )
        );

        // پیام خطای شبکه؛ در حالت عادی مخفی است.
        statusView = new TextView(getContext());
        statusView.setTextColor(Color.rgb(75, 75, 85));
        statusView.setTextSize(13);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(12), dp(9), dp(12), dp(9));
        statusView.setVisibility(View.GONE);
        statusView.setBackgroundColor(Color.rgb(245, 246, 249));

        statusView.setOnClickListener(v -> {
            if (webView != null) {
                statusView.setText("در حال تلاش مجدد...");
                statusView.setVisibility(View.VISIBLE);
                webView.reload();
            }
        });

        root.addView(
                statusView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        spaceUrl = resolveSpaceUrl();

        Uri baseUri = Uri.parse(spaceUrl);
        trustedSpaceHost = baseUri.getHost();

        webView = new WebView(getContext());
        webView.setBackgroundColor(Color.WHITE);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {
                return handleNavigation(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    String url
            ) {
                return handleNavigation(Uri.parse(url));
            }

            @Override
            public void onPageStarted(
                    WebView view,
                    String url,
                    android.graphics.Bitmap favicon
            ) {
                super.onPageStarted(view, url, favicon);
                pageLoaded = false;
            }

            @Override
            public void onPageFinished(
                    WebView view,
                    String url
            ) {
                super.onPageFinished(view, url);

                Uri uri = Uri.parse(url);

                if (!isTrustedHost(uri.getHost())) {
                    return;
                }

                pageLoaded = true;
                statusView.setVisibility(View.GONE);

                applyCompactLayout();
            }

            @Override
            public void onReceivedSslError(
                    WebView view,
                    SslErrorHandler handler,
                    SslError error
            ) {
                // هرگز خطای گواهی HTTPS را نادیده نگیر.
                handler.cancel();
                showLoadError(
                        "اتصال امن برقرار نشد. برای تلاش دوباره لمس کن."
                );
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
            ) {
                super.onReceivedError(view, request, error);

                if (request != null && request.isForMainFrame()) {
                    showLoadError(
                            "اتصال به AI Bale برقرار نشد. برای تلاش دوباره لمس کن."
                    );
                }
            }

            @Override
            public void onReceivedHttpError(
                    WebView view,
                    WebResourceRequest request,
                    android.webkit.WebResourceResponse response
            ) {
                super.onReceivedHttpError(view, request, response);

                if (request != null
                        && request.isForMainFrame()
                        && response != null
                        && response.getStatusCode() >= 400) {
                    showLoadError(
                            "سرور پاسخ معتبر نداد. برای تلاش دوباره لمس کن."
                    );
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // فقط میکروفن و فقط برای سایت اصلی مورداعتماد.
                // دسترسی‌های دیگر به‌صورت خودکار اعطا نمی‌شوند.
                if (request == null
                        || !isTrustedOrigin(request.getOrigin())) {
                    if (request != null) {
                        request.deny();
                    }
                    return;
                }

                String[] resources = request.getResources();

                if (resources == null
                        || resources.length != 1
                        || !PermissionRequest.RESOURCE_AUDIO_CAPTURE
                                .equals(resources[0])) {
                    request.deny();
                    return;
                }

                if (getContext().checkSelfPermission(
                        Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED) {

                    request.grant(new String[]{
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    });

                } else {
                    request.deny();
                    requestMicrophonePermission();
                }
            }

            @Override
            public void onPermissionRequestCanceled(
                    PermissionRequest request
            ) {
                // درخواست لغوشده دوباره اعطا نمی‌شود.
            }
        });

        root.addView(
                webView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        statusView.setText("در حال اتصال به AI Bale...");
        statusView.setVisibility(View.VISIBLE);

        webView.loadUrl(spaceUrl);

        return root;
    }

    private Button createButton(String text) {
        Button button = new Button(getContext());
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setPadding(dp(7), 0, dp(7), 0);
        return button;
    }

    private int dp(float value) {
        return Math.round(
                value * getContext()
                        .getResources()
                        .getDisplayMetrics().density
        );
    }

    private String resolveSpaceUrl() {
        String configured = BuildConfig.HF_SPACE_URL;

        if (configured != null) {
            configured = configured.trim();

            Uri uri = Uri.parse(configured);

            if ("https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null) {
                return configured;
            }
        }

        return FALLBACK_SPACE_URL;
    }

    private boolean isTrustedHost(String host) {
        return host != null
                && trustedSpaceHost != null
                && trustedSpaceHost.equalsIgnoreCase(host);
    }

    private boolean isTrustedOrigin(Uri origin) {
        return origin != null
                && "https".equalsIgnoreCase(origin.getScheme())
                && isTrustedHost(origin.getHost());
    }

    private boolean handleNavigation(Uri uri) {
        if (uri == null) {
            return true;
        }

        String scheme = uri.getScheme();

        if ("about".equalsIgnoreCase(scheme)) {
            return false;
        }

        if ("https".equalsIgnoreCase(scheme)
                && isTrustedHost(uri.getHost())) {
            return false;
        }

        // دامنه‌های دیگر داخل WebView دستیار اجرا نمی‌شوند.
        if ("https".equalsIgnoreCase(scheme)
                || "http".equalsIgnoreCase(scheme)) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(
                        getContext(),
                        "برنامه‌ای برای بازکردن این لینک پیدا نشد.",
                        Toast.LENGTH_SHORT
                ).show();
            }
        }

        return true;
    }

    private void applyCompactLayout() {
        if (webView == null || !pageLoaded) {
            return;
        }

        String css =
                "html,body{" +
                "height:100%!important;" +
                "min-height:0!important;" +
                "overflow:hidden!important;" +
                "margin:0!important;" +
                "}" +

                ".app{" +
                "height:100%!important;" +
                "min-height:0!important;" +
                "max-width:none!important;" +
                "margin:0!important;" +
                "padding:0!important;" +
                "overflow:hidden!important;" +
                "display:flex!important;" +
                "flex-direction:column!important;" +
                "}" +

                "#topMain,#topChat,#tabs," +
                ".gem-drawer-mask,#shw,#cv,#imgEditor," +
                "#imageGenOverlay{" +
                "display:none!important;" +
                "}" +

                "main{" +
                "display:flex!important;" +
                "flex:1 1 auto!important;" +
                "flex-direction:column!important;" +
                "height:0!important;" +
                "min-height:0!important;" +
                "overflow:hidden!important;" +
                "}" +

                ".screen{" +
                "box-sizing:border-box!important;" +
                "min-height:0!important;" +
                "padding:0 8px!important;" +
                "}" +

                ".screen.on{" +
                "display:block!important;" +
                "height:100%!important;" +
                "overflow-y:auto!important;" +
                "}" +

                "#chat{" +
                "height:100%!important;" +
                "min-height:0!important;" +
                "overflow-y:auto!important;" +
                "padding:0!important;" +
                "}" +

                "#msgs{" +
                "max-width:none!important;" +
                "padding:8px 8px 12px!important;" +
                "gap:12px!important;" +
                "}" +

                ".dock{" +
                "position:relative!important;" +
                "inset:auto!important;" +
                "bottom:auto!important;" +
                "display:block!important;" +
                "flex:0 0 auto!important;" +
                "width:100%!important;" +
                "max-width:none!important;" +
                "box-sizing:border-box!important;" +
                "margin:0!important;" +
                "padding:8px!important;" +
                "background:var(--bg)!important;" +
                "}" +

                // فایل‌ها در پنل کوچک مدیریت نمی‌شوند؛
                // برای پیوست، دکمهٔ برنامهٔ کامل موجود است.
                "#clipBtn{display:none!important;}" +

                ".vc{" +
                "position:absolute!important;" +
                "inset:0!important;" +
                "height:100%!important;" +
                "width:100%!important;" +
                "min-height:0!important;" +
                "box-sizing:border-box!important;" +
                "padding:10px 12px!important;" +
                "}" +

                ".vc-stage{padding:6px 0!important;}" +
                ".vc-ctl{gap:10px!important;padding-bottom:4px!important;}" +
                ".app:before{display:none!important;}" +

                "body.gemini-home-screen #home," +
                "body.gemini-empty-chat #chat{" +
                "min-height:0!important;" +
                "height:100%!important;" +
                "}";

        String script =
                "(function(){" +
                "try{" +
                "var css=" + JSONObject.quote(css) + ";" +
                "var id='ai-bale-assistant-compact-style';" +
                "var style=document.getElementById(id);" +
                "if(!style){" +
                "style=document.createElement('style');" +
                "style.id=id;" +
                "document.head.appendChild(style);" +
                "}" +
                "style.textContent=css;" +

                "var tries=0;" +
                "var timer=setInterval(function(){" +
                "try{" +
                "if(typeof state!=='undefined'&&" +
                "state.online&&state.cid&&typeof go==='function'){" +
                "if(state.page!=='chat')go('chat');" +
                "clearInterval(timer);" +
                "}" +
                "}catch(e){}" +
                "if(++tries>=160)clearInterval(timer);" +
                "},250);" +
                "}catch(e){}" +
                "})();";

        webView.evaluateJavascript(script, null);
    }

    private void showLoadError(String message) {
        if (statusView == null) {
            return;
        }

        statusView.setText(message);
        statusView.setVisibility(View.VISIBLE);
    }

    private void requestMicrophonePermission() {
        if (permissionActivityLaunched) {
            return;
        }

        permissionActivityLaunched = true;

        try {
            Intent intent = new Intent(
                    getContext(),
                    AssistantPermissionActivity.class
            );

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);

        } catch (Exception e) {
            permissionActivityLaunched = false;

            Toast.makeText(
                    getContext(),
                    "برای استفاده از گفت‌وگوی صوتی، مجوز میکروفن را در برنامه فعال کن.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void stopLiveConversation() {
        if (webView == null) {
            return;
        }

        webView.evaluateJavascript(
                "(function(){" +
                "try{" +
                "if(typeof stopVoiceConversation==='function')" +
                "stopVoiceConversation(false);" +
                "}catch(e){}" +
                "})();",
                null
        );
    }

    private void openFullApp() {
        try {
            Intent intent = new Intent(
                    getContext(),
                    MainActivity.class
            );

            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );

            getContext().startActivity(intent);
            finish();

        } catch (Exception e) {
            Toast.makeText(
                    getContext(),
                    "بازکردن برنامه ممکن نشد.",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);

        Dialog dialog = getWindow();

        if (d
