package com.kapadokyaonline.personel;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String ALLOWED_HOST = "personel.kapadokyaonline.com";
    private static final String START_URL = "https://personel.kapadokyaonline.com/employee_portal.php";
    private static final int REQ_CAMERA = 7001;
    private static final int REQ_FILE = 7002;
    private static final int MAX_NETWORK_RETRIES = 4;

    private WebView webView;
    private ProgressBar progressBar;
    private PermissionRequest pendingCameraRequest;
    private ValueCallback<Uri[]> pendingFileCallback;

    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private int networkRetryCount = 0;
    private boolean retryScheduled = false;
    private boolean lastLoadHadNetworkError = false;
    private boolean currentMainFrameError = false;
    private String lastMainFrameUrl = START_URL;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean networkCallbackRegistered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progress);

        WebView.setWebContentsDebuggingEnabled(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, false);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setLoadsImagesAutomatically(true);
        s.setBlockNetworkLoads(false);
        s.setUserAgentString(s.getUserAgentString() + " KapadokyaPersonelAndroid/1.0.3");

        webView.setWebViewClient(new SafeClient());
        webView.setWebChromeClient(new SafeChrome());

        registerNetworkRecovery();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }
    }

    private boolean isAllowed(Uri uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && ALLOWED_HOST.equalsIgnoreCase(uri.getHost());
    }

    private boolean isAllowedUrl(String url) {
        try {
            return isAllowed(Uri.parse(url));
        } catch (Exception e) {
            return false;
        }
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "Bağlantı açılamadı.", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean hasUsableNetwork() {
        try {
            if (connectivityManager == null) {
                connectivityManager =
                        (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            }

            Network network = connectivityManager.getActiveNetwork();
            if (network == null) return false;

            NetworkCapabilities caps =
                    connectivityManager.getNetworkCapabilities(network);

            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    private boolean isTransientNetworkError(int code) {
        return code == WebViewClient.ERROR_UNKNOWN
                || code == WebViewClient.ERROR_HOST_LOOKUP
                || code == WebViewClient.ERROR_CONNECT
                || code == WebViewClient.ERROR_IO
                || code == WebViewClient.ERROR_TIMEOUT;
    }

    private long retryDelayForAttempt(int attempt) {
        switch (attempt) {
            case 1: return 700L;
            case 2: return 1600L;
            case 3: return 3200L;
            default: return 5500L;
        }
    }

    private void scheduleNetworkRetry(String url) {
        if (webView == null || retryScheduled) return;
        if (!isAllowedUrl(url)) url = START_URL;

        if (networkRetryCount >= MAX_NETWORK_RETRIES) {
            Toast.makeText(
                    this,
                    "Bağlantı kurulamadı. İnterneti kontrol edip sayfayı tekrar deneyin.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        if (!hasUsableNetwork()) {
            // Ağ geri geldiğinde NetworkCallback tekrar çağıracak.
            return;
        }

        final String retryUrl = url;
        final int attempt = ++networkRetryCount;
        final long delay = retryDelayForAttempt(attempt);

        retryScheduled = true;

        retryHandler.postDelayed(() -> {
            retryScheduled = false;

            if (webView == null || !hasUsableNetwork()) return;

            CookieManager.getInstance().flush();
            webView.stopLoading();
            webView.loadUrl(retryUrl);
        }, delay);
    }

    private void registerNetworkRecovery() {
        try {
            connectivityManager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    runOnUiThread(() -> {
                        if (lastLoadHadNetworkError && webView != null) {
                            networkRetryCount = 0;
                            scheduleNetworkRetry(lastMainFrameUrl);
                        }
                    });
                }
            };

            connectivityManager.registerDefaultNetworkCallback(networkCallback);
            networkCallbackRegistered = true;
        } catch (Exception ignored) {
            networkCallbackRegistered = false;
        }
    }

    private class SafeClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (isAllowed(uri)) return false;
            openExternal(uri);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);

            if (isAllowedUrl(url)) {
                lastMainFrameUrl = url;
            }

            currentMainFrameError = false;
        }

        @Override
        public void onReceivedError(
                WebView view,
                WebResourceRequest request,
                WebResourceError error
        ) {
            super.onReceivedError(view, request, error);

            if (request == null || !request.isForMainFrame()) return;

            currentMainFrameError = true;
            lastLoadHadNetworkError = true;

            String failedUrl =
                    request.getUrl() != null
                            ? request.getUrl().toString()
                            : lastMainFrameUrl;

            if (isAllowedUrl(failedUrl)) {
                lastMainFrameUrl = failedUrl;
            }

            int code = error != null
                    ? error.getErrorCode()
                    : WebViewClient.ERROR_UNKNOWN;

            if (isTransientNetworkError(code)) {
                scheduleNetworkRetry(lastMainFrameUrl);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            CookieManager.getInstance().flush();

            if (!currentMainFrameError && isAllowedUrl(url)) {
                lastLoadHadNetworkError = false;
                networkRetryCount = 0;
                retryScheduled = false;
                lastMainFrameUrl = url;
            }

            super.onPageFinished(view, url);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // SSL hatalarını ASLA bypass etmiyoruz.
            handler.cancel();
            Toast.makeText(
                    MainActivity.this,
                    "Güvenli bağlantı doğrulanamadı.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private class SafeChrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progressBar.setProgress(newProgress);
            progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> handleWebPermission(request));
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (pendingCameraRequest == request) pendingCameraRequest = null;
            request.deny();
        }

        @Override
        public boolean onShowFileChooser(
                WebView webView,
                ValueCallback<Uri[]> filePathCallback,
                FileChooserParams fileChooserParams
        ) {
            if (pendingFileCallback != null) {
                pendingFileCallback.onReceiveValue(null);
            }

            pendingFileCallback = filePathCallback;

            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");

            try {
                startActivityForResult(intent, REQ_FILE);
                return true;
            } catch (ActivityNotFoundException e) {
                pendingFileCallback = null;
                return false;
            }
        }
    }

    private void handleWebPermission(PermissionRequest request) {
        if (request == null || !isAllowed(request.getOrigin())) {
            if (request != null) request.deny();
            return;
        }

        boolean wantsCamera = false;

        for (String res : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                wantsCamera = true;
                break;
            }
        }

        if (!wantsCamera) {
            request.deny();
            return;
        }

        if (checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            request.grant(
                    new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}
            );
        } else {
            pendingCameraRequest = request;
            requestPermissions(
                    new String[]{Manifest.permission.CAMERA},
                    REQ_CAMERA
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQ_CAMERA) return;

        PermissionRequest r = pendingCameraRequest;
        pendingCameraRequest = null;

        if (r == null) return;

        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            r.grant(
                    new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}
            );
        } else {
            r.deny();
            Toast.makeText(
                    this,
                    "QR okutmak için kamera izni gereklidir.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQ_FILE) return;

        ValueCallback<Uri[]> cb = pendingFileCallback;
        pendingFileCallback = null;

        if (cb == null) return;

        if (resultCode == RESULT_OK
                && data != null
                && data.getData() != null) {
            cb.onReceiveValue(new Uri[]{data.getData()});
        } else {
            cb.onReceiveValue(null);
        }
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();

        if (webView != null) {
            webView.onPause();
        }

        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (webView != null) {
            webView.onResume();

            if (lastLoadHadNetworkError) {
                networkRetryCount = 0;
                scheduleNetworkRetry(lastMainFrameUrl);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) {
            webView.saveState(outState);
        }

        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        retryHandler.removeCallbacksAndMessages(null);

        if (networkCallbackRegistered
                && connectivityManager != null
                && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
        }

        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
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
