package com.luxomega.iceberg;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class IcebergBridgeServer extends Thread {
    private final int port;
    private volatile boolean running = true;
    private ServerSocket serverSocket;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private static final String TOR_HOST = "127.0.0.1";
    private static final int TOR_PORT = 9050;
    private static final String I2P_HOST = "127.0.0.1";
    private static final int I2P_PORT = 4444;
    private static final String DEFAULT_SEARXNG = "http://127.0.0.1:8888";

    public IcebergBridgeServer(int port) {
        this.port = port;
        setDaemon(true);
    }

    public void stopServer() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        pool.shutdownNow();
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
            while (running) {
                Socket client = serverSocket.accept();
                pool.submit(() -> handleClient(client));
            }
        } catch (IOException ignored) {}
    }

    private void handleClient(Socket client) {
        try (Socket s = client;
             InputStream in = new BufferedInputStream(s.getInputStream());
             OutputStream out = s.getOutputStream()) {

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String fullPath = parts.length > 1 ? parts[1] : "/";

            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            String body = "";
            if (contentLength > 0) {
                byte[] buf = new byte[contentLength];
                int readTotal = 0;
                while (readTotal < contentLength) {
                    int r = in.read(buf, readTotal, contentLength - readTotal);
                    if (r == -1) break;
                    readTotal += r;
                }
                body = new String(buf, 0, readTotal, StandardCharsets.UTF_8);
            }

            if ("OPTIONS".equalsIgnoreCase(method)) {
                sendResponse(out, 204, "");
                return;
            }

            String path = fullPath.contains("?") ? fullPath.substring(0, fullPath.indexOf("?")) : fullPath;
            String queryStr = fullPath.contains("?") ? fullPath.substring(fullPath.indexOf("?") + 1) : "";

            if ("GET".equalsIgnoreCase(method) && (path.equals("/") || path.equals("/health") || path.equals("/diagnose"))) {
                String searxUrl = getQueryParam(queryStr, "searxng", DEFAULT_SEARXNG);
                JSONObject resp = new JSONObject();
                resp.put("ok", true);
                resp.put("service", "LUX-Ω Android Native Iceberg Bridge v5.4");

                JSONObject bridge = new JSONObject();
                bridge.put("status", "ON");
                bridge.put("endpoint", "http://127.0.0.1:" + port);
                resp.put("bridge", bridge);

                JSONObject proxies = new JSONObject();
                proxies.put("tor_socks5h", checkTcpPort(TOR_HOST, TOR_PORT));
                proxies.put("i2pd_http", checkTcpPort(I2P_HOST, I2P_PORT));
                proxies.put("searxng", checkSearxng(searxUrl));
                resp.put("proxies", proxies);

                sendResponse(out, 200, resp.toString());
                return;
            }

            if ("POST".equalsIgnoreCase(method) && path.equals("/search")) {
                JSONObject reqJson = body.isEmpty() ? new JSONObject() : new JSONObject(body);
                String q = reqJson.optString("query", "").trim();
                if (q.isEmpty()) {
                    sendResponse(out, 400, "{"ok":false,"error":"Parâmetro 'query' obrigatório."}");
                    return;
                }
                String searxUrl = reqJson.optString("searxng_url", DEFAULT_SEARXNG);
                JSONObject searchRes = runIcebergSearch(q, searxUrl);
                sendResponse(out, 200, searchRes.toString());
                return;
            }

            if ("POST".equalsIgnoreCase(method) && path.equals("/inspect")) {
                JSONObject reqJson = body.isEmpty() ? new JSONObject() : new JSONObject(body);
                String targetUrl = reqJson.optString("url", "").trim();
                if (targetUrl.isEmpty()) {
                    sendResponse(out, 400, "{"ok":false,"error":"Parâmetro 'url' obrigatório."}");
                    return;
                }
                JSONObject inspectRes = runOsintInspect(targetUrl);
                sendResponse(out, 200, inspectRes.toString());
                return;
            }

            sendResponse(out, 404, "{"ok":false,"error":"Rota não encontrada"}");
        } catch (Exception e) {
            // Ignora falhas de socket abortado pelo cliente
        }
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '') {
                int next = in.read();
                if (next != '
' && next != -1) {
                    // fallback
                }
                break;
            }
            if (b == '
') break;
            baos.write(b);
        }
        if (b == -1 && baos.size() == 0) return null;
        return baos.toString("UTF-8");
    }

    private void sendResponse(OutputStream out, int status, String jsonBody) throws IOException {
        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        String statusText = status == 200 ? "OK" : (status == 204 ? "No Content" : "Error");
        String headers = "HTTP/1.1 " + status + " " + statusText + "
" +
                "Access-Control-Allow-Origin: *
" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS
" +
                "Access-Control-Allow-Headers: Content-Type, Authorization
" +
                "Content-Type: application/json; charset=utf-8
" +
                "Content-Length: " + bytes.length + "
" +
                "Connection: close

";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        if (bytes.length > 0) out.write(bytes);
        out.flush();
    }

    private String getQueryParam(String query, String key, String defVal) {
        if (query == null || query.isEmpty()) return defVal;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                try {
                    return URLDecoder.decode(kv[1], "UTF-8");
                } catch (Exception ignored) {}
            }
        }
        return defVal;
    }

    private JSONObject checkTcpPort(String host, int p) {
        JSONObject res = new JSONObject();
        long start = System.nanoTime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, p), 1500);
            double ms = Math.round((System.nanoTime() - start) / 100000.0) / 10.0;
            res.put("status", "ON");
            res.put("host", host + ":" + p);
            res.put("latency_ms", ms);
        } catch (Exception e) {
            try {
                res.put("status", "OFF");
                res.put("host", host + ":" + p);
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res;
    }

    private JSONObject checkSearxng(String searxUrl) {
        JSONObject res = new JSONObject();
        long start = System.nanoTime();
        try {
            String target = searxUrl.replaceAll("/+$", "") + "/search?q=test&format=json";
            HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setRequestProperty("User-Agent", "LUX-Omega-Android/5.4");
            int code = conn.getResponseCode();
            double ms = Math.round((System.nanoTime() - start) / 100000.0) / 10.0;
            res.put("status", code == 200 ? "ON" : "OFF");
            res.put("url", searxUrl);
            res.put("latency_ms", ms);
        } catch (Exception e) {
            try {
                res.put("status", "OFF");
                res.put("url", searxUrl);
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res;
    }

    private String classifyLayer(String url, String engine) {
        String low = url == null ? "" : url.toLowerCase();
        if (low.contains(".onion")) return "Darknet Overlay (Tor .onion)";
        if (low.contains(".i2p")) return "Darknet Overlay (I2Pd .i2p)";
        String[] markers = {"pastebin", "archive.org", "github.com", "gitlab", "shodan", "censys", "crt.sh", "reddit", "intelx"};
        for (String m : markers) {
            if (low.contains(m)) return "Deep Web / Indexadores";
        }
        if (engine != null && (engine.equalsIgnoreCase("ahmia") || engine.equalsIgnoreCase("torch"))) {
            return "Deep Web / Indexadores";
        }
        return "Surface Web";
    }

    private JSONObject runIcebergSearch(String query, String searxUrl) throws Exception {
        long start = System.nanoTime();
        List<Callable<List<JSONObject>>> tasks = new ArrayList<>();
        tasks.add(() -> searchSearxng(query, searxUrl));
        tasks.add(() -> searchDuckDuckGo(query));
        tasks.add(() -> searchWikipedia(query, "pt"));
        tasks.add(() -> searchWikipedia(query, "en"));

        List<Future<List<JSONObject>>> futures = pool.invokeAll(tasks);
        Set<String> seen = new HashSet<>();
        JSONArray allResults = new JSONArray();

        Map<String, JSONArray> layers = new LinkedHashMap<>();
        layers.put("Surface Web", new JSONArray());
        layers.put("Deep Web / Indexadores", new JSONArray());
        layers.put("Darknet Overlay (Tor .onion)", new JSONArray());
        layers.put("Darknet Overlay (I2Pd .i2p)", new JSONArray());

        for (Future<List<JSONObject>> f : futures) {
            try {
                for (JSONObject item : f.get()) {
                    String u = item.optString("url", "");
                    if (u.isEmpty() || seen.contains(u)) continue;
                    seen.add(u);
                    allResults.put(item);
                    String l = item.optString("layer", "Surface Web");
                    if (!layers.containsKey(l)) layers.put(l, new JSONArray());
                    layers.get(l).put(item);
                }
            } catch (Exception ignored) {}
        }

        JSONObject byLayer = new JSONObject();
        for (Map.Entry<String, JSONArray> entry : layers.entrySet()) {
            byLayer.put(entry.getKey(), entry.getValue());
        }

        double elapsedMs = Math.round((System.nanoTime() - start) / 100000.0) / 10.0;
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("query", query);
        out.put("elapsed_ms", elapsedMs);
        out.put("total_results", allResults.length());
        out.put("by_layer", byLayer);
        out.put("results", allResults);
        return out;
    }

    private List<JSONObject> searchSearxng(String query, String searxUrl) {
        List<JSONObject> list = new ArrayList<>();
        try {
            String url = searxUrl.replaceAll("/+$", "") + "/search?q=" + URLEncoder.encode(query, "UTF-8") + "&format=json";
            String raw = simpleHttpGet(url, null, 5000);
            JSONObject obj = new JSONObject(raw);
            JSONArray arr = obj.optJSONArray("results");
            if (arr != null) {
                for (int i = 0; i < Math.min(arr.length(), 8); i++) {
                    JSONObject it = arr.getJSONObject(i);
                    String href = it.optString("url", "");
                    String eng = it.optString("engine", "searxng");
                    JSONObject row = new JSONObject();
                    row.put("title", it.optString("title", href));
                    row.put("url", href);
                    row.put("snippet", it.optString("content", ""));
                    row.put("source", "SearXNG (" + eng + ")");
                    row.put("layer", classifyLayer(href, eng));
                    list.add(row);
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private List<JSONObject> searchDuckDuckGo(String query) {
        List<JSONObject> list = new ArrayList<>();
        try {
            String url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8");
            String raw = simpleHttpGet(url, null, 6000);
            Pattern p = Pattern.compile("<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>.*?<a[^>]+class="result__snippet"[^>]*>(.*?)</a>", Pattern.DOTALL);
            Matcher m = p.matcher(raw);
            int count = 0;
            while (m.find() && count < 6) {
                String href = m.group(1);
                if (href.contains("uddg=")) {
                    int idx = href.indexOf("uddg=") + 5;
                    int end = href.indexOf("&", idx);
                    href = URLDecoder.decode(end > 0 ? href.substring(idx, end) : href.substring(idx), "UTF-8");
                }
                String title = stripHtml(m.group(2));
                String snip = stripHtml(m.group(3));
                JSONObject row = new JSONObject();
                row.put("title", title);
                row.put("url", href);
                row.put("snippet", snip);
                row.put("source", "DuckDuckGo");
                row.put("layer", classifyLayer(href, ""));
                list.add(row);
                count++;
            }
        } catch (Exception ignored) {}
        return list;
    }

    private List<JSONObject> searchWikipedia(String query, String lang) {
        List<JSONObject> list = new ArrayList<>();
        try {
            String url = "https://" + lang + ".wikipedia.org/w/api.php?action=query&list=search&srsearch=" + URLEncoder.encode(query, "UTF-8") + "&srlimit=3&format=json";
            String raw = simpleHttpGet(url, null, 5000);
            JSONObject obj = new JSONObject(raw);
            JSONArray arr = obj.getJSONObject("query").getJSONArray("search");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                String title = it.optString("title", "");
                String pageUrl = "https://" + lang + ".wikipedia.org/wiki/" + URLEncoder.encode(title.replace(" ", "_"), "UTF-8");
                JSONObject row = new JSONObject();
                row.put("title", title + " — Wikipedia (" + lang.toUpperCase() + ")");
                row.put("url", pageUrl);
                row.put("snippet", stripHtml(it.optString("snippet", "")));
                row.put("source", "Wikipedia-" + lang);
                row.put("layer", "Surface Web");
                list.add(row);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private JSONObject runOsintInspect(String targetUrl) {
        JSONObject out = new JSONObject();
        String urlStr = targetUrl.trim();
        if (!urlStr.matches("(?i)^https?://.*")) {
            urlStr = "http://" + urlStr;
        }
        String route = "Surface / Deep Web (Direto Android)";
        try {
            URL parsed = new URL(urlStr);
            String host = parsed.getHost().toLowerCase();

            if (host.endsWith(".onion")) {
                route = "Tor SOCKS5h via Orbot (" + TOR_HOST + ":" + TOR_PORT + ")";
                HttpResult res = fetchViaSocks5h(parsed);
                return buildInspectJson(urlStr, route, res.statusCode, res.headers, res.body);
            }

            if (host.endsWith(".i2p")) {
                route = "I2Pd HTTP Proxy (" + I2P_HOST + ":" + I2P_PORT + ")";
                Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(I2P_HOST, I2P_PORT));
                HttpResult res = fetchHttpUrlConnection(urlStr, proxy, 25000);
                return buildInspectJson(urlStr, route, res.statusCode, res.headers, res.body);
            }

            HttpResult res = fetchHttpUrlConnection(urlStr, null, 15000);
            return buildInspectJson(urlStr, route, res.statusCode, res.headers, res.body);

        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("url", urlStr);
                out.put("route", route);
                out.put("error", e.getMessage());
            } catch (Exception ignored) {}
            return out;
        }
    }

    private JSONObject buildInspectJson(String url, String route, int statusCode, Map<String, String> headers, String rawHtml) throws Exception {
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("url", url);
        out.put("route", route);
        out.put("status_code", statusCode);

        JSONObject hdrJson = new JSONObject();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null) hdrJson.put(e.getKey(), e.getValue());
        }
        out.put("headers", hdrJson);

        Matcher tm = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(rawHtml);
        out.put("title", tm.find() ? stripHtml(tm.group(1)) : "");

        JSONObject meta = new JSONObject();
        Matcher mm = Pattern.compile("<meta\s+[^>]*>", Pattern.CASE_INSENSITIVE).matcher(rawHtml);
        while (mm.find()) {
            String tag = mm.group();
            Matcher nm = Pattern.compile("(?:name|property)=["']([^"']+)["']", Pattern.CASE_INSENSITIVE).matcher(tag);
            Matcher cm = Pattern.compile("content=["']([^"']*)["']", Pattern.CASE_INSENSITIVE).matcher(tag);
            if (nm.find() && cm.find()) {
                meta.put(nm.group(1).toLowerCase(), cm.group(1));
            }
        }
        out.put("meta", meta);

        JSONArray darkLinks = new JSONArray();
        Set<String> seenLinks = new HashSet<>();
        Matcher lm = Pattern.compile("href=["'](https?://[^"']+|[^"']*\.(?:onion|i2p)[^"']*)["']", Pattern.CASE_INSENSITIVE).matcher(rawHtml);
        while (lm.find()) {
            String link = lm.group(1);
            if (!seenLinks.contains(link) && (link.toLowerCase().contains(".onion") || link.toLowerCase().contains(".i2p"))) {
                seenLinks.add(link);
                darkLinks.put(link);
            }
        }
        out.put("darknet_links_found", darkLinks);

        String noScript = rawHtml.replaceAll("(?is)<(script|style|noscript)[^>]*>.*?</\1>", " ");
        String cleanText = stripHtml(noScript);
        if (cleanText.length() > 6000) cleanText = cleanText.substring(0, 6000);
        out.put("clean_text", cleanText);

        return out;
    }

    private static class HttpResult {
        int statusCode;
        Map<String, String> headers = new LinkedHashMap<>();
        String body;
    }

    private String simpleHttpGet(String urlStr, Proxy proxy, int timeoutMs) throws IOException {
        return fetchHttpUrlConnection(urlStr, proxy, timeoutMs).body;
    }

    private HttpResult fetchHttpUrlConnection(String urlStr, Proxy proxy, int timeoutMs) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) (proxy != null ? url.openConnection(proxy) : url.openConnection());
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) LUX-Omega/5.4");

        HttpResult res = new HttpResult();
        res.statusCode = conn.getResponseCode();
        for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
            if (entry.getKey() != null && !entry.getValue().isEmpty()) {
                res.headers.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        InputStream stream = res.statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
        res.body = stream != null ? readAll(stream) : "";
        return res;
    }

    // Cliente SOCKS5h nativo (ATYP=0x03 Domain Name) para resolução remota no Orbot sem vazamento DNS
    private HttpResult fetchViaSocks5h(URL url) throws Exception {
        String host = url.getHost();
        boolean isHttps = "https".equalsIgnoreCase(url.getProtocol());
        int targetPort = url.getPort() != -1 ? url.getPort() : (isHttps ? 443 : 80);
        String path = (url.getPath() == null || url.getPath().isEmpty()) ? "/" : url.getPath();
        if (url.getQuery() != null) path += "?" + url.getQuery();

        Socket rawSocket = new Socket();
        rawSocket.connect(new InetSocketAddress(TOR_HOST, TOR_PORT), 10000);
        rawSocket.setSoTimeout(25000);

        OutputStream out = rawSocket.getOutputStream();
        InputStream in = rawSocket.getInputStream();

        // Greeting SOCKS5: versão 5, 1 método, sem autenticação (0x00)
        out.write(new byte[]{0x05, 0x01, 0x00});
        out.flush();
        byte[] greetResp = new byte[2];
        readFully(in, greetResp);
        if (greetResp[0] != 0x05 || greetResp[1] != 0x00) {
            rawSocket.close();
            throw new IOException("Falha na autenticação SOCKS5 do Orbot.");
        }

        // Request CONNECT com domínio remoto (0x03)
        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        req.write(0x05);
        req.write(0x01); // CONNECT
        req.write(0x00); // RSV
        req.write(0x03); // DOMAINNAME (socks5h)
        req.write(hostBytes.length);
        req.write(hostBytes);
        req.write((targetPort >> 8) & 0xFF);
        req.write(targetPort & 0xFF);
        out.write(req.toByteArray());
        out.flush();

        byte[] repHdr = new byte[4];
        readFully(in, repHdr);
        if (repHdr[1] != 0x00) {
            rawSocket.close();
            throw new IOException("Orbot/Tor recusou conexão .onion (código SOCKS5: " + repHdr[1] + ")");
        }
        int atyp = repHdr[3];
        if (atyp == 0x01) {
            readFully(in, new byte[4 + 2]);
        } else if (atyp == 0x03) {
            int len = in.read();
            readFully(in, new byte[len + 2]);
        } else if (atyp == 0x04) {
            readFully(in, new byte[16 + 2]);
        }

        Socket activeSocket = rawSocket;
        if (isHttps) {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) factory.createSocket(rawSocket, host, targetPort, true);
            ssl.startHandshake();
            activeSocket = ssl;
        }

        OutputStream httpOut = activeSocket.getOutputStream();
        InputStream httpIn = activeSocket.getInputStream();
        String httpReq = "GET " + path + " HTTP/1.1
" +
                "Host: " + host + "
" +
                "User-Agent: Mozilla/5.0 (rv:115.0) Gecko/20100101 Firefox/115.0
" +
                "Accept: text/html,application/xhtml+xml,*/*
" +
                "Connection: close

";
        httpOut.write(httpReq.getBytes(StandardCharsets.UTF_8));
        httpOut.flush();

        String rawResponse = readAll(httpIn);
        activeSocket.close();

        HttpResult res = new HttpResult();
        int splitIdx = rawResponse.indexOf("

");
        String headerBlock = splitIdx > 0 ? rawResponse.substring(0, splitIdx) : rawResponse;
        res.body = splitIdx > 0 ? rawResponse.substring(splitIdx + 4) : "";

        String[] headerLines = headerBlock.split("
");
        if (headerLines.length > 0) {
            String[] statusParts = headerLines[0].split(" ");
            if (statusParts.length >= 2) {
                try { res.statusCode = Integer.parseInt(statusParts[1]); } catch (Exception ignored) { res.statusCode = 200; }
            }
        }
        for (int i = 1; i < headerLines.length; i++) {
            int colon = headerLines[i].indexOf(":");
            if (colon > 0) {
                res.headers.put(headerLines[i].substring(0, colon).trim(), headerLines[i].substring(colon + 1).trim());
            }
        }
        return res;
    }

    private void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r == -1) throw new IOException("Conexão encerrada prematuramente pelo proxy.");
            off += r;
        }
    }

    private String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) != -1) {
            baos.write(buf, 0, r);
        }
        return baos.toString("UTF-8");
    }

    private String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", """)
                .replace("&#39;", "'")
                .replaceAll("\s+", " ")
                .trim();
    }
}
