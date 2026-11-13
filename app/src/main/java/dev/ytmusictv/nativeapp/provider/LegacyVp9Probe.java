package dev.ytmusictv.nativeapp.provider;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.schabi.newpipe.extractor.stream.VideoStream;

/** One bounded binary range probe; the selector permits at most two per explicit 4K request. */
final class LegacyVp9Probe implements YouTubeStreamResolver.LegacyVp9Verifier {
    static final int MAX_BYTES = 256 * 1024;
    @Override public boolean verify(VideoStream stream) {
        HttpURLConnection connection = null;
        try {
            if (Thread.currentThread().isInterrupted()) return false;
            URL url = new URL(stream.getContent());
            if (!"https".equalsIgnoreCase(url.getProtocol())) return false;
            connection = (HttpURLConnection)url.openConnection();
            connection.setConnectTimeout(4000);connection.setReadTimeout(4000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent",ProviderHttp.USER_AGENT);
            connection.setRequestProperty("Referer","https://www.youtube.com/");
            connection.setRequestProperty("Accept-Encoding","identity");
            connection.setRequestProperty("Range","bytes=0-"+(MAX_BYTES-1));
            long deadline = System.nanoTime() + 8_000_000_000L;
            if (connection.getResponseCode() != 206 || Thread.currentThread().isInterrupted()) return false;
            String range = connection.getHeaderField("Content-Range");
            String encoding = connection.getContentEncoding();
            if (range == null || !range.matches("bytes 0-[0-9]+/[0-9]+")
                    || (encoding != null && !encoding.equalsIgnoreCase("identity"))
                    || connection.getContentLengthLong() > MAX_BYTES) return false;
            long last = Long.parseLong(range.substring(8,range.indexOf('/')));
            long total = Long.parseLong(range.substring(range.indexOf('/')+1));
            if (last < 0 || last >= MAX_BYTES || total <= last) return false;
            int expected = (int)last + 1;
            long contentLength = connection.getContentLengthLong();
            if (contentLength >= 0 && contentLength != expected) return false;
            byte[] prefix = new byte[MAX_BYTES];
            int size = 0;
            try (InputStream input = connection.getInputStream()) {
                while (size < expected) {
                    long remaining = deadline - System.nanoTime();
                    if (Thread.currentThread().isInterrupted() || remaining <= 0) return false;
                    connection.setReadTimeout((int)Math.max(1,Math.min(4000,remaining/1_000_000L)));
                    int read = input.read(prefix,size,Math.min(8192,expected-size));
                    if (read < 0) break;
                    if (read == 0) return false;
                    size += read;
                }
            }
            return size == expected && !Thread.currentThread().isInterrupted() && System.nanoTime() <= deadline
                    && WebmVp9Header.verify(prefix,size,stream.getWidth(),stream.getHeight());
        } catch (java.io.IOException | RuntimeException ignored) {
            return false;
        } finally { if (connection != null) connection.disconnect(); }
    }
}
