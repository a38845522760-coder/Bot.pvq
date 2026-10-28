package com.aibale.assistant;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebResourceRequest;

public class MainActivity extends Activity {

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
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

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {
                Uri uri = request.getUrl();
                String host = uri.getHost();

                if (host != null &&
                    (host.equals("huggingface.co") ||
                     host.endsWith(".hf.space") ||
                     host.equals("tapi.bale.ai"))) {
                    return false;
                }

                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    startActivity(intent);
                } catch (Exception ignored) {
                    // اگر مرورگر دیگری موجود نباشد، برنامه بسته نمی‌شود.
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

        webView.setWebChromeClient(new WebChromeClient());

        String spaceUrl = BuildConfig.HF_SPACE_URL;

        if (spaceUrl != null && !spaceUrl.trim().isEmpty()) {
            webView.loadUrl(spaceUrl);
        } else {
            webView.loadUrl("https://huggingface.co");
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
}
