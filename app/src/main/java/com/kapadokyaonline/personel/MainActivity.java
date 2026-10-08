package com.kapadokyaonline.personel;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String ALLOWED_HOST = "personel.kapadokyaonline.com";
    private static final String START_URL =
            "https://personel.kapadokyaonline.com/employee_portal.php";
    private static final String DIAG_URL =
            "https://personel.kapadokyaonline.com/api_app_diagnostics.php";
    private static final String APP_VERSION = "1.0.4";

    private static final int REQ_CAMERA = 7001;
    private static final int REQ_FILE = 7002;
    private static final int MAX_MAIN_FRAME_RETRIES = 5;

    private static final String PREFS = "kapadokya_personel_native";
    private static final String PREF_PENDING_DIAG = "pending_diagnostic";

    private WebView webView;
    private ProgressBar progressBar;
    private PermissionRequest pendingCameraRequest;
    private ValueCallback<Uri[]> pendingFileCallback;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService diagnosticExecutor =
            Executors.newSingleThreadExecutor();

    private int mainFrameRetryCount = 0;
    private boolean mainFrameFailed = false;
    private boolean showingRecoveryPage = false;
    private boolean rebuildingWebView = false;
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
        configureWebView(webView);
        registerNetworkRecovery();

        if (savedInstanceState != null) {
            try {
                webView.restoreState(savedInstanceState);
                String restored = webView.getUrl();
                if (restored != null && isAllowed(Uri.parse(restored))) {
                    lastMainUrl = restored;
                }
            } catch (Exception ignored) {
                webView.loadUrl(START_URL);
            }
        } else {
            webView.loadUrl(START_URL);
        }
    }

    private void configureWebView(WebView view) {
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(view, false);

        WebSettings s = view.getSettings();
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
        s.setSupportZoom(false);
        s.setUserAgentString(
                s.getUserAgentString()
                        + " KapadokyaPersonelAndroid/" + APP_VERSION
        );

        view.setWebViewClient(new SafeClient());
        view.setWebChromeClient(new SafeChrome());
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
            Toast.makeText(
                    this,
                    "Bağlantı açılamadı.",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private boolean isNetworkAvailable() {
        try {
            if (connectivityManager == null) {
                connectivityManager =
                        (ConnectivityManager) getSystemService(
                                Context.CONNECTIVITY_SERVICE
                        );
            }

            if (connectivityManager == null) return true;

            Network network = connectivityManager.getActiveNetwork();
            if (network == null) return false;

            NetworkCapabilities caps =
                    connectivityManager.getNetworkCapabilities(network);

            return caps != null
                    && caps.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_INTERNET
                    );
        } catch (Exception e) {
            return true;
        }
    }

    private String networkType() {
        try {
            if (connectivityManager == null) {
                connectivityManager =
                        (ConnectivityManager) getSystemService(
                                Context.CONNECTIVITY_SERVICE
                        );
            }

            if (connectivityManager == null) return "UNKNOWN";

            Network network = connectivityManager.getActiveNetwork();
            if (network == null) return "OFFLINE";

            NetworkCapabilities caps =
                    connectivityManager.getNetworkCapabilities(network);

            if (caps == null) return "UNKNOWN";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return "WIFI";
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return "CELLULAR";
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return "ETHERNET";
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return "VPN";
            }

            return "OTHER";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private String webViewVersion() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PackageInfo p = WebView.getCurrentWebViewPackage();
                if (p != null) {
                    return p.packageName + " " + p.versionName;
                }
            }
        } catch (Exception ignored) {
        }

        return "unknown";
    }

    private void registerNetworkRecovery() {
        try {
            connectivityManager =
                    (ConnectivityManager) getSystemService(
                            Context.CONNECTIVITY_SERVICE
                    );

            if (connectivityManager == null) return;

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    handler.postDelayed(() -> {
                        flushPendingDiagnostic();

                        if (
                                (mainFrameFailed || showingRecoveryPage)
                                        && webView != null
                        ) {
                            retryMainFrameNow();
                        }
                    }, 650);
                }
            };

            connectivityManager.registerDefaultNetworkCallback(
                    networkCallback
            );
        } catch (Exception ignored) {
            networkCallback = null;
        }
    }

    private void retryMainFrameNow() {
        if (webView == null || rebuildingWebView) return;

        String target = lastMainUrl;

        if (target == null || !isAllowed(Uri.parse(target))) {
            target = START_URL;
        }

        final String retryUrl = target;

        handler.post(() -> {
            if (webView == null || rebuildingWebView) return;

            showingRecoveryPage = false;

            try {
                webView.stopLoading();
                webView.loadUrl(retryUrl);
            } catch (Exception e) {
                scheduleMainFrameRetry(retryUrl);
            }
        });
    }

    private void scheduleMainFrameRetry(String failedUrl) {
        if (failedUrl != null && isAllowed(Uri.parse(failedUrl))) {
            lastMainUrl = failedUrl;
        }

        mainFrameFailed = true;
        mainFrameRetryCount++;

        if (!isNetworkAvailable()) {
            showRecoverableErrorPage(
                    "İnternet bağlantısı bekleniyor",
                    "Telefon yeniden internete bağlandığında uygulama "
                            + "otomatik olarak tekrar deneyecek."
            );
            return;
        }

        if (mainFrameRetryCount == 3) {
            queueDiagnostic(
                    "WEBVIEW_RECREATE",
                    "AUTO",
                    "Art arda bağlantı hatası nedeniyle WebView yeniden oluşturuluyor.",
                    lastMainUrl
            );
            rebuildWebViewAndLoad();
            return;
        }

        if (mainFrameRetryCount <= MAX_MAIN_FRAME_RETRIES) {
            long delay = Math.min(
                    3500L,
                    650L * mainFrameRetryCount
            );

            handler.postDelayed(() -> {
                if (
                        mainFrameFailed
                                && webView != null
                                && !rebuildingWebView
                ) {
                    retryMainFrameNow();
                }
            }, delay);
            return;
        }

        showRecoverableErrorPage(
                "Bağlantı geçici olarak kesildi",
                "Uygulama bağlantıyı birkaç kez otomatik olarak yeniledi. "
                        + "İnternetiniz açıksa Tekrar Dene düğmesine basın."
        );
    }

    private void rebuildWebViewAndLoad() {
        if (rebuildingWebView) return;
        rebuildingWebView = true;

        handler.post(() -> {
            try {
                CookieManager.getInstance().flush();

                WebView old = webView;
                if (old == null) {
                    rebuildingWebView = false;
                    return;
                }

                ViewGroup parent = (ViewGroup) old.getParent();

                if (parent == null) {
                    rebuildingWebView = false;
                    retryMainFrameNow();
                    return;
                }

                int index = parent.indexOfChild(old);
                ViewGroup.LayoutParams params = old.getLayoutParams();

                try {
                    old.stopLoading();
                    old.setWebChromeClient(null);
                    old.setWebViewClient(null);
                    parent.removeView(old);
                    old.destroy();
                } catch (Exception ignored) {
                }

                WebView fresh = new WebView(MainActivity.this);
                fresh.setId(R.id.webView);

                if (index < 0 || index > parent.getChildCount()) {
                    index = 0;
                }

                parent.addView(fresh, index, params);
                webView = fresh;
                configureWebView(webView);

                final String target =
                        lastMainUrl != null
                                && isAllowed(Uri.parse(lastMainUrl))
                                ? lastMainUrl
                                : START_URL;

                showingRecoveryPage = false;

                handler.postDelayed(() -> {
                    rebuildingWebView = false;

                    if (webView != null) {
                        try {
                            webView.loadUrl(target);
                        } catch (Exception e) {
                            scheduleMainFrameRetry(target);
                        }
                    }
                }, 350);

            } catch (Exception e) {
                rebuildingWebView = false;
                showRecoverableErrorPage(
                        "Uygulama bağlantısı yenilenemedi",
                        "Tekrar Dene düğmesine basarak devam edebilirsiniz."
                );
            }
        });
    }

    private void showRecoverableErrorPage(
            String title,
            String detail
    ) {
        if (webView == null || rebuildingWebView) return;

        showingRecoveryPage = true;

        String html =
                "<!doctype html><html><head>"
                        + "<meta charset='utf-8'>"
                        + "<meta name='viewport' "
                        + "content='width=device-width,initial-scale=1'>"
                        + "<style>"
                        + "body{font-family:Arial,sans-serif;"
                        + "background:#f6f7f9;color:#111;margin:0;padding:28px;}"
                        + ".box{max-width:520px;margin:70px auto;background:#fff;"
                        + "border-radius:18px;padding:24px;"
                        + "box-shadow:0 10px 35px rgba(0,0,0,.08);}"
                        + "h2{margin-top:0}p{line-height:1.55;color:#555}"
                        + "a{display:inline-block;background:#d90000;color:#fff;"
                        + "text-decoration:none;padding:13px 18px;"
                        + "border-radius:10px;font-weight:700}"
                        + "</style></head><body><div class='box'>"
                        + "<h2>" + escapeHtml(title) + "</h2>"
                        + "<p>" + escapeHtml(detail) + "</p>"
                        + "<a href='" + START_URL + "'>Tekrar Dene</a>"
                        + "</div></body></html>";

        try {
            webView.loadDataWithBaseURL(
                    "https://" + ALLOWED_HOST + "/",
                    html,
                    "text/html",
                    "UTF-8",
                    null
            );
        } catch (Exception ignored) {
        }
    }

    private String escapeHtml(String s) {
        if (s == null) return "";

        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private void queueDiagnostic(
            String event,
            String errorCode,
            String description,
            String url
    ) {
        try {
            JSONObject o = new JSONObject();
            o.put("event", event == null ? "" : event);
            o.put("error_code", errorCode == null ? "" : errorCode);
            o.put(
                    "description",
                    description == null ? "" : description
            );
            o.put("url", url == null ? "" : url);
            o.put("app_version", APP_VERSION);
            o.put("android_version", Build.VERSION.RELEASE);
            o.put("manufacturer", Build.MANUFACTURER);
            o.put("model", Build.MODEL);
            o.put("webview_version", webViewVersion());
            o.put("network", networkType());
            o.put(
                    "retry_count",
                    String.valueOf(mainFrameRetryCount)
            );
            o.put(
                    "client_time",
                    String.valueOf(System.currentTimeMillis())
            );

            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_PENDING_DIAG, o.toString())
                    .apply();

            flushPendingDiagnostic();
        } catch (Exception ignored) {
        }
    }

    private void flushPendingDiagnostic() {
        if (!isNetworkAvailable()) return;

        SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);

        String pending =
                prefs.getString(PREF_PENDING_DIAG, "");

        if (pending == null || pending.isEmpty()) return;

        String cookie = "";

        try {
            String c = CookieManager.getInstance().getCookie(DIAG_URL);
            if (c != null) cookie = c;
        } catch (Exception ignored) {
        }

        final String payload = pending;
        final String cookieHeader = cookie;

        diagnosticExecutor.execute(() -> {
            boolean sent = postDiagnostic(
                    payload,
                    cookieHeader
            );

            if (sent) {
                runOnUiThread(() -> {
                    SharedPreferences current =
                            getSharedPreferences(
                                    PREFS,
                                    MODE_PRIVATE
                            );

                    String now =
                            current.getString(
                                    PREF_PENDING_DIAG,
                                    ""
                            );

                    if (payload.equals(now)) {
                        current.edit()
                                .remove(PREF_PENDING_DIAG)
                                .apply();
                    }
                });
            }
        });
    }

    private boolean postDiagnostic(
            String payload,
            String cookie
    ) {
        HttpURLConnection conn = null;

        try {
            JSONObject o = new JSONObject(payload);

            String[] keys = new String[]{
                    "event",
                    "url",
                    "error_code",
                    "description",
                    "app_version",
                    "android_version",
                    "manufacturer",
                    "model",
                    "webview_version",
                    "network",
                    "retry_count",
                    "client_time"
            };

            StringBuilder body = new StringBuilder();

            for (String key : keys) {
                if (body.length() > 0) body.append("&");

                body.append(
                        URLEncoder.encode(
                                key,
                                "UTF-8"
                        )
                );
                body.append("=");
                body.append(
                        URLEncoder.encode(
                                o.optString(key, ""),
                                "UTF-8"
                        )
                );
            }

            byte[] bytes =
                    body.toString().getBytes(
                            StandardCharsets.UTF_8
                    );

            URL u = new URL(DIAG_URL);
            conn =
                    (HttpURLConnection) u.openConnection();

            conn.setRequestMethod("POST");
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(4500);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8"
            );
            conn.setRequestProperty(
                    "User-Agent",
                    "KapadokyaPersonelAndroid/" + APP_VERSION
            );

            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty(
                        "Cookie",
                        cookie
                );
            }

            conn.setFixedLengthStreamingMode(bytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }

            int code = conn.getResponseCode();

            return code >= 200 && code < 300;

        } catch (Exception ignored) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private class SafeClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(
                WebView view,
                WebResourceRequest request
        ) {
            Uri uri = request.getUrl();

            if (isAllowed(uri)) {
                if (request.isForMainFrame()) {
                    showingRecoveryPage = false;
                    lastMainUrl = uri.toString();
                }
                return false;
            }

            openExternal(uri);
            return true;
        }

        @Override
        public void onPageStarted(
                WebView view,
                String url,
                android.graphics.Bitmap favicon
        ) {
            if (
                    !showingRecoveryPage
                            && url != null
                            && isAllowed(Uri.parse(url))
            ) {
                lastMainUrl = url;
            }
        }

        @Override
        public void onPageFinished(
                WebView view,
                String url
        ) {
            CookieManager.getInstance().flush();

            if (
                    !showingRecoveryPage
                            && url != null
                            && isAllowed(Uri.parse(url))
                            && !url.startsWith("data:")
            ) {
                mainFrameFailed = false;
                mainFrameRetryCount = 0;
                lastMainUrl = url;
                flushPendingDiagnostic();
            }
        }

        @Override
        public void onReceivedError(
                WebView view,
                WebResourceRequest request,
                WebResourceError error
        ) {
            if (
                    request == null
                            || !request.isForMainFrame()
            ) {
                return;
            }

            Uri uri = request.getUrl();
            if (!isAllowed(uri)) return;

            int code =
                    error != null
                            ? error.getErrorCode()
                            : ERROR_UNKNOWN;

            String description =
                    error != null
                            && error.getDescription() != null
                            ? error.getDescription().toString()
                            : "WebView bağlantı hatası";

            if (code == ERROR_FAILED_SSL_HANDSHAKE) {
                return;
            }

            queueDiagnostic(
                    "MAIN_FRAME_ERROR",
                    String.valueOf(code),
                    description,
                    uri.toString()
            );

            scheduleMainFrameRetry(
                    uri.toString()
            );
        }

        @Override
        public void onReceivedHttpError(
                WebView view,
                WebResourceRequest request,
                WebResourceResponse errorResponse
        ) {
            if (
                    request == null
                            || !request.isForMainFrame()
                            || !isAllowed(request.getUrl())
                            || errorResponse == null
            ) {
                return;
            }

            int status = errorResponse.getStatusCode();

            if (
                    status == 408
                            || status == 429
                            || status == 500
                            || status == 502
                            || status == 503
                            || status == 504
            ) {
                queueDiagnostic(
                        "HTTP_ERROR",
                        String.valueOf(status),
                        "Ana sayfa HTTP hatası",
                        request.getUrl().toString()
                );

                scheduleMainFrameRetry(
                        request.getUrl().toString()
                );
            }
        }

        @Override
        public boolean onRenderProcessGone(
                WebView view,
                RenderProcessGoneDetail detail
        ) {
            queueDiagnostic(
                    "RENDERER_GONE",
                    detail != null && detail.didCrash()
                            ? "CRASH"
                            : "KILLED",
                    "Android WebView renderer işlemi kapandı.",
                    lastMainUrl
            );

            mainFrameFailed = true;
            mainFrameRetryCount =
                    Math.max(
                            mainFrameRetryCount,
                            2
                    );

            handler.post(
                    MainActivity.this::rebuildWebViewAndLoad
            );

            return true;
        }

        @Override
        public void onReceivedSslError(
                WebView view,
                SslErrorHandler handler,
                SslError error
        ) {
            queueDiagnostic(
                    "SSL_ERROR",
                    error != null
                            ? String.valueOf(error.getPrimaryError())
                            : "SSL",
                    "SSL doğrulama hatası. Bağlantı güvenlik nedeniyle durduruldu.",
                    lastMainUrl
            );

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
        public void onProgressChanged(
                WebView view,
                int newProgress
        ) {
            progressBar.setProgress(newProgress);
            progressBar.setVisibility(
                    newProgress >= 100
                            ? View.GONE
                            : View.VISIBLE
            );
        }

        @Override
        public void onPermissionRequest(
                PermissionRequest request
        ) {
            runOnUiThread(
                    () -> handleWebPermission(request)
            );
        }

        @Override
        public void onPermissionRequestCanceled(
                PermissionRequest request
        ) {
            if (pendingCameraRequest == request) {
                pendingCameraRequest = null;
            }
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

            Intent intent =
                    new Intent(
                            Intent.ACTION_OPEN_DOCUMENT
                    );

            intent.addCategory(
                    Intent.CATEGORY_OPENABLE
            );
            intent.setType("image/*");

            try {
                startActivityForResult(
                        intent,
                        REQ_FILE
                );
                return true;
            } catch (ActivityNotFoundException e) {
                pendingFileCallback = null;
                return false;
            }
        }
    }

    private void handleWebPermission(
            PermissionRequest request
    ) {
        if (
                request == null
                        || !isAllowed(request.getOrigin())
        ) {
            if (request != null) request.deny();
            return;
        }

        boolean wantsCamera = false;

        for (String res : request.getResources()) {
            if (
                    PermissionRequest
                            .RESOURCE_VIDEO_CAPTURE
                            .equals(res)
            ) {
                wantsCamera = true;
                break;
            }
        }

        if (!wantsCamera) {
            request.deny();
            return;
        }

        if (
                checkSelfPermission(
                        Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
        ) {
            request.grant(
                    new String[]{
                            PermissionRequest
                                    .RESOURCE_VIDEO_CAPTURE
                    }
            );
        } else {
            pendingCameraRequest = request;

            requestPermissions(
                    new String[]{
                            Manifest.permission.CAMERA
                    },
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
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode != REQ_CAMERA) return;

        PermissionRequest r = pendingCameraRequest;
        pendingCameraRequest = null;

        if (r == null) return;

        if (
                grantResults.length > 0
                        && grantResults[0]
                        == PackageManager.PERMISSION_GRANTED
        ) {
            r.grant(
                    new String[]{
                            PermissionRequest
                                    .RESOURCE_VIDEO_CAPTURE
                    }
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
        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode != REQ_FILE) return;

        ValueCallback<Uri[]> cb = pendingFileCallback;
        pendingFileCallback = null;

        if (cb == null) return;

        if (
                resultCode == RESULT_OK
                        && data != null
                        && data.getData() != null
        ) {
            cb.onReceiveValue(
                    new Uri[]{
                            data.getData()
                    }
            );
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

            flushPendingDiagnostic();

            if (
                    (mainFrameFailed || showingRecoveryPage)
                            && isNetworkAvailable()
            ) {
                handler.postDelayed(
                        this::retryMainFrameNow,
                        450
                );
            }
        }
    }

    @Override
    protected void onSaveInstanceState(
            Bundle outState
    ) {
        if (webView != null) {
            webView.saveState(outState);
        }

        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        try {
            if (
                    connectivityManager != null
                            && networkCallback != null
            ) {
                connectivityManager
                        .unregisterNetworkCallback(
                                networkCallback
                        );
            }
        } catch (Exception ignored) {
        }

        handler.removeCallbacksAndMessages(null);
        diagnosticExecutor.shutdownNow();

        if (webView != null) {
            try {
                webView.stopLoading();
                webView.setWebChromeClient(null);
                webView.setWebViewClient(null);
                webView.destroy();
            } catch (Exception ignored) {
            }

            webView = null;
        }

        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (
                webView != null
                        && webView.canGoBack()
        ) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
