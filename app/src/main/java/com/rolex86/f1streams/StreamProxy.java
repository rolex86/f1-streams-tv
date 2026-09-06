package com.rolex86.f1streams;

import android.util.Base64;
import android.webkit.CookieManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StreamProxy {
    private static final Pattern URI_ATTR = Pattern.compile("URI=\\\"([^\\\"]+)\\\"");

    private volatile boolean running;
    private ServerSocket server;
    private Thread acceptThread;
    private int port;

    private String referer;
    private String userAgent;
    private Map<String, String> sourceHeaders = Collections.emptyMap();

    private String initialSourceUrl;
    private String initialBaseUrl;
    private String initialPlaylist;
    private boolean initialPlaylistServed;

    private volatile int requestCount;
    private volatile int lastStatus;
    private volatile String lastPath = "-";
    private volatile String lastError = "";
    private volatile String validationError = "";

    synchronized String startValidated(String sourceUrl, String referer,
                                       Map<String, String> requestHeaders,
                                       String userAgent) throws Exception {
        stop();
        this.referer = referer;
        this.userAgent = userAgent;
        this.sourceHeaders = requestHeaders == null
                ? Collections.emptyMap()
                : new HashMap<>(requestHeaders);
        requestCount = 0;
        lastStatus = 0;
        lastPath = "-";
        lastError = "";
        validationError = "";
        initialSourceUrl = null;
        initialBaseUrl = null;
        initialPlaylist = null;
        initialPlaylistServed = false;

        Probe probe = probePlaylist(sourceUrl);
        if (!probe.valid) {
            validationError = probe.error;
            throw new IllegalArgumentException(probe.error);
        }

        initialSourceUrl = sourceUrl;
        initialBaseUrl = probe.finalUrl;
        initialPlaylist = probe.playlist;

        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        port = server.getLocalPort();
        running = true;

        acceptThread = new Thread(this::acceptLoop, "f1-stream-proxy");
        acceptThread.setDaemon(true);
        acceptThread.start();

        // Force an HLS-looking root path so external players can infer the media type.
        return localUrl(sourceUrl, true);
    }

    synchronized void stop() {
        running = false;
        if (server != null) {
            try { server.close(); } catch (Exception ignored) {}
            server = null;
        }
        acceptThread = null;
        initialSourceUrl = null;
        initialBaseUrl = null;
        initialPlaylist = null;
        initialPlaylistServed = false;
    }

    String diagnostic() {
        if (requestCount == 0) {
            if (validationError != null && !validationError.isEmpty()) {
                return "Proxy: " + validationError;
            }
            return "Proxy: přehrávač se k proxy nepřipojil";
        }
        String base = "Proxy: " + requestCount + " požadavků, poslední HTTP "
                + lastStatus + " " + lastPath;
        if (lastError != null && !lastError.isEmpty()) base += " | " + lastError;
        return base;
    }

    String validationError() {
        return validationError == null ? "" : validationError;
    }

    private Probe probePlaylist(String sourceUrl) {
        HttpURLConnection c = null;
        try {
            c = openUpstream(sourceUrl, null, false);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                return Probe.fail("HLS HTTP " + code);
            }

            byte[] raw = readAllLimited(c.getInputStream(), 1024 * 1024);
            String text = new String(raw, StandardCharsets.UTF_8).trim();
            if (!text.startsWith("#EXTM3U")) {
                return Probe.fail("není HLS playlist");
            }

            boolean master = text.contains("#EXT-X-STREAM-INF") || text.contains("#EXT-X-MEDIA");
            boolean media = text.contains("#EXTINF") || text.contains("#EXT-X-TARGETDURATION");
            if (!master && !media) {
                return Probe.fail("HLS bez streamů");
            }

            // A finite media playlist is usually an advert/clip rather than the live channel.
            if (!master && text.contains("#EXT-X-ENDLIST")) {
                return Probe.fail("krátký/VOD HLS, čekám na live stream");
            }

            return Probe.ok(text, c.getURL().toString());
        } catch (Exception e) {
            return Probe.fail(e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                Thread t = new Thread(() -> handle(socket), "f1-proxy-client");
                t.setDaemon(true);
                t.start();
            } catch (Exception ignored) {
            }
        }
    }

    private void handle(Socket socket) {
        HttpURLConnection upstream = null;
        try (Socket s = socket) {
            s.setSoTimeout(20_000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendSimple(s.getOutputStream(), 400, "Bad Request", "text/plain", new byte[0]);
                return;
            }

            String method = parts[0];
            boolean headOnly = "HEAD".equals(method);
            if (!"GET".equals(method) && !headOnly) {
                sendSimple(s.getOutputStream(), 405, "Method Not Allowed", "text/plain", new byte[0]);
                return;
            }

            Map<String, String> clientHeaders = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    clientHeaders.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                }
            }

            String target = decodeTarget(parts[1]);
            if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) {
                sendSimple(s.getOutputStream(), 400, "Bad Request", "text/plain", new byte[0]);
                return;
            }

            requestCount++;
            lastPath = shortPath(target);
            lastError = "";

            // Serve the already validated first playlist once. This avoids consuming a
            // short-lived/single-use URL during validation and then requesting it again.
            if (!headOnly && target.equals(initialSourceUrl) && !initialPlaylistServed
                    && initialPlaylist != null) {
                initialPlaylistServed = true;
                lastStatus = 200;
                String rewritten = rewritePlaylist(initialPlaylist,
                        initialBaseUrl == null ? target : initialBaseUrl);
                byte[] out = rewritten.getBytes(StandardCharsets.UTF_8);
                sendSimple(s.getOutputStream(), 200, "OK",
                        "application/vnd.apple.mpegurl", out);
                return;
            }

            if (headOnly && target.equals(initialSourceUrl) && initialPlaylist != null) {
                lastStatus = 200;
                OutputStream out = s.getOutputStream();
                writeStatus(out, 200, "OK");
                writeHeader(out, "Content-Type", "application/vnd.apple.mpegurl");
                writeHeader(out, "Connection", "close");
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                return;
            }

            upstream = openUpstream(target, clientHeaders.get("range"), headOnly);
            int code = upstream.getResponseCode();
            lastStatus = code;
            String type = upstream.getContentType();
            if (type == null) type = guessType(target);

            if (headOnly) {
                OutputStream out = s.getOutputStream();
                writeStatus(out, code, reason(code));
                writeHeader(out, "Content-Type", type);
                copyRangeHeaders(upstream, out);
                writeHeader(out, "Connection", "close");
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                return;
            }

            InputStream body = code >= 200 && code < 400
                    ? upstream.getInputStream()
                    : upstream.getErrorStream();

            if (isHls(target, type)) {
                byte[] raw = readAllLimited(body, 2 * 1024 * 1024);
                String text = new String(raw, StandardCharsets.UTF_8);
                String rewritten = rewritePlaylist(text, upstream.getURL().toString());
                byte[] out = rewritten.getBytes(StandardCharsets.UTF_8);
                sendSimple(s.getOutputStream(), code, reason(code),
                        "application/vnd.apple.mpegurl", out);
            } else {
                OutputStream out = s.getOutputStream();
                writeStatus(out, code, reason(code));
                writeHeader(out, "Content-Type", type);
                copyRangeHeaders(upstream, out);
                writeHeader(out, "Connection", "close");
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));

                if (body != null) {
                    byte[] buffer = new byte[32 * 1024];
                    int n;
                    while ((n = body.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                out.flush();
            }
        } catch (Exception e) {
            lastStatus = 502;
            lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            try {
                sendSimple(socket.getOutputStream(), 502, "Bad Gateway", "text/plain",
                        lastError.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        } finally {
            if (upstream != null) upstream.disconnect();
        }
    }

    private HttpURLConnection openUpstream(String target, String range, boolean headOnly)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setConnectTimeout(12_000);
        c.setReadTimeout(20_000);
        c.setInstanceFollowRedirects(true);
        if (headOnly) c.setRequestMethod("HEAD");

        for (Map.Entry<String, String> e : sourceHeaders.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key == null || value == null || blockedHeader(key)) continue;
            try { c.setRequestProperty(key, value); } catch (Exception ignored) {}
        }

        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("User-Agent", valueOr(sourceHeaders, "User-Agent", userAgent));
        c.setRequestProperty("Referer", valueOr(sourceHeaders, "Referer", referer));

        String cookies = CookieManager.getInstance().getCookie(target);
        if (cookies != null && !cookies.isEmpty()) c.setRequestProperty("Cookie", cookies);
        if (range != null && !range.isEmpty()) c.setRequestProperty("Range", range);
        return c;
    }

    private void copyRangeHeaders(HttpURLConnection upstream, OutputStream out) throws Exception {
        String contentRange = upstream.getHeaderField("Content-Range");
        if (contentRange != null) writeHeader(out, "Content-Range", contentRange);
        String acceptRanges = upstream.getHeaderField("Accept-Ranges");
        if (acceptRanges != null) writeHeader(out, "Accept-Ranges", acceptRanges);
        long length = upstream.getContentLengthLong();
        if (length >= 0) writeHeader(out, "Content-Length", Long.toString(length));
    }

    private boolean blockedHeader(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.equals("host") || k.equals("connection") || k.equals("cookie") ||
                k.equals("content-length") || k.equals("accept-encoding") || k.equals("range");
    }

    private String rewritePlaylist(String playlist, String sourceUrl) throws Exception {
        URL base = new URL(sourceUrl);
        String[] lines = playlist.replace("\r\n", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder(playlist.length() + 1024);

        for (String line : lines) {
            if (line.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(line);
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    String absolute = resolve(base, m.group(1));
                    String replacement = absolute == null ? m.group(1) : localUrl(absolute, false);
                    m.appendReplacement(sb, Matcher.quoteReplacement(
                            "URI=\"" + replacement + "\""));
                }
                m.appendTail(sb);
                out.append(sb);
            } else if (!line.trim().isEmpty()) {
                String absolute = resolve(base, line.trim());
                out.append(absolute == null ? line : localUrl(absolute, false));
            } else {
                out.append(line);
            }
            out.append('\n');
        }
        return out.toString();
    }

    private String resolve(URL base, String value) {
        try {
            String v = value.trim();
            if (v.startsWith("data:") || v.startsWith("skd:")) return null;
            return new URL(base, v).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String localUrl(String target, boolean forceHls) {
        String encoded = Base64.encodeToString(target.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        String path = forceHls ? "/stream.m3u8" : pathHint(target);
        return "http://127.0.0.1:" + port + path + "?u=" + encoded;
    }

    private String decodeTarget(String requestTarget) {
        try {
            int q = requestTarget.indexOf('?');
            if (q < 0) return null;
            String query = requestTarget.substring(q + 1);
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "u".equals(pair.substring(0, eq))) {
                    String encoded = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    byte[] bytes = Base64.decode(encoded,
                            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String pathHint(String target) {
        try {
            String path = new URL(target).getPath();
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            if (name.matches("[A-Za-z0-9._-]{1,80}")) return "/" + name;
        } catch (Exception ignored) {}
        return "/stream.bin";
    }

    private String shortPath(String target) {
        try {
            URL u = new URL(target);
            String p = u.getPath();
            if (p.length() > 55) p = "…" + p.substring(p.length() - 54);
            return u.getHost() + p;
        } catch (Exception e) {
            return target.length() > 65 ? target.substring(0, 65) : target;
        }
    }

    private boolean isHls(String target, String type) {
        String t = target.toLowerCase(Locale.ROOT);
        String ct = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return t.contains(".m3u8") || ct.contains("mpegurl") || ct.contains("m3u");
    }

    private String guessType(String target) {
        String t = target.toLowerCase(Locale.ROOT);
        if (t.contains(".m3u8")) return "application/vnd.apple.mpegurl";
        if (t.contains(".ts")) return "video/mp2t";
        if (t.contains(".m4s")) return "video/iso.segment";
        if (t.contains(".mp4")) return "video/mp4";
        if (t.contains(".aac")) return "audio/aac";
        if (t.contains(".key")) return "application/octet-stream";
        return "application/octet-stream";
    }

    private byte[] readAllLimited(InputStream in, int maxBytes) throws Exception {
        if (in == null) return new byte[0];
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int n;
            while ((n = input.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) throw new IllegalArgumentException("playlist je příliš velký");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private void sendSimple(OutputStream out, int code, String reason,
                            String type, byte[] body) throws Exception {
        writeStatus(out, code, reason);
        writeHeader(out, "Content-Type", type);
        writeHeader(out, "Content-Length", Integer.toString(body.length));
        writeHeader(out, "Cache-Control", "no-store");
        writeHeader(out, "Access-Control-Allow-Origin", "*");
        writeHeader(out, "Connection", "close");
        out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private void writeStatus(OutputStream out, int code, String reason) throws Exception {
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
    }

    private void writeHeader(OutputStream out, String name, String value) throws Exception {
        out.write((name + ": " + value + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    private String valueOr(Map<String, String> map, String name, String fallback) {
        String value = header(map, name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private String header(Map<String, String> map, String name) {
        if (map == null) return null;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            default: return code >= 200 && code < 300 ? "OK" : "Upstream";
        }
    }

    private static final class Probe {
        final boolean valid;
        final String playlist;
        final String finalUrl;
        final String error;

        private Probe(boolean valid, String playlist, String finalUrl, String error) {
            this.valid = valid;
            this.playlist = playlist;
            this.finalUrl = finalUrl;
            this.error = error;
        }

        static Probe ok(String playlist, String finalUrl) {
            return new Probe(true, playlist, finalUrl, "");
        }

        static Probe fail(String error) {
            return new Probe(false, null, null, error);
        }
    }
}
