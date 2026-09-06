package com.rolex86.f1streams;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String CHANNELS_URL = "https://cdn.f1live.dpdns.org/channels.json";
    private static final String ORIGIN = "https://f1live.dpdns.org";
    private static final String REFERER = "https://f1live.dpdns.org/stream";
    private static final int MAX_PAGE_BYTES = 700_000;

    private final List<StreamItem> items = new ArrayList<>();
    private ArrayAdapter<StreamItem> adapter;
    private ListView list;
    private TextView status;

    private static final Pattern MEDIA_URL = Pattern.compile(
            "https?://[^\\\"'<>\\s]+?(?:\\.m3u8|\\.mpd)(?:\\?[^\\\"'<>\\s]*)?",
            Pattern.CASE_INSENSITIVE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadChannels();
    }

    private void buildUi() {
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(28), dp(20), dp(28), dp(20));

        TextView title = new TextView(this);
        title.setText("F1 Streams");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextColor(0xffaaaaaa);
        status.setTextSize(15);
        status.setText("Načítám…");
        status.setPadding(0, 0, 0, dp(12));
        root.addView(status, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new ListView(this);
        list.setDividerHeight(1);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
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
        root.addView(list, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        StreamItem item = items.get(position);
        if (item.refresh) {
            loadChannels();
            return;
        }
        openStream(item);
    }

    private void loadChannels() {
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
                    status.setText(parsed.isEmpty()
                            ? "Nenašel jsem žádné streamy."
                            : "Dostupné streamy: " + parsed.size());
                    if (!items.isEmpty()) list.requestFocus();
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
        walkJson(root, "", out, seen);
        return out;
    }

    private void walkJson(Object node, String path, List<StreamItem> out, Set<String> seen) throws JSONException {
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                walkJson(a.get(i), path, out, seen);
            }
            return;
        }

        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;

        String url = findUrl(o);
        if (url != null && seen.add(url)) {
            String name = findName(o);
            if (name == null || name.trim().isEmpty()) name = humanize(path);
            if (name == null || name.trim().isEmpty()) name = "Stream " + (out.size() + 1);
            out.add(new StreamItem(name, url, false));
        }

        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = o.opt(key);
            String childPath = path.isEmpty() ? key : path + " / " + key;
            if (value instanceof JSONObject || value instanceof JSONArray) {
                walkJson(value, childPath, out, seen);
            } else if (value instanceof String) {
                String s = ((String) value).trim();
                if (isHttp(s) && keyLooksPlayable(key) && seen.add(s)) {
                    out.add(new StreamItem(humanize(childPath), s, false));
                }
            }
        }
    }

    private String findName(JSONObject o) {
        String[] keys = {"name", "title", "label", "channel", "channelName", "channel_name", "displayName", "display_name"};
        for (String key : keys) {
            Object v = o.opt(key);
            if (v instanceof String && !((String) v).trim().isEmpty()) return (String) v;
        }
        return null;
    }

    private String findUrl(JSONObject o) {
        String[] preferred = {"streamUrl", "stream_url", "stream", "playUrl", "play_url", "url", "link", "src", "embedUrl", "embed_url", "embed", "iframe"};
        for (String key : preferred) {
            Object v = o.opt(key);
            if (v instanceof String && isHttp((String) v)) return ((String) v).trim();
        }

        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object v = o.opt(key);
            if (v instanceof String) {
                String s = ((String) v).trim();
                if (isDirectMedia(s)) return s;
            }
        }
        return null;
    }

    private boolean keyLooksPlayable(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("stream") || k.equals("url") || k.contains("link") || k.contains("embed") || k.contains("iframe") || k.equals("src");
    }

    private void openStream(StreamItem item) {
        status.setText("Otevírám: " + item.name);
        if (isDirectMedia(item.url)) {
            launchPlayer(item.url);
            return;
        }

        new Thread(() -> {
            String resolved = null;
            try {
                String page = httpGet(item.url, REFERER, MAX_PAGE_BYTES);
                resolved = findMediaUrlInText(page);
            } catch (Exception ignored) {
            }
            final String finalUrl = resolved != null ? resolved : item.url;
            runOnUiThread(() -> launchPlayer(finalUrl));
        }).start();
    }

    private String findMediaUrlInText(String text) {
        if (text == null) return null;
        String normalized = text
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&");
        Matcher m = MEDIA_URL.matcher(normalized);
        return m.find() ? m.group() : null;
    }

    private void launchPlayer(String url) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(url), "video/*");
        i.addCategory(Intent.CATEGORY_DEFAULT);
        try {
            startActivity(i);
            status.setText("Vyber přehrávač.");
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, "Není nainstalovaný vhodný přehrávač.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private String httpGet(String url, String referer, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(12_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/json,text/plain,text/html,*/*");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 Chrome/145 Safari/537.36");
        c.setRequestProperty("Origin", ORIGIN);
        c.setRequestProperty("Referer", referer);
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
