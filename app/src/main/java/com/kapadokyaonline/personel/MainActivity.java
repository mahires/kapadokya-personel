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
    private static final int MAX_MAIN_FRAME_RETRIES = 4;

    private WebView webView;
    private ProgressBar progressBar;
    private PermissionRequest pendingCameraRequest;
    private ValueCallback<Uri[]> pendingFileCallback;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int mainFrameRetryCount = 0;
    private boolean mainFrameFailed = false;
    private String lastMainUrl = START_URL;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

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
        s.setDefaultTextEncodingName("UTF-8");
        s.setUserAgentString(s.getUserAgentString() + " KapadokyaPersonelAndroid/1.0.3");

        webView.setWebViewClient(new SafeClient());
        webView.setWebChromeClient(new SafeChrome());

        registerNetworkRecovery();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
            String restored = webView.getUrl();
            if (restored != null && isAllowed(Uri.parse(restored))) {
                lastMainUrl = restored;
            }
        } else {
            webView.loadUrl(START_URL);
        }
    }

    private boolean isAllowed(Uri uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && ALLOWED_HOST.equalsIgnoreCase(uri.getHost());
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "Bağlantı açılamadı.", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isNetworkAvailable() {
        try {
            if (connectivityManager == null) {
                connectivityManager =
                        (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            }
            if (connectivityManager == null) return true;

            Network network = connectivityManager.getActiveNetwork();
            if (network == null) return false;

            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            // Ağ durumunu okuyamazsak gereksiz yere sayfayı engellemeyelim.
            return true;
        }
    }

    private void registerNetworkRecovery() {
        try {
            connectivityManager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

            if (connectivityManager == null) return;

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    handler.postDelayed(() -> {
                        if (mainFrameFailed && webView != null) {
                            mainFrameRetryCount = 0;
                            retryMainFrameNow();
                        }
                    }, 700);
                }
            };

            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ignored) {
            networkCallback = null;
        }
    }

    private void retryMainFrameNow() {
        if (webView == null) return;

        String target = lastMainUrl;
        if (target == null || !isAllowed(Uri.parse(target))) {
            target = START_URL;
        }

        final String retryUrl = target;
        handler.post(() -> {
            if (webView != null) {
                webView.stopLoading();
                webView.loadUrl(retryUrl);
            }
        });
    }

    private void scheduleMainFrameRetry(String failedUrl) {
        if (failedUrl != null && isAllowed(Uri.parse(failedUrl))) {
            lastMainUrl = failedUrl;
        }

        mainFrameFailed = true;

        if (!isNetworkAvailable()) {
            showRecoverableErrorPage(
                    "İnternet bağlantısı bekleniyor",
                    "Telefon yeniden internete bağlandığında uygulama otomatik olarak tekrar deneyecek."
            );
            return;
        }

        if (mainFrameRetryCount < MAX_MAIN_FRAME_RETRIES) {
            mainFrameRetryCount++;
            long delay = 700L * mainFrameRetryCount;

            handler.postDelayed(() -> {
                if (mainFrameFailed && webView != null) {
                    retryMainFrameNow();
                }
            }, delay);
            return;
        }

        showRecoverableErrorPage(
                "Bağlantı geçici olarak kesildi",
                "Sunucu tarayıcıdan açılıyor olsa bile Android WebView bağlantıyı zaman zaman yarıda kesebiliyor. Tekrar Dene düğmesine basın."
        );
    }

    private void showRecoverableErrorPage(String title, String detail) {
        if (webView == null) return;

        String html = "<!doctype html><html><head>"
                + "<meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>"
                + "body{font-family:Arial,sans-serif;background:#f6f7f9;color:#111;margin:0;padding:28px;}"
                + ".box{max-width:520px;margin:70px auto;background:#fff;border-radius:18px;padding:24px;"
                + "box-shadow:0 10px 35px rgba(0,0,0,.08);}"
                + "h2{margin-top:0}p{line-height:1.55;color:#555}"
                + "a{display:inline-block;background:#d90000;color:#fff;text-decoration:none;"
                + "padding:13px 18px;border-radius:10px;font-weight:700}"
                + "</style></head><body><div class='box'>"
                + "<h2>" + escapeHtml(title) + "</h2>"
                + "<p>" + escapeHtml(detail) + "</p>"
                + "<a href='" + START_URL + "'>Tekrar Dene</a>"
                + "</div></body></html>";

        webView.loadDataWithBaseURL(
                "https://" + ALLOWED_HOST + "/",
                html,
                "text/html",
                "UTF-8",
                null
        );
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace(""", "&quot;")
                .replace("'", "&#39;");
    }

    private class SafeClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (isAllowed(uri)) {
                if (request.isForMainFrame()) {
                    lastMainUrl = uri.toString();
                }
                return false;
            }
            openExternal(uri);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            if (url != null && isAllowed(Uri.parse(url))) {
                lastMainUrl = url;
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            CookieManager.getInstance().flush();

            if (url != null
                    && isAllowed(Uri.parse(url))
                    && !url.startsWith("data:")) {
                mainFrameFailed = false;
                mainFrameRetryCount = 0;
                lastMainUrl = url;
            }
        }

        @Override
        public void onReceivedError(
                WebView view,
                WebResourceRequest request,
                WebResourceError error
        ) {
            if (request == null || !request.isForMainFrame()) return;

            Uri uri = request.getUrl();
            if (!isAllowed(uri)) return;

            // SSL hataları burada otomatik tekrar denenmez; güvenlik kontrolü ayrı tutulur.
            int code = error != null ? error.getErrorCode() : ERROR_UNKNOWN;
            if (code == ERROR_FAILED_SSL_HANDSHAKE) return;

            scheduleMainFrameRetry(uri.toString());
        }

        @Override
        public void onReceivedSslError(
                WebView view,
                SslErrorHandler handler,
                SslError error
        ) {
            mainFrameFailed = false;
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
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();

            if (mainFrameFailed && isNetworkAvailable()) {
                handler.postDelayed(this::retryMainFrameNow, 500);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        try {
            if (connectivityManager != null && networkCallback != null) {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception ignored) {
        }

        handler.removeCallbacksAndMessages(null);

        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
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
