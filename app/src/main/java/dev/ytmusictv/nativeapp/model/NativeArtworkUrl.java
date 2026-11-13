package dev.ytmusictv.nativeapp.model;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative Google image sizing. Identity/crop flags stay intact; original is always a fallback. */
public final class NativeArtworkUrl {
    private NativeArtworkUrl() { }

    /** At most two URLs: a supported larger rendition, then the exact supplied original. */
    public static List<String> candidates(String originalUrl, int requestedPixels) {
        if (originalUrl == null || originalUrl.isEmpty()) return Collections.emptyList();
        List<String> unchanged = Collections.singletonList(originalUrl);
        if (requestedPixels <= 0) return unchanged;
        try {
            URI uri = new URI(originalUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawFragment() != null) return unchanged;
            if (uri.getHost().equalsIgnoreCase("i.ytimg.com")) {
                Matcher video = Pattern.compile("/vi/([A-Za-z0-9_-]{11})/(?:hqdefault|mqdefault|sddefault|default)\\.(?:jpg|webp)")
                        .matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
                if (!video.matches() || !knownYoutubeQuery(uri.getRawQuery())) return unchanged;
                String promoted = "https://i.ytimg.com/vi/" + video.group(1) + "/maxresdefault.jpg";
                return Collections.unmodifiableList(Arrays.asList(promoted, originalUrl));
            }
            if (uri.getRawQuery() != null || !knownHost(uri.getHost())) return unchanged;
            String path = uri.getRawPath();
            int equals = path == null ? -1 : path.lastIndexOf('=');
            if (equals < 1) return unchanged;
            String options = path.substring(equals + 1);
            if (options.length() > 120 || !options.matches("[A-Za-z0-9_-]+")) return unchanged;
            String[] tokens = options.split("-", -1);
            int width = 0, height = 0, square = 0;
            int wi = -1, hi = -1, si = -1;
            for (int index = 0; index < tokens.length; index++) {
                String token = tokens[index];
                if (token.matches("[whs]\\d{1,5}")) {
                    int size = Integer.parseInt(token.substring(1));
                    if (size < 1 || size > 8192) return unchanged;
                    switch (token.charAt(0)) {
                        case 'w': if (wi >= 0) return unchanged; width = size; wi = index; break;
                        case 'h': if (hi >= 0) return unchanged; height = size; hi = index; break;
                        case 's': if (si >= 0) return unchanged; square = size; si = index; break;
                        default: return unchanged;
                    }
                } else if (token.isEmpty() || token.startsWith("w") || token.startsWith("h") || token.startsWith("s")) {
                    return unchanged;
                }
            }
            if (square > 0 && (width > 0 || height > 0)) return unchanged;
            int originalPixels = square > 0 ? square : Math.max(width, height);
            int target = Math.min(1024, requestedPixels);
            if (originalPixels <= 0 || originalPixels >= target) return unchanged;
            if (square > 0) tokens[si] = "s" + target;
            else {
                // Scale both sides equally: changing the crop/aspect ratio changes the artwork.
                double scale = target / (double) originalPixels;
                if (wi >= 0) tokens[wi] = "w" + Math.max(1, Math.round(width * scale));
                if (hi >= 0) tokens[hi] = "h" + Math.max(1, Math.round(height * scale));
            }
            int originalEquals = originalUrl.lastIndexOf('=');
            String promoted = originalUrl.substring(0, originalEquals + 1) + String.join("-", tokens);
            return promoted.equals(originalUrl) ? unchanged : Collections.unmodifiableList(Arrays.asList(promoted, originalUrl));
        } catch (Exception invalid) {
            return unchanged;
        }
    }

    private static boolean knownYoutubeQuery(String query) {
        if (query == null) return true;
        if (query.isEmpty()) return false;
        boolean sqp=false, rs=false;
        for (String pair : query.split("&", -1)) {
            int equals=pair.indexOf('=');
            if (equals < 1 || equals == pair.length()-1) return false;
            String key=pair.substring(0,equals);
            if (key.equals("sqp") && !sqp) sqp=true;
            else if (key.equals("rs") && !rs) rs=true;
            else return false;
        }
        return true;
    }

    private static boolean knownHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals("lh3.googleusercontent.com") || value.equals("lh4.googleusercontent.com")
                || value.equals("lh5.googleusercontent.com") || value.equals("lh6.googleusercontent.com")
                || value.equals("yt3.googleusercontent.com") || value.equals("lh3.ggpht.com")
                || value.equals("yt3.ggpht.com");
    }
}
