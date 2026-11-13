package dev.ytmusictv.nativeapp.model;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
public final class NativeArtistPage {
    public final NativeCollection artist;
    public final List<NativeCollection> albums;
    public final boolean complete;
    public NativeArtistPage(NativeCollection artist,List<NativeCollection> albums,boolean complete) {
        this.artist=artist;this.albums=Collections.unmodifiableList(new ArrayList<>(albums));this.complete=complete;
    }
}
