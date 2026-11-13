package dev.ytmusictv.nativeapp.model;

/** Stable native artist/album catalogue identity; id is an InnerTube browse ID. */
public final class NativeCollection {
    public enum Kind { ARTIST, ALBUM }
    public final String id, title, subtitle, thumbnailUrl, browseParams;
    public final Kind kind;
    public NativeCollection(String id,String title,String subtitle,String thumbnailUrl,Kind kind) {
        this(id,title,subtitle,thumbnailUrl,kind,"");
    }
    public NativeCollection(String id,String title,String subtitle,String thumbnailUrl,Kind kind,String browseParams) {
        this.id=clean(id,256);this.title=clean(title,512);this.subtitle=clean(subtitle,512);
        this.thumbnailUrl=clean(thumbnailUrl,2048);this.kind=kind==null?Kind.ALBUM:kind;
        this.browseParams=clean(browseParams,4096);
    }
    private static String clean(String value,int limit) {
        if(value==null)return "";value=value.trim();return value.length()>limit?value.substring(0,limit):value;
    }
}
