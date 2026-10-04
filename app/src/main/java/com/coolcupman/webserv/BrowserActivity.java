package com.coolcupman.webserv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Message;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.util.Locale;

/** Full-screen WebView tuned for device/server admin interfaces. */
public class BrowserActivity extends Activity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_SCHEME_GUESSED = "scheme_guessed";

    private static final int REQ_FILE_CHOOSER = 42;
    private static final String BRIDGE_NAME = "WebServBridge";

    private WebView web;
    private EditText address;
    private ProgressBar progress;
    private View errorPanel;
    private TextView errorText;
    private Button errorAlt;

    private HostStore hosts;
    private CertTrustStore certs;
    private CredentialStore creds;
    private Downloader downloader;

    private String startUrl;
    /** https was chosen for the user; on a connection-level failure retry once with http. */
    private boolean schemeFallbackArmed;
    private String defaultUserAgent;
    private String failedUrl;
    private ValueCallback<Uri[]> pendingFileCallback;
    /** One-time token so only downloads we started can be saved through the JS bridge. */
    private volatile String blobToken;
    /** Avoids looping on wrong saved credentials. */
    private final java.util.Set<String> authTried = new java.util.HashSet<>();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);

        hosts = new HostStore(this);
        certs = new CertTrustStore(this);
        creds = new CredentialStore(this);
        downloader = new Downloader(this, certs, creds);

        web = findViewById(R.id.web);
        address = findViewById(R.id.address);
        progress = findViewById(R.id.progress);
        errorPanel = findViewById(R.id.error_panel);
        errorText = findViewById(R.id.error_text);
        errorAlt = findViewById(R.id.error_alt);
        findViewById(R.id.error_retry).setOnClickListener(v -> retry());
        errorAlt.setOnClickListener(v -> loadOtherScheme());

        startUrl = getIntent().getStringExtra(EXTRA_URL);
        schemeFallbackArmed = getIntent().getBooleanExtra(EXTRA_SCHEME_GUESSED, false)
                && startUrl != null && startUrl.startsWith("https://");
        if (startUrl == null) {
            finish();
            return;
        }

        setupWebView();
        setupAddressBar();

        if (savedInstanceState != null && web.restoreState(savedInstanceState) != null) {
            return;
        }
        load(startUrl);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        // Admin UIs frequently mix http resources into https pages (old firmware).
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        defaultUserAgent = s.getUserAgentString();

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), BRIDGE_NAME);
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener(this::onDownload);
        applyDesktopMode(hosts.desktopMode(UrlUtil.hostKey(startUrl)));
    }

    private void setupAddressBar() {
        address.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                UrlUtil.Target t = UrlUtil.normalize(address.getText().toString(), UrlUtil.SchemeMode.AUTO);
                if (t == null) {
                    Toast.makeText(this, R.string.invalid_address, Toast.LENGTH_SHORT).show();
                } else {
                    schemeFallbackArmed = t.schemeGuessed && t.url.startsWith("https://");
                    hideKeyboard();
                    web.requestFocus();
                    load(t.url);
                }
                return true;
            }
            return false;
        });
    }

    private void load(String url) {
        errorPanel.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        address.setText(url);
        hosts.touch(url);
        web.loadUrl(url);
    }

    private void retry() {
        String url = failedUrl != null ? failedUrl : web.getUrl();
        if (url == null) url = startUrl;
        load(url);
    }

    private void loadOtherScheme() {
        String url = failedUrl != null ? failedUrl : startUrl;
        String other = url.startsWith("https://") ? "http" : "https";
        schemeFallbackArmed = false;
        load(UrlUtil.withScheme(url, other));
    }

    // ---- desktop mode --------------------------------------------------------

    private void applyDesktopMode(boolean on) {
        WebSettings s = web.getSettings();
        if (on) {
            // Same engine version, but advertised as desktop Linux Chrome: many router UIs
            // otherwise serve a crippled "mobile" page or refuse to render.
            String ua = defaultUserAgent
                    .replaceAll("\\(Linux; Android [^)]*\\)", "(X11; Linux x86_64)")
                    .replace(" Mobile", "")
                    .replaceAll(" Version/\\d+(\\.\\d+)*", "");
            s.setUserAgentString(ua);
        } else {
            s.setUserAgentString(defaultUserAgent);
        }
    }

    private boolean desktopModeOn() {
        return !TextUtils.equals(web.getSettings().getUserAgentString(), defaultUserAgent);
    }

    // ---- menu ----------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.browser, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_back).setEnabled(web.canGoBack());
        menu.findItem(R.id.action_forward).setEnabled(web.canGoForward());
        menu.findItem(R.id.action_desktop).setChecked(desktopModeOn());
        String url = web.getUrl() != null ? web.getUrl() : startUrl;
        menu.findItem(R.id.action_favorite).setChecked(hosts.isFavorite(url));
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        String url = web.getUrl() != null ? web.getUrl() : startUrl;
        if (id == R.id.action_reload) {
            if (errorPanel.getVisibility() == View.VISIBLE) retry(); else web.reload();
        } else if (id == R.id.action_back) {
            if (web.canGoBack()) web.goBack();
        } else if (id == R.id.action_forward) {
            if (web.canGoForward()) web.goForward();
        } else if (id == R.id.action_home) {
            load(startUrl);
        } else if (id == R.id.action_desktop) {
            boolean on = !desktopModeOn();
            hosts.setDesktopMode(UrlUtil.hostKey(url), on);
            applyDesktopMode(on);
            web.reload();
        } else if (id == R.id.action_favorite) {
            boolean fav = !hosts.isFavorite(url);
            hosts.setFavorite(url, fav);
            Toast.makeText(this, fav ? R.string.saved_favorite : R.string.removed_favorite,
                    Toast.LENGTH_SHORT).show();
        } else if (id == R.id.action_copy) {
            ClipboardManager cb = getSystemService(ClipboardManager.class);
            cb.setPrimaryClip(ClipData.newPlainText("URL", url));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        } else if (id == R.id.action_external) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
            }
        } else if (id == R.id.action_clear_session) {
            clearSession();
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    /** Logs out of everything: cookies, storage, cache, WebView's auth cache. */
    private void clearSession() {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();
        web.clearCache(true);
        authTried.clear();
        Toast.makeText(this, R.string.session_cleared, Toast.LENGTH_SHORT).show();
        load(startUrl);
    }

    @Override
    public void onBackPressed() {
        if (errorPanel.getVisibility() == View.VISIBLE && web.canGoBack()) {
            errorPanel.setVisibility(View.GONE);
            web.setVisibility(View.VISIBLE);
            web.goBack();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface(BRIDGE_NAME);
            web.destroy();
        }
        super.onDestroy();
    }

    // ---- downloads -------------------------------------------------------------

    private void onDownload(String url, String userAgent, String contentDisposition, String mimeType, long len) {
        Toast.makeText(this, R.string.downloading, Toast.LENGTH_SHORT).show();
        Downloader.Callback cb = this::onDownloadDone;
        if (url.startsWith("data:")) {
            downloader.saveDataUrl(url, android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType), cb);
        } else if (url.startsWith("blob:")) {
            // Blob URLs only exist inside the page: read them there and hand the bytes back.
            String token = Long.toHexString(new SecureRandom().nextLong());
            blobToken = token;
            String name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
            String js = "(function(){var x=new XMLHttpRequest();x.open('GET'," + jsString(url) + ",true);"
                    + "x.responseType='blob';x.onload=function(){var r=new FileReader();"
                    + "r.onloadend=function(){" + BRIDGE_NAME + ".saveData(" + jsString(token) + ",r.result,"
                    + jsString(name) + ");};r.readAsDataURL(x.response);};"
                    + "x.onerror=function(){" + BRIDGE_NAME + ".saveData(" + jsString(token) + ",'','');};"
                    + "x.send();})();";
            web.evaluateJavascript(js, null);
        } else {
            downloader.download(url, userAgent, contentDisposition, mimeType, cb);
        }
    }

    private void onDownloadDone(String name, Uri saved, String error) {
        if (isFinishing()) return;
        if (error != null) {
            Toast.makeText(this, getString(R.string.download_failed, name, error), Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.download_done_title)
                .setMessage(getString(R.string.download_done, name))
                .setPositiveButton(R.string.open, (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_VIEW).setData(saved)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private static String jsString(String s) {
        return org.json.JSONObject.quote(s);
    }

    /** Exposed to page JavaScript; accepts data only with the token of a download we started. */
    private final class Bridge {
        @JavascriptInterface
        public void saveData(String token, String dataUrl, String name) {
            String expected = blobToken;
            if (expected == null || !expected.equals(token)) return;
            blobToken = null;
            if (dataUrl == null || dataUrl.isEmpty()) {
                runOnUiThread(() -> onDownloadDone(name, null, "blob read failed"));
                return;
            }
            downloader.saveDataUrl(dataUrl, name, BrowserActivity.this::onDownloadDone);
        }
    }

    // ---- file upload (firmware, config restore, certificates) ----------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (pendingFileCallback != null) {
                pendingFileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                pendingFileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ---- WebView callbacks --------------------------------------------------------

    private final class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setProgress(newProgress);
            progress.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            setTitle(TextUtils.isEmpty(title) ? getString(R.string.app_name) : title);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = callback;
            Intent i = params.createIntent();
            // Firmware images often have odd extensions/MIME types: allow any file.
            i.setType("*/*");
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            try {
                startActivityForResult(i, REQ_FILE_CHOOSER);
            } catch (ActivityNotFoundException e) {
                pendingFileCallback = null;
                Toast.makeText(BrowserActivity.this, R.string.no_file_picker, Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }

        /** window.open / target=_blank: keep it in this view so the admin session (cookies) carries over. */
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            WebView popup = new WebView(BrowserActivity.this);
            popup.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    web.loadUrl(request.getUrl().toString());
                    v.destroy();
                    return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }
    }

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String scheme = request.getUrl().getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) return false;
            // mailto:, tel:, intent: ... hand to the system.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
            } catch (Exception ignored) {
                // nothing can handle it
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (!address.hasFocus()) address.setText(url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!address.hasFocus()) address.setText(url);
            schemeFallbackArmed = false;
            CookieManager.getInstance().flush();
            invalidateOptionsMenu();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            String url = request.getUrl().toString();
            int code = error.getErrorCode();
            boolean connectionLevel = code == ERROR_CONNECT || code == ERROR_FAILED_SSL_HANDSHAKE
                    || code == ERROR_TIMEOUT || code == ERROR_IO || code == ERROR_UNKNOWN;
            if (schemeFallbackArmed && url.startsWith("https://") && connectionLevel) {
                schemeFallbackArmed = false;
                Toast.makeText(BrowserActivity.this, R.string.https_failed_trying_http, Toast.LENGTH_SHORT).show();
                String http = UrlUtil.withScheme(url, "http");
                view.post(() -> load(http));
                return;
            }
            showError(url, error.getDescription() + " (" + code + ")");
        }

        @Override
        public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
            // The page crashed the renderer: start over cleanly instead of crashing the app.
            String url = view.getUrl() != null ? view.getUrl() : startUrl;
            ((android.view.ViewGroup) view.getParent()).removeView(view);
            view.destroy();
            web = null;
            Intent i = new Intent(BrowserActivity.this, BrowserActivity.class).putExtra(EXTRA_URL, url);
            finish();
            startActivity(i);
            return true;
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            String url = error.getUrl();
            String hostKey = UrlUtil.hostKey(url);
            X509Certificate x509 = CertTrustStore.toX509(error.getCertificate());
            String fp = x509 != null ? CertTrustStore.fingerprint(x509) : null;
            if (certs.isTrusted(hostKey, fp)) {
                handler.proceed();
                return;
            }
            if (isFinishing() || fp == null) {
                handler.cancel();
                return;
            }
            showCertDialog(hostKey, fp, x509, error, handler);
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
            String url = view.getUrl() != null ? view.getUrl() : startUrl;
            Uri u = Uri.parse(url);
            String hostKey = host.equalsIgnoreCase(u.getHost()) ? UrlUtil.hostKey(url)
                    : host.toLowerCase(Locale.ROOT) + ":" + ("http".equals(u.getScheme()) ? 80 : 443);
            String attemptKey = hostKey + "|" + realm;
            CredentialStore.Credential saved = creds.get(hostKey, realm);
            if (saved != null && authTried.add(attemptKey)) {
                handler.proceed(saved.username, saved.password);
                return;
            }
            showAuthDialog(hostKey, realm, saved, handler);
        }
    }

    private void showError(String url, String message) {
        failedUrl = url;
        web.setVisibility(View.INVISIBLE);
        errorPanel.setVisibility(View.VISIBLE);
        errorText.setText(getString(R.string.load_error, UrlUtil.displayHost(url), message));
        errorAlt.setText(url.startsWith("https://") ? R.string.try_http : R.string.try_https);
    }

    private void showCertDialog(String hostKey, String fp, X509Certificate cert, SslError error,
                                SslErrorHandler handler) {
        DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
        StringBuilder msg = new StringBuilder();
        msg.append(getString(R.string.cert_intro, hostKey)).append("\n\n");
        msg.append(getString(R.string.cert_problem)).append(' ').append(describe(error)).append("\n\n");
        msg.append(getString(R.string.cert_subject)).append(' ').append(cert.getSubjectX500Principal().getName()).append('\n');
        msg.append(getString(R.string.cert_issuer)).append(' ').append(cert.getIssuerX500Principal().getName()).append('\n');
        msg.append(getString(R.string.cert_valid)).append(' ')
                .append(df.format(cert.getNotBefore())).append(" – ").append(df.format(cert.getNotAfter())).append("\n\n");
        msg.append("SHA-256:\n").append(CertTrustStore.pretty(fp)).append("\n\n");
        msg.append(getString(R.string.cert_advice));

        final boolean[] decided = {false};
        new AlertDialog.Builder(this)
                .setTitle(R.string.cert_title)
                .setMessage(msg)
                .setPositiveButton(R.string.cert_trust_always, (d, w) -> {
                    decided[0] = true;
                    certs.trustPermanently(hostKey, fp);
                    handler.proceed();
                })
                .setNeutralButton(R.string.cert_trust_once, (d, w) -> {
                    decided[0] = true;
                    certs.trustForSession(hostKey, fp);
                    handler.proceed();
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    decided[0] = true;
                    handler.cancel();
                })
                .setOnDismissListener(d -> {
                    if (!decided[0]) handler.cancel();
                })
                .show();
    }

    private String describe(SslError e) {
        StringBuilder sb = new StringBuilder();
        if (e.hasError(SslError.SSL_UNTRUSTED)) sb.append(getString(R.string.ssl_untrusted)).append("; ");
        if (e.hasError(SslError.SSL_IDMISMATCH)) sb.append(getString(R.string.ssl_idmismatch)).append("; ");
        if (e.hasError(SslError.SSL_EXPIRED)) sb.append(getString(R.string.ssl_expired)).append("; ");
        if (e.hasError(SslError.SSL_NOTYETVALID)) sb.append(getString(R.string.ssl_notyetvalid)).append("; ");
        if (e.hasError(SslError.SSL_DATE_INVALID)) sb.append(getString(R.string.ssl_date_invalid)).append("; ");
        if (e.hasError(SslError.SSL_INVALID)) sb.append(getString(R.string.ssl_invalid)).append("; ");
        if (sb.length() >= 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private void showAuthDialog(String hostKey, String realm, CredentialStore.Credential prefill,
                                HttpAuthHandler handler) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText user = new EditText(this);
        user.setHint(R.string.username);
        user.setSingleLine(true);
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText pass = new EditText(this);
        pass.setHint(R.string.password);
        pass.setSingleLine(true);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox remember = new CheckBox(this);
        remember.setText(R.string.remember_login);
        remember.setChecked(prefill != null);
        if (prefill != null) user.setText(prefill.username);
        box.addView(user);
        box.addView(pass);
        box.addView(remember);

        final boolean[] decided = {false};
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.auth_title, hostKey))
                .setMessage(TextUtils.isEmpty(realm) ? null : getString(R.string.auth_realm, realm))
                .setView(box)
                .setPositiveButton(R.string.login, (d, w) -> {
                    decided[0] = true;
                    String u = user.getText().toString();
                    String p = pass.getText().toString();
                    if (remember.isChecked()) {
                        creds.put(hostKey, realm, u, p);
                    } else {
                        creds.remove(hostKey, realm);
                    }
                    handler.proceed(u, p);
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    decided[0] = true;
                    handler.cancel();
                })
                .setOnDismissListener(d -> {
                    if (!decided[0]) handler.cancel();
                })
                .show();
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(address.getWindowToken(), 0);
    }
}
