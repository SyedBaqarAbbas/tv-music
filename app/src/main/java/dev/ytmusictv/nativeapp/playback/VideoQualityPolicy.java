package dev.ytmusictv.nativeapp.playback;

/** Bounded requested maxima. Zero is Auto, with the original 720p ceiling. */
final class VideoQualityPolicy {
    private VideoQualityPolicy() { }
    static int require(int value) {
        switch(value) {
            case 0: case 360: case 480: case 720: case 1080: case 2160: return value;
            default: throw new IllegalArgumentException("Choose Auto, 360p, 480p, 720p, 1080p or 4K.");
        }
    }
    static int restored(int value) {
        try { return require(value); } catch(IllegalArgumentException unknown) { return 0; }
    }
}
