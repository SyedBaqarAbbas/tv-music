package dev.ytmusictv.nativeapp.model;

/** Blocking resolution, called only on the playback engine's bounded worker. */
public interface NativeStreamResolver {
    PlaybackSource resolve(NativeTrack track, boolean video) throws Exception;

    /** Maximum video height preference; zero retains the default up-to-720p policy. */
    default PlaybackSource resolve(NativeTrack track, boolean video, int videoQuality) throws Exception {
        return resolve(track, video);
    }
}
