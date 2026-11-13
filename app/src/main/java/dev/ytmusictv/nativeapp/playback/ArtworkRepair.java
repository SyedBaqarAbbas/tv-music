package dev.ytmusictv.nativeapp.playback;

import dev.ytmusictv.nativeapp.model.NativeTrack;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Repairs catalogue metadata only. No playlist, stream or playback operation belongs here. */
final class ArtworkRepair {
    private ArtworkRepair() { }

    static int fillMissing(Map<String,NativeTrack> queued,List<NativeTrack> fresh){
        if(fresh==null||fresh.size()>QueueCheckpoint.MAX_ENTRIES)throw new IllegalArgumentException("Invalid artwork batch");
        Map<String,List<NativeTrack>> candidates=new HashMap<>();
        for(NativeTrack track:fresh){
            if(track==null)throw new IllegalArgumentException("Invalid artwork track");
            if(track.videoId.isEmpty()||!validCover(track.thumbnailUrl))continue;
            candidates.computeIfAbsent(track.videoId,ignored->new ArrayList<>()).add(track);
        }
        int updated=0;
        for(Map.Entry<String,NativeTrack> entry:queued.entrySet()){
            NativeTrack old=entry.getValue();
            if(old==null||!old.thumbnailUrl.isEmpty())continue;
            List<NativeTrack> matching=candidates.get(old.videoId);if(matching==null)continue;
            for(NativeTrack candidate:matching){
                if(!compatible(old.artist,candidate.artist)||!compatible(old.album,candidate.album))continue;
                entry.setValue(new NativeTrack(old.videoId,old.title,old.artist,old.album,
                        old.durationSeconds,candidate.thumbnailUrl,old.isVideoSource));
                updated++;break;
            }
        }
        return updated;
    }

    private static boolean compatible(String first,String second){
        return first.isEmpty()||second.isEmpty()||first.equalsIgnoreCase(second);
    }

    private static boolean validCover(String value){
        if(value.isEmpty())return false;
        try{
            URI uri=URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null
                    &&uri.getRawUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443);
        }catch(IllegalArgumentException invalid){return false;}
    }
}
