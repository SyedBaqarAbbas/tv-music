package dev.ytmusictv.nativeapp.provider;

import dev.ytmusictv.nativeapp.model.NativeTrack;
import dev.ytmusictv.nativeapp.model.NativeStreamResolver;
import dev.ytmusictv.nativeapp.model.PlaybackSource;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.VideoStream;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Native stream extraction through GPL-3.0-or-later NewPipeExtractor; no website player. */
public final class YouTubeStreamResolver implements NativeStreamResolver {
    private static boolean initialized;
    private static synchronized void initialize() {
        if (initialized) return;
        NewPipe.init(new Downloader() {
            @Override public Response execute(Request request) throws IOException {
                // Player scripts can exceed 2 MB. One extraction runs at a time in the service.
                try (ProviderHttp http = new ProviderHttp()) {
                    ProviderHttp.Reply reply = http.request(request.url(), request.httpMethod(),
                            request.headers(), request.dataToSend(), 8 * 1024 * 1024);
                    return new Response(reply.code, reply.message, reply.headers, reply.body, reply.url);
                }
            }
        }, new Localization("en", "US"), new ContentCountry("US"));
        initialized = true;
    }

    public YouTubeStreamResolver() { initialize(); }

    @Override public PlaybackSource resolve(NativeTrack track, boolean video) throws Exception {
        if (track == null || !track.videoId.matches("[A-Za-z0-9_-]{11}")) throw new IOException("Invalid track identity.");
        StreamExtractor extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=" + track.videoId);
        extractor.fetchPage();
        AudioStream audio = chooseAudio(extractor.getAudioStreams());
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", ProviderHttp.USER_AGENT);
        headers.put("Referer", "https://www.youtube.com/");
        if (!video) {
            if (audio == null) throw new IOException("No playable audio source is available for this recording.");
            return new PlaybackSource(audio.getContent(), "", mime(audio, "audio/mp4"), "",
                    headers, expires(audio.getContent()));
        }
        VideoStream picture = chooseVideo(extractor.getVideoStreams());
        if (picture != null) {
            // A muxed source already contains audio: the player must not merge a duplicate.
            return new PlaybackSource("", picture.getContent(), "", mime(picture, "video/mp4"),
                    headers, expires(picture.getContent()));
        }
        picture = chooseVideo(extractor.getVideoOnlyStreams());
        if (picture == null || audio == null) throw new IOException("Video is unavailable for this recording. Audio can still play.");
        return new PlaybackSource(audio.getContent(), picture.getContent(), mime(audio, "audio/mp4"),
                mime(picture, "video/mp4"), headers, Math.min(expires(audio.getContent()), expires(picture.getContent())));
    }

    private static AudioStream chooseAudio(List<AudioStream> streams) {
        AudioStream best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AudioStream stream : streams) {
            if (!usable(stream)) continue;
            String mime = mime(stream, "");
            int bitrate = Math.max(0, stream.getAverageBitrate());
            int score = (mime.contains("mp4") ? 1000 : 0) + Math.min(bitrate, 192);
            if (score > bestScore) { best = stream; bestScore = score; }
        }
        return best;
    }

    private static VideoStream chooseVideo(List<VideoStream> streams) {
        VideoStream best = null;
        int bestScore = Integer.MIN_VALUE;
        for (VideoStream stream : streams) {
            if (!usable(stream)) continue;
            int height = stream.getHeight();
            if (height <= 0) {
                try { height = Integer.parseInt(stream.getResolution().replaceAll("[^0-9].*", "")); }
                catch (NumberFormatException ignored) { height = 360; }
            }
            if (height > 720 || stream.getFps() > 30) continue;
            String codec = stream.getCodec();
            int score = (mime(stream, "").contains("mp4") ? 1500 : 0)
                    + (codec != null && codec.startsWith("avc") ? 1000 : 0) + height;
            if (score > bestScore) { best = stream; bestScore = score; }
        }
        return best;
    }

    private static boolean usable(Stream stream) {
        return stream != null && stream.isUrl() && stream.getContent() != null
                && stream.getContent().startsWith("https://")
                && "PROGRESSIVE_HTTP".equals(stream.getDeliveryMethod().name());
    }
    private static String mime(Stream stream, String fallback) {
        return stream.getFormat() == null ? fallback : stream.getFormat().getMimeType();
    }
    private static long expires(String url) {
        try {
            String query = new URI(url).getRawQuery();
            if (query != null) for (String part : query.split("&")) if (part.startsWith("expire=")) {
                return Long.parseLong(part.substring(7)) * 1000L;
            }
        } catch (Exception ignored) { }
        return System.currentTimeMillis() + 5 * 60_000L;
    }
}
