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
        return resolve(track, video, 0);
    }

    @Override public PlaybackSource resolve(NativeTrack track, boolean video, int maximumVideoHeight) throws Exception {
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
        VideoStream picture = chooseVideo(extractor.getVideoStreams(), extractor.getVideoOnlyStreams(),
                maximumVideoHeight, audio != null, new HardwareVideoSupport(), new LegacyVp9Probe());
        if (picture != null && !picture.isVideoOnly()) {
            // A muxed source already contains audio: the player must not merge a duplicate.
            return new PlaybackSource("", picture.getContent(), "", mime(picture, "video/mp4"),
                    headers, expires(picture.getContent()));
        }
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

    static int videoHeightCap(int requested) {
        switch (requested) {
            case 360: case 480: case 720: case 1080: case 2160: return requested;
            default: return 720; // Auto and unrecognized saved values retain the TV-safe default.
        }
    }

    interface DecoderSupport { boolean supports(int width, int height, int fps, int vp9Level); }
    interface LegacyVp9Verifier { boolean verify(VideoStream stream); }

    /** Compare both representation lists before choosing; muxed only wins a resolution tie. */
    static VideoStream chooseVideo(List<VideoStream> muxed, List<VideoStream> videoOnly,
            int requestedHeight, boolean audioAvailable) {
        return chooseVideo(muxed, videoOnly, requestedHeight, audioAvailable, new HardwareVideoSupport());
    }
    static VideoStream chooseVideo(List<VideoStream> muxed, List<VideoStream> videoOnly,
            int requestedHeight, boolean audioAvailable, DecoderSupport decoder) {
        VideoStream best = null;
        int bestHeight = 0;
        int cap = videoHeightCap(requestedHeight);
        for (int group = 0; group < 2; group++) {
            List<VideoStream> streams = group == 0 ? muxed : videoOnly;
            if (streams == null) continue;
            for (VideoStream stream : streams) {
                if (!usable(stream) || (stream.isVideoOnly() && !audioAvailable)) continue;
                // Unknown rates cannot establish the supported 30 fps ceiling.
                if (stream.getFps() <= 0 || stream.getFps() > 30) continue;
                int height = videoHeight(stream);
                if (height <= 0 || height > cap) continue;
                String codec = stream.getCodec();
                if (height <= 1080) {
                    if (!"video/mp4".equals(mime(stream, "")) || codec == null
                            || !codec.regionMatches(true, 0, "avc", 0, 3)) continue;
                } else {
                    int level = VideoCodecPolicy.verifiedVp9SdrLevel(codec);
                    String type = mime(stream, "");
                    if (cap != 2160 || level == 0 || stream.getWidth() <= 0 || stream.getWidth() > 4096
                            || (!type.equals("video/webm") && !type.equals("video/mp4")) || decoder == null || !decoder.supports(stream.getWidth(), height, stream.getFps(), level)) continue;
                }
                if (height > bestHeight || (height == bestHeight && best != null
                        && best.isVideoOnly() && !stream.isVideoOnly())) {
                    best = stream;
                    bestHeight = height;
                }
            }
        }
        return best;
    }

    /** Legacy 'vp9' metadata requires actual header proof, after the hardware preflight. */
    static VideoStream chooseVideo(List<VideoStream> muxed, List<VideoStream> videoOnly,
            int requestedHeight, boolean audioAvailable, DecoderSupport decoder, LegacyVp9Verifier verifier) {
        VideoStream best = chooseVideo(muxed, videoOnly, requestedHeight, audioAvailable, decoder);
        if (requestedHeight != 2160 || decoder == null || verifier == null) return best;
        VideoStream first = null, second = null;
        for (int group = 0; group < 2; group++) {
            List<VideoStream> streams = group == 0 ? muxed : videoOnly;
            if (streams == null) continue;
            for (VideoStream stream : streams) {
                if (!usable(stream) || (stream.isVideoOnly() && !audioAvailable)
                        || !"video/webm".equals(mime(stream,"")) || !"vp9".equalsIgnoreCase(stream.getCodec())
                        || stream.getFps() <= 0 || stream.getFps() > 30 || stream.getWidth() <= 0
                        || stream.getWidth() > 4096 || stream.getHeight() <= 1080 || stream.getHeight() > 2160
                        || (best != null && stream.getHeight() <= videoHeight(best))) continue;
                if (first == null || stream.getHeight() > first.getHeight()) { second = first; first = stream; }
                else if (stream != first && (second == null || stream.getHeight() > second.getHeight())) second = stream;
            }
        }
        for (VideoStream candidate : new VideoStream[]{first,second}) {
            if (Thread.currentThread().isInterrupted()) break;
            if (candidate != null && decoder.supports(candidate.getWidth(),candidate.getHeight(),candidate.getFps(),0)
                    && verifier.verify(candidate)) return candidate;
        }
        return best;
    }

    private static int videoHeight(VideoStream stream) {
        if (stream.getHeight() > 0) return stream.getHeight();
        String resolution = stream.getResolution();
        if (resolution != null && resolution.matches("[0-9]{2,4}p(?:[0-9]{1,3})?")) {
            try { return Integer.parseInt(resolution.substring(0, resolution.indexOf('p'))); }
            catch (NumberFormatException ignored) { }
        }
        return 0;
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
