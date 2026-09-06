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
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private final Channel[] channels = {
            new Channel(
                    "ORF 1",
                    "Rakousko",
                    "https://orf1.mdn.ors.at/out/u/orf1/q8c/manifest.m3u8"),
            new Channel(
                    "ServusTV Österreich",
                    "Rakousko",
                    "https://rbmn-live.akamaized.net/hls/live/2002825/geoSTVATweb/master.m3u8"),
            new Channel(
                    "RTL Zwee",
                    "Lucembursko",
                    "https://live-edge.rtl.lu/channel2/smil:channel2/playlist.m3u8")
    };

    private ArrayAdapter<Channel> adapter;
    private TextView status;
    private int probesFinished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        probeAll();
    }

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
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setText("Free TV zdroje • kontroluji dostupnost…");
        status.setTextColor(0xffaaaaaa);
        status.setTextSize(15);
        status.setPadding(0, 0, 0, dp(12));
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(this);
        list.setDividerHeight(1);
        adapter = new ArrayAdapter<Channel>(
                this,
                android.R.layout.simple_list_item_1,
                channels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(Color.WHITE);
                v.setTextSize(21);
                v.setGravity(Gravity.CENTER_VERTICAL);
                v.setMinHeight(dp(62));
                v.setPadding(dp(18), 0, dp(18), 0);
                v.setBackgroundResource(android.R.drawable.list_selector_background);
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> open(channels[position]));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1));

        setContentView(root);
        list.requestFocus();
    }

    private void probeAll() {
        probesFinished = 0;
        for (Channel channel : channels) {
            channel.state = "ověřuji…";
            new Thread(() -> probe(channel), "f1-probe").start();
        }
        adapter.notifyDataSetChanged();
    }

    private void probe(Channel channel) {
        HttpURLConnection connection = null;
        String result;
        try {
            connection = (HttpURLConnection) new URL(channel.url).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,*/*");

            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                InputStream input = connection.getInputStream();
                byte[] prefix = new byte[256];
                int count = input.read(prefix);
                input.close();
                String text = count > 0
                        ? new String(prefix, 0, count, java.nio.charset.StandardCharsets.UTF_8)
                        : "";
                result = text.contains("#EXTM3U") ? "dostupný" : "HTTP " + code;
            } else {
                result = "HTTP " + code;
            }
        } catch (Exception e) {
            result = "nedostupný";
        } finally {
            if (connection != null) connection.disconnect();
        }

        channel.state = result;
        runOnUiThread(() -> {
            probesFinished++;
            adapter.notifyDataSetChanged();
            if (probesFinished >= channels.length) {
                int available = 0;
                for (Channel item : channels) {
                    if ("dostupný".equals(item.state)) available++;
                }
                status.setText("Přímé HLS • dostupné " + available + "/" + channels.length
                        + " • některé zdroje mohou být geoblokované");
            }
        });
    }

    private void open(Channel channel) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(channel.url), "video/*");
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Není nainstalovaný vhodný přehrávač.", Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Channel {
        final String name;
        final String country;
        final String url;
        volatile String state = "ověřuji…";

        Channel(String name, String country, String url) {
            this.name = name;
            this.country = country;
            this.url = url;
        }

        @Override
        public String toString() {
            return name + " • " + country + " • " + state;
        }
    }
}
