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
import android.webkit.ValueCallback;
import android.webkit.PermissionRequest;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.widget.Toast;

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
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();

                if ("huggingface.co".equals(host) ||
                    (host != null && host.endsWith(".hf.space")) ||
                    "tapi.bale.ai".equals(host)) {
                    return false;
                }

                startActivity(new Intent(Intent.ACTION_VIEW, uri));
                return true;
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl(BuildConfig.HF_SPACE_URL.isEmpty()
            ? "https://huggingface.co"
            : BuildConfig.HF_SPACE_URL);
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
