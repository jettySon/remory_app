package com.remory.app;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.NoCredentialException;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import org.json.JSONObject;
import java.util.Arrays;
import java.util.Collections;

public final class MainActivity extends ComponentActivity {
    private static final int PICK_FILE = 100;
    private static final int PAPER = Color.rgb(255, 250, 252);
    private WebView webView;
    private ProgressBar progress;
    private FrameLayout root;
    private LinearLayout errorPanel;
    private ValueCallback<Uri[]> fileCallback;
    private CancellationSignal googleRequest;
    private int documentGeneration;
    private boolean pageFailed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(PAPER);
        setContentView(root);
        // Keep the web UI and its fixed bottom navigation outside system bars and the keyboard.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard));
                return WindowInsets.CONSUMED;
            }
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        createWebView();
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        FrameLayout.LayoutParams progressLayout = new FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP);
        root.addView(progress, progressLayout);
        createErrorPanel();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleBack(); }
        });
        if (state == null || webView.restoreState(state) == null) webView.loadUrl(BuildConfig.WEB_URL);
    }

    private void createWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(PAPER);
        root.addView(webView, 0, new FrameLayout.LayoutParams(-1, -1));
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setUserAgentString(settings.getUserAgentString() + " RemoryAndroid/" + BuildConfig.VERSION_NAME);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        installGoogleBridge();

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (SitePolicy.isSameOrigin(BuildConfig.WEB_URL, url)) return false;
                if (request.isForMainFrame() && request.hasGesture()) openExternal(request.getUrl());
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                documentGeneration++;
                cancelGoogleRequest();
                pageFailed = false;
                progress.setVisibility(View.VISIBLE);
                errorPanel.setVisibility(View.GONE);
            }
            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
                // onPageFinished is also called for an error document. Keep its retry screen visible.
                if (pageFailed) errorPanel.setVisibility(View.VISIBLE);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showConnectionError();
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) showConnectionError();
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                cancelGoogleRequest();
                root.removeView(view);
                view.destroy();
                createWebView();
                showConnectionError();
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                if (progress == null) return;
                progress.setProgress(value);
                progress.setVisibility(value >= 100 || pageFailed ? View.GONE : View.VISIBLE);
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (!SitePolicy.isSameOrigin(BuildConfig.WEB_URL, view.getUrl())) return false;
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), PICK_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                    toast(R.string.file_unavailable);
                }
                return true;
            }
        });
        webView.setDownloadListener((url, agent, disposition, mime, length) -> download(url, agent, disposition, mime));
    }

    private void installGoogleBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        WebViewCompat.addWebMessageListener(webView, "RemoryAndroid", Collections.singleton(SitePolicy.origin(BuildConfig.WEB_URL)),
            (view, message, origin, mainFrame, reply) -> {
                if (!mainFrame || !SitePolicy.isSameOrigin(BuildConfig.WEB_URL, origin.toString()) ||
                    !SitePolicy.isSameOrigin(BuildConfig.WEB_URL, view.getUrl())) return;
                try {
                    JSONObject request = new JSONObject(message.getData());
                    String id = request.getString("id");
                    if (!"googleSignIn".equals(request.optString("type"))) return;
                    if (googleRequest != null) { reply(reply, id, null, "BUSY"); return; }
                    String clientId = request.getString("clientId");
                    String nonce = request.getString("nonce");
                    if (!clientId.endsWith(".apps.googleusercontent.com") || nonce.isEmpty() || nonce.length() > 512) {
                        reply(reply, id, null, "INVALID_REQUEST");
                        return;
                    }
                    signInWithGoogle(id, clientId, nonce, reply);
                } catch (Exception ignored) {
                    // No unvalidated input, credentials, or account information is written to logs.
                }
            });
    }

    private void signInWithGoogle(String id, String clientId, String nonce, JavaScriptReplyProxy reply) {
        final int generation = documentGeneration;
        googleRequest = new CancellationSignal();
        GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(clientId).setNonce(nonce).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder().addCredentialOption(option).build();
        CredentialManager.create(this).getCredentialAsync(this, request, googleRequest, this::runOnUiThread,
            new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                @Override public void onResult(GetCredentialResponse result) {
                    if (generation != documentGeneration || isDestroyed()) return;
                    googleRequest = null;
                    try {
                        if (!(result.getCredential() instanceof CustomCredential credential) ||
                            !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
                            reply(reply, id, null, "UNSUPPORTED_CREDENTIAL");
                            return;
                        }
                        String token = GoogleIdTokenCredential.createFrom(credential.getData()).getIdToken();
                        reply(reply, id, token, null);
                    } catch (Exception e) { reply(reply, id, null, "INVALID_CREDENTIAL"); }
                }
                @Override public void onError(GetCredentialException error) {
                    if (generation != documentGeneration || isDestroyed()) return;
                    googleRequest = null;
                    String code = error instanceof GetCredentialCancellationException ? "CANCELED"
                        : error instanceof NoCredentialException ? "NO_ACCOUNT" : "GOOGLE_UNAVAILABLE";
                    reply(reply, id, null, code);
                }
            });
    }

    private void reply(JavaScriptReplyProxy reply, String id, String credential, String error) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        if (isDestroyed() || !SitePolicy.isSameOrigin(BuildConfig.WEB_URL, webView.getUrl())) return;
        try {
            JSONObject result = new JSONObject().put("id", id);
            if (credential != null) result.put("credential", credential);
            if (error != null) result.put("error", error);
            reply.postMessage(result.toString());
        } catch (Exception ignored) { }
    }

    private void cancelGoogleRequest() {
        if (googleRequest != null) googleRequest.cancel();
        googleRequest = null;
    }

    private void handleBack() {
        if (pageFailed || !SitePolicy.isSameOrigin(BuildConfig.WEB_URL, webView.getUrl())) {
            fallbackBack();
            return;
        }
        webView.evaluateJavascript("Boolean(window.__remoryHandleBack && window.__remoryHandleBack())", value -> {
            if (!"true".equals(value)) fallbackBack();
        });
    }

    private void fallbackBack() {
        if (!pageFailed && webView.canGoBack()) webView.goBack();
        else moveTaskToBack(true);
    }

    private void createErrorPanel() {
        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setPadding(dp(28), dp(28), dp(28), dp(28));
        errorPanel.setBackgroundColor(PAPER);
        TextView title = new TextView(this);
        title.setText(R.string.connection_title);
        title.setTextSize(21);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Color.rgb(108, 86, 131));
        errorPanel.addView(title);
        TextView hint = new TextView(this);
        hint.setText(BuildConfig.DEBUG ? R.string.connection_hint_debug : R.string.connection_hint);
        hint.setTextSize(16);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(16), 0, dp(24));
        errorPanel.addView(hint);
        Button retry = new Button(this);
        retry.setText(R.string.retry);
        retry.setOnClickListener(view -> webView.loadUrl(BuildConfig.WEB_URL));
        errorPanel.addView(retry);
        Button browser = new Button(this);
        browser.setText(R.string.open_browser);
        browser.setOnClickListener(view -> openExternal(Uri.parse(BuildConfig.WEB_URL)));
        errorPanel.addView(browser);
        errorPanel.setVisibility(View.GONE);
        root.addView(errorPanel, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showConnectionError() {
        pageFailed = true;
        progress.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void download(String url, String agent, String disposition, String mime) {
        if (!SitePolicy.isSameOrigin(BuildConfig.WEB_URL, url)) { openExternal(Uri.parse(url)); return; }
        // Older Android download folders require broad storage access; let the browser
        // save the site's public templates instead of requesting that permission.
        if (Build.VERSION.SDK_INT < 29) { openExternal(Uri.parse(url)); return; }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) request.addRequestHeader("Cookie", cookie);
            request.addRequestHeader("User-Agent", agent);
            String name = URLUtil.guessFileName(url, disposition, mime);
            request.setTitle(name);
            request.setMimeType(mime);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            getSystemService(DownloadManager.class).enqueue(request);
            toast(R.string.download_started);
        } catch (Exception e) { toast(R.string.download_failed); }
    }

    private void openExternal(Uri uri) {
        if (!Arrays.asList("http", "https", "mailto", "tel").contains(uri.getScheme())) { toast(R.string.link_unavailable); return; }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(intent);
        } catch (ActivityNotFoundException e) { toast(R.string.link_unavailable); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCallback = null;
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        webView.saveState(state);
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() { super.onResume(); webView.onResume(); }
    @Override protected void onPause() { CookieManager.getInstance().flush(); webView.onPause(); super.onPause(); }
    @Override protected void onDestroy() {
        documentGeneration++;
        cancelGoogleRequest();
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        root.removeView(webView);
        webView.destroy();
        super.onDestroy();
    }
    private void toast(int text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
