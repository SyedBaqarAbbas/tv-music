package dev.ytmusictv.nativeapp.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Bounded HTTPS transport. It never logs bodies, authorization, or signed URLs. */
final class ProviderHttp implements AutoCloseable {
    static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36";
    private volatile boolean closed;
    private volatile HttpURLConnection active;

    static final class Reply {
        final int code;
        final String body, message, url;
        final Map<String, List<String>> headers;
        Reply(int code, String body, String message, String url, Map<String, List<String>> headers) {
            this.code = code; this.body = body; this.message = message; this.url = url; this.headers = headers;
        }
    }

    Reply request(String url, String method, Map<String, List<String>> headers, byte[] body, int limit) throws IOException {
        for (int redirects = 0; redirects < 4; redirects++) {
            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Request cancelled.");
            URL endpoint = new URL(url);
            if (!"https".equalsIgnoreCase(endpoint.getProtocol())) throw new IOException("Insecure provider URL rejected.");
            HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
            active = connection;
            try {
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(12_000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod(method);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept-Encoding", "gzip");
                for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                    if (header.getKey() == null || header.getValue() == null) continue;
                    if (header.getKey().equalsIgnoreCase("Accept-Encoding")) continue;
                    boolean first = true;
                    for (String value : header.getValue()) {
                        if (first) connection.setRequestProperty(header.getKey(), value);
                        else connection.addRequestProperty(header.getKey(), value);
                        first = false;
                    }
                }
                if (body != null && body.length > 0) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length);
                    try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
                }
                int code = connection.getResponseCode();
                if (code >= 300 && code <= 399 && connection.getHeaderField("Location") != null) {
                    URL redirected = new URL(endpoint, connection.getHeaderField("Location"));
                    // Never forward a session or authorization to another origin.
                    if (!endpoint.getHost().equalsIgnoreCase(redirected.getHost())) {
                        if (headers.containsKey("Cookie") || headers.containsKey("Authorization")) {
                            throw new IOException("Authenticated provider redirect rejected.");
                        }
                    }
                    url = redirected.toString();
                    if (code == 303) { method = "GET"; body = null; }
                    continue;
                }
                InputStream raw = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String text = "";
                if (raw != null && !"HEAD".equals(method)) {
                    InputStream decoded = "gzip".equalsIgnoreCase(connection.getContentEncoding()) ? new GZIPInputStream(raw) : raw;
                    try (InputStream input = decoded; ByteArrayOutputStream output = new ByteArrayOutputStream(8192)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Request cancelled.");
                            if (output.size() + count > limit) throw new IOException("Provider response exceeds the memory limit.");
                            output.write(buffer, 0, count);
                        }
                        text = output.toString(StandardCharsets.UTF_8.name());
                    }
                } else if (raw != null) raw.close();
                Map<String, List<String>> responseHeaders = connection.getHeaderFields();
                return new Reply(code, text, connection.getResponseMessage(), url,
                        responseHeaders == null ? Collections.emptyMap() : responseHeaders);
            } finally {
                connection.disconnect();
                if (active == connection) active = null;
            }
        }
        throw new IOException("Too many provider redirects.");
    }

    @Override public void close() {
        closed = true;
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }
}
