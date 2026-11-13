package dev.ytmusictv;

/** Run with javac/java; no Android SDK, network, or test library is required. */
public final class LyricsMatcherTest {
    private static int checks;

    public static void main(String[] args) {
        equal("LEMONADE", LyricsMatcher.cleanTitle("LEMONADE (Official Music Video)", "aespa"));
        equal("LEMONADE", LyricsMatcher.cleanTitle("aespa - LEMONADE | Official Video", "aespa - Topic"));
        equal("LEMONADE", LyricsMatcher.cleanTitle("LEMONADE [Official M/V]", "aespa"));
        equal("aespa", LyricsMatcher.cleanArtist("aespa - Topic"));
        equal("aespa", LyricsMatcher.cleanArtist("aespaVEVO"));
        equal("Artist A", LyricsMatcher.queryArtist("Artist A feat. Artist B"));
        equal("Feather", LyricsMatcher.queryArtist("Feather"));
        check(LyricsMatcher.sameTrack("Lemonade", "aespa", "LEMONADE", "aespa"));
        check(LyricsMatcher.sameTrack("Artist A - Song (Official Video)", "Artist A - Topic", "Song", "Artist A"));
        check(LyricsMatcher.sameTrack("Song", "Artist A, Artist B", "Song", "Artist B"));
        check(LyricsMatcher.sameTrack("Deja Vu", "Beyonce", "Déjà Vu", "Beyoncé"));
        check(!LyricsMatcher.sameTrack("Lemonade", "aespa", "Lemonade", "Different artist"));
        check(!LyricsMatcher.sameTrack("Lemonade", "aespa", "Lemonade (Live)", "aespa"));
        check(!LyricsMatcher.sameTrack("Lemonade", "aespa", "Lemonade (Remix)", "aespa"));
        check(!LyricsMatcher.sameTrack("Lemonade", "aespa", "Lemonade (Acoustic)", "aespa"));
        check(!LyricsMatcher.sameTrack("Lemonade", "aespa", "Lemonade (Instrumental)", "aespa"));
        check(!LyricsMatcher.sameTrack("Song (Live)", "Artist", "Song", "Artist"));
        check(!LyricsMatcher.sameTrack("Song", "Artist", "Song 2", "Artist"));
        check(LyricsMatcher.timingMatches(187, 187));
        check(LyricsMatcher.timingMatches(190, 187));
        check(!LyricsMatcher.timingMatches(191, 187)); // Actual TV MV versus album recording.
        check(!LyricsMatcher.timingMatches(0, 187));
        check(!LyricsMatcher.timingMatches(187, Double.NaN));
        check(!LyricsMatcher.timingMatches(187, Double.POSITIVE_INFINITY));
        equal("Synthetic first line\nSynthetic second line", LyricsMatcher.plainFromSynced(
                "[ar:Test artist]\n[offset:100]\n[00:01.00]Synthetic first line\n[00:02.00][00:04.00]Synthetic second line"));
        System.out.println("Lyrics matching checks passed: " + checks);
    }

    private static void equal(String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + "; got " + actual);
    }

    private static void check(boolean result) {
        checks++;
        if (!result) throw new AssertionError("Failed check " + checks);
    }
}
