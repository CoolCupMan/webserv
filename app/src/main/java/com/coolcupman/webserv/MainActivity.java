package com.coolcupman.webserv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private EditText input;
    private RadioGroup schemeGroup;
    private Button scanButton;
    private ProgressBar scanProgress;
    private HostAdapter adapter;

    private HostStore hosts;
    private PortScanner scanner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hosts = new HostStore(this);
        scanner = new PortScanner();

        input = findViewById(R.id.input);
        schemeGroup = findViewById(R.id.scheme_group);
        scanButton = findViewById(R.id.scan);
        scanProgress = findViewById(R.id.scan_progress);
        ListView list = findViewById(R.id.hosts);
        list.setEmptyView(findViewById(R.id.hosts_empty));

        findViewById(R.id.open).setOnClickListener(v -> openTyped());
        scanButton.setOnClickListener(v -> scanTyped());
        findViewById(R.id.gateway).setOnClickListener(v -> fillGateway());
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                openTyped();
                return true;
            }
            return false;
        });

        adapter = new HostAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> open(adapter.getItem(pos).url, false));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            showHostActions(adapter.getItem(pos));
            return true;
        });

        TextView footer = findViewById(R.id.footer);
        footer.setText(getString(R.string.footer_version, BuildConfig.VERSION_NAME, BuildConfig.APPLICATION_ID));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHosts();
    }

    @Override
    protected void onDestroy() {
        scanner.shutdown();
        super.onDestroy();
    }

    private UrlUtil.SchemeMode schemeMode() {
        int id = schemeGroup.getCheckedRadioButtonId();
        if (id == R.id.scheme_https) return UrlUtil.SchemeMode.HTTPS;
        if (id == R.id.scheme_http) return UrlUtil.SchemeMode.HTTP;
        return UrlUtil.SchemeMode.AUTO;
    }

    private void openTyped() {
        UrlUtil.Target t = UrlUtil.normalize(input.getText().toString(), schemeMode());
        if (t == null) {
            input.setError(getString(R.string.invalid_address));
            return;
        }
        open(t.url, t.schemeGuessed);
    }

    private void open(String url, boolean schemeGuessed) {
        hosts.touch(url);
        startActivity(new Intent(this, BrowserActivity.class)
                .putExtra(BrowserActivity.EXTRA_URL, url)
                .putExtra(BrowserActivity.EXTRA_SCHEME_GUESSED, schemeGuessed));
    }

    // ---- port scan -------------------------------------------------------------

    private void scanTyped() {
        UrlUtil.Target t = UrlUtil.normalize(input.getText().toString(), UrlUtil.SchemeMode.AUTO);
        if (t == null) {
            input.setError(getString(R.string.invalid_address));
            return;
        }
        String host = android.net.Uri.parse(t.url).getHost();
        scanButton.setEnabled(false);
        scanProgress.setVisibility(View.VISIBLE);
        scanner.scan(host, (h, open, error) -> {
            if (isFinishing() || isDestroyed()) return;
            scanButton.setEnabled(true);
            scanProgress.setVisibility(View.GONE);
            if (error != null) {
                new AlertDialog.Builder(this).setTitle(R.string.scan_failed)
                        .setMessage(error).setPositiveButton(android.R.string.ok, null).show();
                return;
            }
            showScanResults(h, open);
        });
    }

    private void showScanResults(String host, List<PortScanner.Result> open) {
        if (open.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.scan_title, host))
                    .setMessage(R.string.scan_nothing)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String hostPart = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        String[] labels = new String[open.size()];
        String[] urls = new String[open.size()];
        for (int i = 0; i < open.size(); i++) {
            PortScanner.Result r = open.get(i);
            urls[i] = r.scheme + "://" + hostPart + ":" + r.port + "/";
            labels[i] = r.scheme.toUpperCase() + "  :" + r.port + "  —  " + r.label;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.scan_title, host))
                .setItems(labels, (d, which) -> open(urls[which], false))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- gateway -------------------------------------------------------------------

    /** Fills in the default gateway of the current network — usually the router's admin page. */
    private void fillGateway() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network net = cm != null ? cm.getActiveNetwork() : null;
        LinkProperties lp = net != null ? cm.getLinkProperties(net) : null;
        if (lp != null) {
            for (RouteInfo r : lp.getRoutes()) {
                if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) {
                    input.setText(r.getGateway().getHostAddress());
                    input.setSelection(input.length());
                    return;
                }
            }
        }
        Toast.makeText(this, R.string.no_gateway, Toast.LENGTH_SHORT).show();
    }

    // ---- saved hosts -----------------------------------------------------------------

    private void refreshHosts() {
        adapter.clear();
        adapter.addAll(hosts.all());
    }

    private void showHostActions(HostStore.Entry e) {
        String[] items = {
                getString(R.string.open),
                getString(R.string.edit_in_field),
                getString(R.string.rename),
                getString(e.favorite ? R.string.unfavorite : R.string.favorite),
                getString(R.string.delete)
        };
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: open(e.url, false); break;
                        case 1:
                            input.setText(e.url);
                            input.setSelection(input.length());
                            input.requestFocus();
                            break;
                        case 2: rename(e); break;
                        case 3: hosts.setFavorite(e.url, !e.favorite); refreshHosts(); break;
                        case 4: hosts.remove(e.url); refreshHosts(); break;
                        default: break;
                    }
                })
                .show();
    }

    private void rename(HostStore.Entry e) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setText(e.name);
        field.setSelection(field.length());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(field);
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename)
                .setView(wrap)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (!name.isEmpty()) {
                        hosts.rename(e.url, name);
                        refreshHosts();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private final class HostAdapter extends ArrayAdapter<HostStore.Entry> {
        HostAdapter() {
            super(MainActivity.this, R.layout.item_host, new ArrayList<>());
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(getContext()).inflate(R.layout.item_host, parent, false);
            HostStore.Entry e = getItem(position);
            ((TextView) v.findViewById(R.id.host_name)).setText((e.favorite ? "★ " : "") + e.name);
            ((TextView) v.findViewById(R.id.host_url)).setText(e.url);
            return v;
        }
    }

    // ---- menu -------------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_about) {
            showAbout();
        } else if (id == R.id.action_clear_certs) {
            CertTrustStore certs = new CertTrustStore(this);
            confirm(getString(R.string.confirm_clear_certs, certs.count()), () -> {
                certs.clear();
                Toast.makeText(this, R.string.done, Toast.LENGTH_SHORT).show();
            });
        } else if (id == R.id.action_clear_logins) {
            CredentialStore creds = new CredentialStore(this);
            confirm(getString(R.string.confirm_clear_logins, creds.count()), () -> {
                creds.clear();
                Toast.makeText(this, R.string.done, Toast.LENGTH_SHORT).show();
            });
        } else if (id == R.id.action_clear_cookies) {
            confirm(getString(R.string.confirm_clear_cookies), () -> {
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                WebStorage.getInstance().deleteAllData();
                Toast.makeText(this, R.string.done, Toast.LENGTH_SHORT).show();
            });
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    private void confirm(String message, Runnable action) {
        new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton(R.string.delete, (d, w) -> action.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAbout() {
        String msg = getString(R.string.about_text,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                BuildConfig.APPLICATION_ID,
                BuildConfig.BUILD_TYPE);
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
