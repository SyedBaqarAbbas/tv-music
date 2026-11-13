package dev.ytmusictv;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative matching: live, remix, cover and other recording words stay intact. */
final class LyricsMatcher {
    private static final Pattern BRACKETS = Pattern.compile("[\\[(]([^\\]\\)]{1,80})[\\])]");
    private static final Pattern DECORATION = Pattern.compile(
            "(?:(?:official|music|video|audio|lyrics?|visuali[sz]er|hd|hq|4k|1080p|720p|mv)\\s*)+");
    private static final Pattern CHANNEL_SUFFIX = Pattern.compile("(?i)(?:\\s*-\\s*Topic|VEVO|\\s+Official)$");
    private static final Pattern ARTISTS = Pattern.compile("(?i)\\s*(?:;|,|&|\\b(?:featuring|feat\\.?|ft\\.?)\\s+)\\s*");
    private static final Pattern TIMESTAMP = Pattern.compile("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?\\]");
    private static final Pattern LRC_METADATA = Pattern.compile("(?m)^\\[(?:ar|al|ti|au|by|offset|re|ve|length):[^\\r\\n]*\\]\\s*");

    private LyricsMatcher() { }

    static String cleanArtist(String artist) {
        return CHANNEL_SUFFIX.matcher(artist.trim()).replaceFirst("").trim();
    }

    static String queryArtist(String artist) {
        String cleaned = cleanArtist(artist);
        String[] parts = ARTISTS.split(cleaned, 2);
        return parts.length == 0 || parts[0].isEmpty() ? cleaned : parts[0].trim();
    }

    static String cleanTitle(String title, String artist) {
        Matcher brackets = BRACKETS.matcher(title);
        StringBuffer cleaned = new StringBuffer();
        while (brackets.find()) {
            brackets.appendReplacement(cleaned, decoration(brackets.group(1)) ? " "
                    : Matcher.quoteReplacement(brackets.group()));
        }
        brackets.appendTail(cleaned);
        String result = cleaned.toString().trim();
        for (int attempt = 0; attempt < 3; attempt++) {
            int split = Math.max(result.lastIndexOf(" - "), result.lastIndexOf(" | "));
            if (split < 0 || !decoration(result.substring(split + 3))) break;
            result = result.substring(0, split).trim();
        }
        int prefix = result.indexOf(" - ");
        if (prefix > 0 && artistMatches(result.substring(0, prefix), artist)) {
            result = result.substring(prefix + 3).trim();
        }
        return result.replaceAll("\\s+", " ").trim();
    }

    static boolean sameTrack(String title, String artist, String candidateTitle, String candidateArtist) {
        String expected = normalize(cleanTitle(title, artist));
        return !expected.isEmpty()
                && expected.equals(normalize(cleanTitle(candidateTitle, candidateArtist)))
                && artistMatches(artist, candidateArtist);
    }

    static boolean timingMatches(int duration, double candidateDuration) {
        return duration >= 1 && duration <= 3600 && Double.isFinite(candidateDuration)
                && candidateDuration >= 1 && candidateDuration <= 3600
                && Math.abs(duration - candidateDuration) <= 3;
    }

    static String plainFromSynced(String synced) {
        return TIMESTAMP.matcher(LRC_METADATA.matcher(synced).replaceAll("")).replaceAll("").trim();
    }

    static String normalize(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(folded.length());
        boolean space = false;
        for (int offset = 0; offset < folded.length();) {
            int codePoint = folded.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                if (space && result.length() > 0) result.append(' ');
                result.appendCodePoint(codePoint);
                space = false;
            } else if (Character.isWhitespace(codePoint)) {
                space = true;
            }
        }
        return result.toString();
    }

    private static boolean decoration(String value) {
        String normalized = normalize(value);
        return !normalized.isEmpty() && DECORATION.matcher(normalized).matches();
    }

    private static boolean artistMatches(String first, String second) {
        String a = normalize(cleanArtist(first));
        String b = normalize(cleanArtist(second));
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        String[] left = ARTISTS.split(cleanArtist(first), 8);
        String[] right = ARTISTS.split(cleanArtist(second), 8);
        for (String one : left) {
            String name = normalize(one);
            if (name.isEmpty()) continue;
            for (String two : right) if (name.equals(normalize(two))) return true;
        }
        return false;
    }
}
