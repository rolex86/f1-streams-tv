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
import android.webkit.WebResourceRequest;
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
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String CHANNELS_URL = "https://cdn.f1live.dpdns.org/channels.json";
    private static final String ORIGIN = "https://f1live.dpdns.org";
    private static final String REFERER = "https://f1live.dpdns.org/stream";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/145.0.0.0 Mobile Safari/537.36";

    private static final Pattern HTTP_URL = Pattern.compile(
            "(?:https?:)?//[^\\\"'<>\\s]+", Pattern.CASE_INSENSITIVE);

    private final List<StreamItem> items = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ArrayAdapter<StreamItem> adapter;
    private ListView list;
    private TextView status;
    private WebView resolver;
    private int resolverToken;
    private String resolverReferer = REFERER;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadChannels();
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
        status.setText("Načítám…");
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

        resolver = new WebView(this);
        resolver.setAlpha(0f);
        resolver.setFocusable(false);
        resolver.setFocusableInTouchMode(false);
        WebSettings ws = resolver.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setUserAgentString(USER_AGENT);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(resolver, true);
        resolver.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (isDirectMedia(url)) {
                    mediaFound(url);
                    return true;
                }
                return false;
            }

            @Override
            public void onLoadResource(WebView view, String url) {
                if (isDirectMedia(url)) mediaFound(url);
            }
        });
        LinearLayout.LayoutParams hidden = new LinearLayout.LayoutParams(1, 1);
        root.addView(resolver, hidden);

        setContentView(root);
    }

    private void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        StreamItem item = items.get(position);
        if (item.refresh) {
            loadChannels();
        } else {
            openStream(item);
        }
    }

    private void loadChannels() {
        resolverToken++;
        status.setText("Načítám dostupné streamy…");
        items.clear();
        adapter.notifyDataSetChanged();

        new Thread(() -> {
            try {
                String json = httpGet(CHANNELS_URL, REFERER, 1_500_000);
                List<StreamItem> parsed = parseChannels(json);
                runOnUiThread(() -> {
                    items.clear();
                    items.addAll(parsed);
                    items.add(StreamItem.refresh());
                    adapter.notifyDataSetChanged();
                    status.setText(parsed.isEmpty() ? "Nenašel jsem žádné streamy." : "Dostupné streamy: " + parsed.size());
                    list.requestFocus();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    items.clear();
                    items.add(StreamItem.refresh());
                    adapter.notifyDataSetChanged();
                    status.setText("Načtení selhalo: " + shortMessage(e));
                    list.requestFocus();
                });
            }
        }).start();
    }

    private List<StreamItem> parseChannels(String json) throws JSONException {
        Object root = json.trim().startsWith("[") ? new JSONArray(json) : new JSONObject(json);
        List<StreamItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        walkJson(root, "", null, out, seen);
        return out;
    }

    private void walkJson(Object node, String path, String inheritedName,
                          List<StreamItem> out, Set<String> seen) throws JSONException {
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                walkJson(a.get(i), path, inheritedName, out, seen);
            }
            return;
        }

        if (node instanceof String) {
            addUrls((String) node, path, inheritedName, "", out, seen);
            return;
        }

        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;
        String ownName = findName(o);
        String effectiveName = ownName != null ? ownName : inheritedName;

        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = o.opt(key);
            String childPath = path.isEmpty() ? key : path + " / " + key;
            if (value instanceof JSONObject || value instanceof JSONArray) {
                walkJson(value, childPath, effectiveName, out, seen);
            } else if (value instanceof String && !isNameKey(key)) {
                addUrls((String) value, path, effectiveName, key, out, seen);
            }
        }
    }

    private void addUrls(String raw, String path, String name, String key,
                         List<StreamItem> out, Set<String> seen) {
        if (raw == null) return;
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (k.contains("logo") || k.contains("icon") || k.contains("image") ||
                k.contains("poster") || k.contains("thumb") || k.contains("css") ||
                k.contains("script")) return;

        String normalized = raw.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&");
        boolean hinted = k.isEmpty() || k.contains("url") || k.contains("link") ||
                k.contains("src") || k.contains("embed") || k.contains("iframe") ||
                k.contains("stream") || k.contains("player") || normalized.toLowerCase(Locale.ROOT).contains("<iframe");

        Matcher m = HTTP_URL.matcher(normalized);
        while (m.find()) {
            String url = normalizeUrl(m.group());
            if (!hinted && !isDirectMedia(url)) continue;
            if (!isHttp(url)) continue;
            if (!seen.add(url)) continue;

            String displayName = name;
            if (displayName == null || displayName.trim().isEmpty()) displayName = humanize(path);
            if (displayName == null || displayName.trim().isEmpty()) displayName = "Stream " + (out.size() + 1);
            String group = groupFromPath(path);
            if (group != null && !group.isEmpty() && !displayName.toLowerCase(Locale.ROOT).contains(group.toLowerCase(Locale.ROOT))) {
                displayName = group + " • " + displayName;
            }
            out.add(new StreamItem(displayName, url, false));
        }
    }

    private String findName(JSONObject o) {
        String[] keys = {"name", "title", "label", "channel", "channelName", "channel_name", "displayName", "display_name"};
        for (String key : keys) {
            Object v = o.opt(key);
            if (v instanceof String && !((String) v).trim().isEmpty()) return ((String) v).trim();
        }
        return null;
    }

    private boolean isNameKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.equals("name") || k.equals("title") || k.equals("label") || k.equals("channel") || k.contains("displayname") || k.contains("display_name") || k.contains("channelname") || k.contains("channel_name");
    }

    private void openStream(StreamItem item) {
        if (isDirectMedia(item.url)) {
            launchPlayer(item.url, REFERER);
            return;
        }

        status.setText("Hledám stream: " + item.name);
        int token = ++resolverToken;
        resolverReferer = item.url;
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", REFERER);
        headers.put("Origin", ORIGIN);
        resolver.stopLoading();
        resolver.loadUrl(item.url, headers);

        handler.postDelayed(() -> {
            if (token == resolverToken) {
                resolver.stopLoading();
                status.setText("Stream se nepodařilo získat. Zkus jiný zdroj.");
            }
        }, 15_000);
    }

    private void mediaFound(String url) {
        if (!isDirectMedia(url)) return;
        int token = resolverToken;
        runOnUiThread(() -> {
            if (token != resolverToken) return;
            resolverToken++;
            resolver.stopLoading();
            launchPlayer(url, resolverReferer);
        });
    }

    private void launchPlayer(String url, String referer) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(url), "video/*");
        i.addCategory(Intent.CATEGORY_DEFAULT);
        i.putExtra(Intent.EXTRA_REFERRER, Uri.parse(referer == null ? REFERER : referer));
        Bundle headers = new Bundle();
        headers.putString("Referer", referer == null ? REFERER : referer);
        headers.putString("Origin", ORIGIN);
        headers.putString("User-Agent", USER_AGENT);
        i.putExtra("headers", headers);
        i.putExtra("http_headers", headers);
        try {
            startActivity(i);
            status.setText("Vyber přehrávač.");
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Není nainstalovaný vhodný přehrávač.", Toast.LENGTH_LONG).show();
        }
    }

    private String httpGet(String url, String referer, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(12_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/json,text/plain,text/html,*/*");
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Origin", ORIGIN);
        c.setRequestProperty("Referer", referer);
        c.setRequestProperty("Sec-Fetch-Site", "same-site");
        c.setRequestProperty("Sec-Fetch-Mode", "cors");
        c.setRequestProperty("Sec-Fetch-Dest", "empty");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        try (InputStream in = c.getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder();
            char[] buf = new char[4096];
            int total = 0;
            int n;
            while ((n = r.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) break;
                b.append(buf, 0, n);
            }
            return b.toString();
        } finally {
            c.disconnect();
        }
    }

    private static String normalizeUrl(String url) {
        String u = url.trim();
        if (u.startsWith("//")) u = "https:" + u;
        while (u.endsWith(")") || u.endsWith(",") || u.endsWith(";")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static boolean isHttp(String s) {
        if (s == null) return false;
        String l = s.trim().toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://");
    }

    private static boolean isDirectMedia(String s) {
        if (!isHttp(s)) return false;
        String l = s.toLowerCase(Locale.ROOT);
        return l.contains(".m3u8") || l.contains(".mpd");
    }

    private static String groupFromPath(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        String[] parts = path.split(" / ");
        for (String part : parts) {
            String p = humanize(part);
            String l = p.toLowerCase(Locale.ROOT);
            if (l.contains("server 1") || l.contains("server1")) return "Server 1";
            if (l.contains("server 2") || l.contains("server2")) return "Server 2";
            if (l.contains("sport")) return "Other Sports";
        }
        return null;
    }

    private static String humanize(String s) {
        if (s == null) return null;
        String out = s.replace('_', ' ').replace('-', ' ').trim();
        if (out.isEmpty()) return out;
        return Character.toUpperCase(out.charAt(0)) + out.substring(1);
    }

    private static String shortMessage(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        resolverToken++;
        if (resolver != null) {
            resolver.stopLoading();
            resolver.destroy();
        }
        super.onDestroy();
    }

    private static final class StreamItem {
        final String name;
        final String url;
        final boolean refresh;

        StreamItem(String name, String url, boolean refresh) {
            this.name = name;
            this.url = url;
            this.refresh = refresh;
        }

        static StreamItem refresh() {
            return new StreamItem("↻ Obnovit", "", true);
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
