package dev.ytmusictv.nativeapp.model;

/** Blocking resolution, called only on the playback engine's bounded worker. */
public interface NativeStreamResolver {
    PlaybackSource resolve(NativeTrack track, boolean video) throws Exception;
}
