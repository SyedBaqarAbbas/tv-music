package dev.ytmusictv.nativeapp.model;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
public final class NativeAlbumPage {
    public final NativeCollection album;
    public final List<NativeTrack> tracks;
    public final boolean complete;
    public NativeAlbumPage(NativeCollection album,List<NativeTrack> tracks,boolean complete) {
        this.album=album;this.tracks=Collections.unmodifiableList(new ArrayList<>(tracks));this.complete=complete;
    }
}
