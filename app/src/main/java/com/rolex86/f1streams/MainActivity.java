package com.rolex86.f1streams;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String PAGE = "https://f1live.dpdns.org/stream";
    private static final String CHANNELS = "https://cdn.f1live.dpdns.org/channels.json";
    private static final String ORIGIN = "https://f1live.dpdns.org";
    private static final String[] GROUPS = {"Server 1", "Server 2", "Other Sports"};

    // Header set observed from a real Chrome request accepted by the CDN.
    private static final String CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<StreamItem> items = new ArrayList<>();
    private final List<StreamItem> collected = new ArrayList<>();
    private final Set<String> collectedKeys = new HashSet<>();

    private ArrayAdapter<StreamItem> adapter;
    private ListView list;
    private TextView status;
    private WebView web;

    private boolean collecting;
    private int collectionToken;
    private int providerIndex;
    private StreamItem pendingPlayback;
    private volatile boolean capturingMedia;
    private int playbackToken;
    private volatile String channelProxyInfo = "kanály: požadavek zatím nepřišel";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refresh();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(28), dp(20), dp(28), dp(20));

        TextView title = new TextView(this);
        title.setText("F1 Streams");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextColor(0xffaaaaaa);
        status.setTextSize(15);
        status.setPadding(0, 0, 0, dp(12));
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new ListView(this);
        list.setDividerHeight(1);
        adapter = new ArrayAdapter<StreamItem>(this, android.R.layout.simple_list_item_1, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(Color.WHITE);
                v.setTextSize(21);
                v.setGravity(Gravity.CENTER_VERTICAL);
                v.setMinHeight(dp(58));
                v.setPadding(dp(18), 0, dp(18), 0);
                v.setBackgroundResource(android.R.drawable.list_selector_background);
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(this::onItemClick);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        web = new WebView(this);
        web.setAlpha(0f);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setUserAgentString(CHROME_UA);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                return false;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                if (url.startsWith(CHANNELS)) {
                    return proxyChannels();
                }

                if (capturingMedia && isMedia(url)) {
                    mediaFound(url, request.getRequestHeaders());
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (capturingMedia && isMedia(url)) {
                    mediaFound(url, request.getRequestHeaders());
                    return true;
                }
                return false;
            }

            @Override
            public void onLoadResource(WebView view, String url) {
                if (capturingMedia && isMedia(url)) mediaFound(url, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (pendingPlayback != null && url.startsWith(ORIGIN + "/")) {
                    int token = playbackToken;
                    handler.postDelayed(() -> {
                        if (token == playbackToken && pendingPlayback != null) selectForPlayback(pendingPlayback);
                    }, 1200);
                } else if (collecting && url.startsWith(ORIGIN + "/")) {
                    int token = collectionToken;
                    handler.postDelayed(() -> collectProvider(token, 0), 1800);
                }
            }
        });

        // Keep a real viewport so the React page is not throttled as a tiny hidden document.
        LinearLayout.LayoutParams hidden = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360));
        hidden.height = dp(360);
        root.addView(web, hidden);

        setContentView(root);
    }

    private WebResourceResponse proxyChannels() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(CHANNELS).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(12_000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", CHROME_UA);
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Origin", ORIGIN);
            c.setRequestProperty("Referer", PAGE);
            c.setRequestProperty("Sec-Fetch-Mode", "cors");
            c.setRequestProperty("Sec-Fetch-Site", "same-site");
            c.setRequestProperty("Sec-Fetch-Dest", "empty");
            c.setRequestProperty("Sec-CH-UA", "\"Not:A-Brand\";v=\"99\", \"Google Chrome\";v=\"145\", \"Chromium\";v=\"145\"");
            c.setRequestProperty("Sec-CH-UA-Mobile", "?0");
            c.setRequestProperty("Sec-CH-UA-Platform", "\"Windows\"");

            String cookies = CookieManager.getInstance().getCookie(CHANNELS);
            if (cookies != null && !cookies.isEmpty()) c.setRequestProperty("Cookie", cookies);

            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
            byte[] body = readAll(in);
            channelProxyInfo = "kanály: HTTP " + code + ", " + body.length + " B";

            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", ORIGIN);
            headers.put("Access-Control-Expose-Headers", "ETag");
            headers.put("Cache-Control", "no-store");
            headers.put("Content-Type", "application/json");

            String reason = code >= 200 && code < 300 ? "OK" : "HTTP error";
            return new WebResourceResponse(
                    "application/json",
                    "UTF-8",
                    code,
                    reason,
                    headers,
                    new ByteArrayInputStream(body));
        } catch (Exception e) {
            channelProxyInfo = "kanály: chyba " + e.getClass().getSimpleName() +
                    (e.getMessage() == null ? "" : " (" + e.getMessage() + ")");
            byte[] body = "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", ORIGIN);
            headers.put("Content-Type", "application/json");
            return new WebResourceResponse(
                    "application/json", "UTF-8", 200, "OK", headers,
                    new ByteArrayInputStream(body));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        StreamItem item = items.get(position);
        if (item.refresh) refresh(); else openStream(item);
    }

    private void refresh() {
        pendingPlayback = null;
        capturingMedia = false;
        collecting = true;
        collectionToken++;
        providerIndex = 0;
        collected.clear();
        collectedKeys.clear();
        items.clear();
        adapter.notifyDataSetChanged();
        channelProxyInfo = "kanály: požadavek zatím nepřišel";
        status.setText("Načítám streamy z webu…");
        web.stopLoading();
        web.clearCache(true);
        web.loadUrl(PAGE);
    }

    private void collectProvider(int token, int retry) {
        if (!collecting || token != collectionToken || providerIndex >= GROUPS.length) return;
        final String group = GROUPS[providerIndex];
        clickText(group, clicked -> handler.postDelayed(() -> scrapeNames(group, token, retry), clicked ? 800 : 300));
    }

    private void scrapeNames(String group, int token, int retry) {
        if (!collecting || token != collectionToken) return;
        String js = "(function(){" +
                "var a=[],all=[].slice.call(document.querySelectorAll('body *'));" +
                "all.forEach(function(e){var t=(e.innerText||'').trim();if(!t||t.length>240||!/(^|\\n)online($|\\n)/i.test(t))return;" +
                "var l=t.split(/\\n+/).map(function(x){return x.trim();}).filter(Boolean).filter(function(x){return !/^online$/i.test(x)&&!/may contain ads/i.test(x);});" +
                "if(l.length&&l[0].length<120)a.push({n:l[0],z:t.length});});" +
                "a.sort(function(x,y){return x.z-y.z;});var o=[],s={};a.forEach(function(x){if(!s[x.n]){s[x.n]=1;o.push(x.n);}});return JSON.stringify(o);})()";
        web.evaluateJavascript(js, value -> {
            List<String> names = decodeStringArray(value);
            if (names.isEmpty() && retry < 5) {
                status.setText("Čekám na " + group + "… (" + channelProxyInfo + ")");
                handler.postDelayed(() -> collectProvider(token, retry + 1), 1000);
                return;
            }
            for (String name : names) {
                String key = group + "\n" + name;
                if (collectedKeys.add(key)) collected.add(new StreamItem(group, name, false));
            }
            providerIndex++;
            if (providerIndex < GROUPS.length) {
                handler.postDelayed(() -> collectProvider(token, 0), 400);
            } else {
                finishCollection(token);
            }
        });
    }

    private void finishCollection(int token) {
        if (token != collectionToken) return;
        collecting = false;
        items.clear();
        items.addAll(collected);
        items.add(StreamItem.refresh());
        adapter.notifyDataSetChanged();
        status.setText(collected.isEmpty()
                ? "Žádné streamy. " + channelProxyInfo
                : "Dostupné streamy: " + collected.size() + " (" + channelProxyInfo + ")");
        list.requestFocus();
    }

    private void clickText(String text, BoolCallback callback) {
        String q = JSONObject.quote(text);
        String js = "(function(){var q=" + q + ",a=[].slice.call(document.querySelectorAll('body *')).filter(function(e){return (e.innerText||'').trim()===q;});" +
                "a.sort(function(x,y){return (x.innerText||'').length-(y.innerText||'').length;});if(!a.length)return false;" +
                "var e=a[0];while(e&&e.tagName!='BUTTON')e=e.parentElement;(e||a[0]).click();return true;})()";
        web.evaluateJavascript(js, value -> callback.done("true".equalsIgnoreCase(value)));
    }

    private void openStream(StreamItem item) {
        collecting = false;
        collectionToken++;
        pendingPlayback = item;
        capturingMedia = false;
        playbackToken++;
        status.setText("Připravuji: " + item.name);
        web.stopLoading();
        web.loadUrl(PAGE);
    }

    private void selectForPlayback(StreamItem item) {
        int token = playbackToken;
        clickText(item.group, ok -> handler.postDelayed(() -> {
            if (token != playbackToken || pendingPlayback == null) return;
            clickChannel(item.name, clicked -> {
                if (token != playbackToken || pendingPlayback == null) return;
                if (!clicked) {
                    status.setText("Kanál se na webu nepodařilo vybrat.");
                    pendingPlayback = null;
                    return;
                }
                capturingMedia = true;
                status.setText("Hledám skutečný stream: " + item.name);
                handler.postDelayed(() -> {
                    if (token == playbackToken && capturingMedia) {
                        capturingMedia = false;
                        pendingPlayback = null;
                        status.setText("Stream se nepodařilo zachytit. Zkus jiný zdroj.");
                    }
                }, 18_000);
            });
        }, ok ? 800 : 300));
    }

    private void clickChannel(String name, BoolCallback callback) {
        String q = JSONObject.quote(name);
        String js = "(function(){var q=" + q + ",a=[].slice.call(document.querySelectorAll('body *')).filter(function(e){var t=(e.innerText||'').trim();if(!t||t.length>240)return false;" +
                "var l=t.split(/\\n+/).map(function(x){return x.trim();}).filter(Boolean);return l.length&&l[0]===q;});" +
                "a.sort(function(x,y){return (x.innerText||'').length-(y.innerText||'').length;});if(!a.length)return false;" +
                "var e=a[0];while(e&&e.tagName!='BUTTON')e=e.parentElement;(e||a[0]).click();return true;})()";
        web.evaluateJavascript(js, value -> callback.done("true".equalsIgnoreCase(value)));
    }

    private void mediaFound(String url, Map<String, String> requestHeaders) {
        synchronized (this) {
            if (!capturingMedia) return;
            capturingMedia = false;
        }
        runOnUiThread(() -> {
            StreamItem item = pendingPlayback;
            pendingPlayback = null;
            playbackToken++;
            String referer = requestHeaders == null ? null : requestHeaders.get("Referer");
            if (referer == null || referer.isEmpty()) referer = PAGE;
            launchPlayer(url, referer, requestHeaders, item == null ? "F1 Stream" : item.name);
        });
    }

    private void launchPlayer(String url, String referer, Map<String, String> requestHeaders, String title) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(url), "video/*");
        i.addCategory(Intent.CATEGORY_DEFAULT);
        i.putExtra(Intent.EXTRA_REFERRER, Uri.parse(referer));
        i.putExtra("title", title);
        i.putExtra("referer", referer);
        i.putExtra("referrer", referer);

        Bundle headers = new Bundle();
        headers.putString("Referer", referer);
        headers.putString("User-Agent", CHROME_UA);
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null && !cookies.isEmpty()) headers.putString("Cookie", cookies);
        if (requestHeaders != null) {
            for (Map.Entry<String, String> e : requestHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) headers.putString(e.getKey(), e.getValue());
            }
        }
        i.putExtra("headers", headers);
        i.putExtra("http_headers", headers);

        try {
            startActivity(i);
            status.setText("Předáno přehrávači.");
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Není nainstalovaný vhodný přehrávač.", Toast.LENGTH_LONG).show();
        }
    }

    private List<String> decodeStringArray(String value) {
        List<String> out = new ArrayList<>();
        try {
            if (value == null || "null".equals(value)) return out;
            String json = value;
            if (value.startsWith("\"")) json = new JSONArray("[" + value + "]").getString(0);
            JSONArray a = new JSONArray(json);
            for (int x = 0; x < a.length(); x++) {
                String s = a.optString(x, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static boolean isMedia(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.ROOT);
        return u.startsWith("http") && (u.contains(".m3u8") || u.contains(".mpd"));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        collectionToken++;
        playbackToken++;
        capturingMedia = false;
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }

    private interface BoolCallback { void done(boolean value); }

    private static final class StreamItem {
        final String group;
        final String name;
        final boolean refresh;

        StreamItem(String group, String name, boolean refresh) {
            this.group = group;
            this.name = name;
            this.refresh = refresh;
        }

        static StreamItem refresh() {
            return new StreamItem("", "↻ Obnovit", true);
        }

        @Override
        public String toString() {
            return refresh ? name : group + " • " + name;
        }
    }
}
