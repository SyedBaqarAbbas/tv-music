package dev.ytmusictv.nativeapp.playback;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import dev.ytmusictv.nativeapp.model.NativeStreamResolver;
import dev.ytmusictv.nativeapp.model.NativeTrack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Single application-thread queue writer; Media3's live playlist is authoritative. */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
public final class NativePlaybackEngine implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final NativeStreamResolver resolver;
    private final ThreadPoolExecutor resolutions = new ThreadPoolExecutor(1, 1, 15,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), new ThreadPoolExecutor.AbortPolicy());
    private final QueueCheckpoint storage;
    private final Map<String, NativeTrack> tracks = new HashMap<>();
    private final Random random = new Random();
    public final ExoPlayer player;
    private List<String> traversal = new ArrayList<>();
    private boolean shuffle, background, foreground, closed;
    private boolean videoVisible;
    private volatile boolean video;
    private String error = "";
    private String retryEntry = "";
    private long retryAt;
    private Runnable changed = () -> { };
    private ArrayList<Bundle> undoEntries;
    private long undoExpires;
    private boolean notificationPending;
    private boolean checkpointPending;
    private final Runnable checkpointFlush = () -> { checkpointPending = false; checkpoint(); };

    private final Runnable checkpointTick = new Runnable() {
        @Override public void run() {
            if (closed || !player.isPlaying()) return;
            checkpoint(); main.postDelayed(this, 30_000);
        }
    };

    public NativePlaybackEngine(Context context, NativeStreamResolver resolver) {
        this.context = context.getApplicationContext(); this.resolver = resolver;
        storage = new QueueCheckpoint(context);
        resolutions.allowCoreThreadTimeOut(true);
        MediaSource.Factory sources = new MediaSource.Factory() {
            @Override public MediaSource.Factory setDrmSessionManagerProvider(DrmSessionManagerProvider ignored) { return this; }
            @Override public MediaSource.Factory setLoadErrorHandlingPolicy(LoadErrorHandlingPolicy ignored) { return this; }
            @Override public int[] getSupportedTypes() { return new int[] {C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_DASH, C.CONTENT_TYPE_HLS}; }
            @Override public MediaSource createMediaSource(MediaItem item) {
                NativeTrack track = NativeTrack.fromBundle(item.mediaMetadata.extras);
                if (track == null) throw new IllegalArgumentException("Queue entry has no track metadata");
                return new ResolvingMediaSource(NativePlaybackEngine.this.context, item, track,
                        NativePlaybackEngine.this.resolver, resolutions, () -> video, () -> main.post(() -> {
                            if (closed || !video || !item.mediaId.equals(currentId())) return;
                            video = false; error = "Video is unavailable for this recording. Audio is playing.";
                            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build());
                            signal();
                        }));
            }
        };
        player = new ExoPlayer.Builder(this.context).setMediaSourceFactory(sources)
                .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build();
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            player.addAnalyticsListener(new AnalyticsListener() {
                private int decoderStarts, firstFrames, decoderStops;
                @Override public void onVideoDecoderInitialized(AnalyticsListener.EventTime time,
                        String name, long initializedAtMs, long initializationDurationMs) {
                    // Only a codec identifier and numeric diagnostics; no source or track data.
                    String codec = name == null ? "unknown" : name.replaceAll("[^A-Za-z0-9_.-]", "_");
                    if (codec.length() > 100) codec = codec.substring(0, 100);
                    Log.d("NativePlayback", "video-decoder started=" + (++decoderStarts)
                            + " codec=" + codec + " initMs=" + initializationDurationMs);
                }
                @Override public void onRenderedFirstFrame(AnalyticsListener.EventTime time,
                        Object output, long renderTimeMs) {
                    Log.d("NativePlayback", "video-first-frame count=" + (++firstFrames));
                }
                @Override public void onVideoDisabled(AnalyticsListener.EventTime time, DecoderCounters counters) {
                    Log.d("NativePlayback", "video-disabled count=" + (++decoderStops)
                            + " rendered=" + counters.renderedOutputBufferCount
                            + " dropped=" + counters.droppedBufferCount
                            + " skipped=" + counters.skippedOutputBufferCount);
                }
            });
        }
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override public void onTimelineChanged(Timeline timeline, int reason) { signal(); }
            @Override public void onMediaItemTransition(MediaItem item, int reason) { error = ""; signal(); }
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) { requestCheckpoint(); }
            @Override public void onIsPlayingChanged(boolean playing) {
                main.removeCallbacks(checkpointTick);
                if (playing) main.postDelayed(checkpointTick, 30_000);
                else requestCheckpoint();
                signal();
            }
            @Override public void onPlaybackStateChanged(int state) { signal(); }
            @Override public void onPlayerError(PlaybackException failure) { handleError(failure); }
        });
        restore();
        applyVideoSelection();
    }

    public void setOnChanged(Runnable listener) { changed = listener == null ? () -> { } : listener; }
    private void checkThread() {
        if (Looper.myLooper() != player.getApplicationLooper()) throw new IllegalStateException("Use the player application thread");
    }
    private String currentId() { MediaItem item = player.getCurrentMediaItem(); return item == null ? "" : item.mediaId; }
    private List<String> baseOrder() {
        ArrayList<String> ids = new ArrayList<>();
        for (int i = 0; i < player.getMediaItemCount(); i++) ids.add(player.getMediaItemAt(i).mediaId);
        return ids;
    }
    private int indexOf(String id) { return baseOrder().indexOf(id); }
    private List<String> order() {
        List<String> base = baseOrder();
        return shuffle ? QueueOrder.retainExisting(traversal, base) : base;
    }
    private void applyTraversal(List<String> desired) {
        List<String> base = baseOrder(); traversal = QueueOrder.retainExisting(desired, base);
        int[] indices = new int[traversal.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = base.indexOf(traversal.get(i));
        player.setShuffleOrder(new androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(indices, random.nextLong()));
        player.setShuffleModeEnabled(shuffle);
    }
    private MediaItem item(String id, NativeTrack track) {
        MediaMetadata.Builder metadata = new MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                .setAlbumTitle(track.album).setExtras(track.toBundle());
        if (track.thumbnailUrl.startsWith("https://")) metadata.setArtworkUri(Uri.parse(track.thumbnailUrl));
        return new MediaItem.Builder().setMediaId(id).setUri("native-queue://" + id)
                .setMediaMetadata(metadata.build()).build();
    }

    public Bundle handle(String action, Bundle args) {
        checkThread();
        if (closed) throw new IllegalStateException("Playback is closed");
        switch (action) {
            case NativePlaybackCommands.PLAY_NOW:
            case NativePlaybackCommands.PLAY_NEXT:
            case NativePlaybackCommands.ADD_END: add(action, NativeTrack.fromBundle(args.getBundle("track"))); break;
            case NativePlaybackCommands.PLAY_COLLECTION:
            case NativePlaybackCommands.PLAY_NEXT_COLLECTION:
            case NativePlaybackCommands.ADD_COLLECTION: addCollection(action, args); break;
            case NativePlaybackCommands.REFRESH_ARTWORK:
                // Keep every Media3 item/source and its playback position intact.
                // The snapshot/checkpoint read this metadata map independently.
                if (ArtworkRepair.fillMissing(tracks, CollectionBatch.read(args, 0, true)) == 0) return snapshot();
                break;
            case NativePlaybackCommands.MOVE: move(args.getString("entryId", ""), args.getString("beforeEntryId", "")); break;
            case NativePlaybackCommands.REMOVE: remove(args.getString("entryId", "")); break;
            case NativePlaybackCommands.CLEAR_UPCOMING: clearUpcoming(); break;
            case NativePlaybackCommands.UNDO: undo(); break;
            case NativePlaybackCommands.SET_VIDEO: setVideo(args.getBoolean("value")); break;
            case NativePlaybackCommands.SET_SHUFFLE:
                List<String> before = order(); shuffle = args.getBoolean("value");
                applyTraversal(shuffle ? QueueOrder.shuffleUpcoming(before, currentId(), random) : baseOrder()); break;
            case NativePlaybackCommands.SET_REPEAT: player.setRepeatMode(Math.max(0, Math.min(2, args.getInt("value")))); break;
            case NativePlaybackCommands.SET_BACKGROUND:
                background = args.getBoolean("value"); if (!background && !foreground) player.pause(); break;
            case NativePlaybackCommands.APP_FOREGROUND:
                foreground = args.getBoolean("value");
                if (!foreground) {
                    if (!background) player.pause();
                    videoVisible = false;
                }
                applyVideoSelection();
                break;
            case NativePlaybackCommands.VIDEO_VISIBLE:
                boolean visible = args.getBoolean("value");
                if (videoVisible == visible) return snapshot();
                videoVisible = visible;
                applyVideoSelection();
                return snapshot(); // Presentation visibility is transient, never checkpointed.
            case NativePlaybackCommands.RESTORE: if (player.getMediaItemCount() == 0) restore(); break;
            case NativePlaybackCommands.RETRY: error = ""; rebuildCurrent(); break;
            case NativePlaybackCommands.SNAPSHOT: return snapshot();
            default: throw new IllegalArgumentException("Unsupported playback action");
        }
        checkpoint(); signal(); return snapshot();
    }

    private void add(String action, NativeTrack track) {
        if (track == null) throw new IllegalArgumentException("Choose a track first");
        if (player.getMediaItemCount() >= QueueCheckpoint.MAX_ENTRIES) throw new IllegalArgumentException("Queue limit reached (200 entries)");
        String id = UUID.randomUUID().toString(); String current = currentId(); List<String> before = order();
        tracks.put(id, track);
        boolean append = action.equals(NativePlaybackCommands.ADD_END);
        int at = append || player.getMediaItemCount() == 0 ? player.getMediaItemCount() : player.getCurrentMediaItemIndex() + 1;
        player.addMediaItem(at, item(id, track));
        applyTraversal(append ? QueueOrder.append(before, id) : QueueOrder.insertNext(before, current, id));
        error = "";
        if (action.equals(NativePlaybackCommands.PLAY_NOW)) {
            player.seekTo(indexOf(id), 0); player.prepare(); player.play();
        }
    }

    private void addCollection(String action, Bundle args) {
        boolean replacing = NativePlaybackCommands.PLAY_COLLECTION.equals(action);
        boolean append = NativePlaybackCommands.ADD_COLLECTION.equals(action);
        List<NativeTrack> batch = CollectionBatch.read(args, player.getMediaItemCount(), replacing);
        ArrayList<String> ids = new ArrayList<>(batch.size());
        ArrayList<MediaItem> items = new ArrayList<>(batch.size());
        Map<String, NativeTrack> staged = new HashMap<>();
        for (NativeTrack track : batch) {
            String id;
            do { id = UUID.randomUUID().toString(); } while (tracks.containsKey(id) || staged.containsKey(id));
            ids.add(id); items.add(item(id, track)); staged.put(id, track);
        }
        List<String> desired = replacing ? ids : append ? QueueOrder.appendCollection(order(), ids)
                : QueueOrder.insertNextCollection(order(), currentId(), ids);
        // Every track, capacity and order decision above has passed before any
        // live queue change. Media3 receives one playlist mutation for the batch.
        if (replacing) {
            tracks.clear(); tracks.putAll(staged);
            player.setMediaItems(items, 0, 0);
            applyTraversal(desired);
            undoEntries = null; error = ""; retryEntry = ""; retryAt = 0;
            player.prepare(); player.play();
        } else {
            int at = append || player.getMediaItemCount() == 0 ? player.getMediaItemCount()
                    : player.getCurrentMediaItemIndex() + 1;
            tracks.putAll(staged);
            player.addMediaItems(at, items);
            applyTraversal(desired);
        }
    }

    private void move(String id, String beforeId) {
        List<String> desired = QueueOrder.moveUpcoming(order(), currentId(), id, beforeId);
        if (!shuffle) {
            int from = indexOf(id);
            int destination = beforeId.isEmpty() ? player.getMediaItemCount() : indexOf(beforeId);
            if (from < destination) destination--;
            player.moveMediaItem(from, destination);
        }
        applyTraversal(desired);
    }
    private void remove(String id) {
        int index = indexOf(id); List<String> before = order();
        if (index < 0 || before.indexOf(id) <= before.indexOf(currentId())) throw new IllegalArgumentException("Only upcoming entries can be removed");
        NativeTrack track = tracks.get(id);
        if (track == null) return;
        undoEntries = new ArrayList<>();
        int place = before.indexOf(id);
        undoEntries.add(undoEntry(id, track, place + 1 < before.size() ? before.get(place + 1) : ""));
        undoExpires = SystemClock.elapsedRealtime() + 15_000;
        player.removeMediaItem(index); tracks.remove(id); before.remove(id); applyTraversal(before);
    }
    private Bundle undoEntry(String id, NativeTrack track, String before) {
        Bundle row = new Bundle(); row.putString("entryId", id); row.putBundle("track", track.toBundle());
        row.putString("before", before); return row;
    }
    private void clearUpcoming() {
        List<String> before = order(), keep = QueueOrder.clearUpcoming(before, currentId());
        if (keep.size() == before.size()) return;
        HashSet<String> retained = new HashSet<>(keep);
        undoEntries = new ArrayList<>();
        for (int i = keep.size(); i < before.size(); i++) {
            String id = before.get(i); NativeTrack track = tracks.get(id);
            if (track != null) undoEntries.add(undoEntry(id, track, i + 1 < before.size() ? before.get(i + 1) : ""));
        }
        undoExpires = SystemClock.elapsedRealtime() + 15_000;
        // Remove contiguous base-order ranges backwards; current/history never move.
        for (int end = player.getMediaItemCount(); end > 0;) {
            if (retained.contains(player.getMediaItemAt(end - 1).mediaId)) { end--; continue; }
            int start = end - 1;
            while (start > 0 && !retained.contains(player.getMediaItemAt(start - 1).mediaId)) start--;
            player.removeMediaItems(start, end); end = start;
        }
        tracks.keySet().retainAll(retained); applyTraversal(keep);
    }
    private void undo() {
        if (undoEntries == null || SystemClock.elapsedRealtime() > undoExpires) {
            undoEntries = null;
            throw new IllegalArgumentException("Undo has expired. The queue is unchanged.");
        }
        if (player.getMediaItemCount() + undoEntries.size() > QueueCheckpoint.MAX_ENTRIES) {
            throw new IllegalArgumentException("The queue is full. Undo cannot restore these entries yet.");
        }
        List<String> before = order();
        for (int i = undoEntries.size() - 1; i >= 0; i--) {
            Bundle row = undoEntries.get(i); String id = row.getString("entryId", "");
            NativeTrack track = NativeTrack.fromBundle(row.getBundle("track"));
            if (track == null || indexOf(id) >= 0 || player.getMediaItemCount() >= QueueCheckpoint.MAX_ENTRIES) continue;
            String anchorId = row.getString("before", ""); int anchor = before.indexOf(anchorId);
            int current = before.indexOf(currentId()); if (anchor <= current) anchor = before.size();
            before.add(anchor < 0 ? before.size() : anchor, id); tracks.put(id, track);
            int at = anchorId.isEmpty() ? player.getMediaItemCount() : indexOf(anchorId);
            if (at < 0 || at <= player.getCurrentMediaItemIndex()) at = player.getMediaItemCount();
            player.addMediaItem(at, item(id, track));
        }
        applyTraversal(before); undoEntries = null;
    }

    private void setVideo(boolean wanted) {
        if (video == wanted) return;
        video = wanted;
        applyVideoSelection();
        rebuildCurrent();
    }
    private void applyVideoSelection() {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !video || !foreground || !videoVisible).build());
    }
    private void rebuildCurrent() {
        if (player.getMediaItemCount() == 0) return;
        ArrayList<MediaItem> items = new ArrayList<>();
        for (int i = 0; i < player.getMediaItemCount(); i++) items.add(player.getMediaItemAt(i));
        int index = player.getCurrentMediaItemIndex(); long position = player.getCurrentPosition();
        boolean playing = player.getPlayWhenReady(); List<String> before = order();
        player.setMediaItems(items, index, Math.max(0, position)); applyTraversal(before);
        player.prepare(); player.setPlayWhenReady(playing);
    }
    private void handleError(PlaybackException failure) {
        String id = currentId(); long now = SystemClock.elapsedRealtime();
        boolean expired = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                int code = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                expired = code == 403 || code == 410; break;
            }
        }
        if (expired && (!id.equals(retryEntry) || now - retryAt > 60_000)) {
            retryEntry = id; retryAt = now;
            main.post(() -> { if (!closed && id.equals(currentId())) rebuildCurrent(); });
        } else {
            error = "Playback could not continue. Retry this track or skip to the next."; signal();
        }
    }

    public Bundle snapshot() {
        checkThread(); Bundle result = new Bundle(); ArrayList<Bundle> entries = new ArrayList<>();
        String current = currentId(); List<String> order = order(); int currentIndex = order.indexOf(current);
        for (int i = 0; i < order.size(); i++) {
            String id = order.get(i); NativeTrack track = tracks.get(id); if (track == null) continue;
            Bundle row = new Bundle(); row.putString("entryId", id); row.putBundle("track", track.toBundle());
            row.putBoolean("current", id.equals(current)); row.putBoolean("past", currentIndex >= 0 && i < currentIndex); entries.add(row);
        }
        result.putParcelableArrayList("entries", entries); result.putString("currentId", current);
        result.putStringArrayList("baseOrder", new ArrayList<>(baseOrder()));
        result.putBoolean("video", video); result.putBoolean("shuffle", shuffle); result.putBoolean("background", background);
        result.putInt("repeat", player.getRepeatMode()); result.putString("error", error);
        result.putBoolean("canUndo", undoEntries != null && !undoEntries.isEmpty() && SystemClock.elapsedRealtime() <= undoExpires);
        return result;
    }
    private void requestCheckpoint() {
        if (closed || checkpointPending) return;
        checkpointPending = true;
        main.postDelayed(checkpointFlush, 500);
    }
    public void checkpoint() {
        if (closed) return;
        main.removeCallbacks(checkpointFlush); checkpointPending = false;
        storage.save(snapshot(), player.getCurrentPosition());
    }
    private void restore() {
        Bundle saved = storage.load(); ArrayList<Bundle> entries = saved.getParcelableArrayList("entries");
        if (entries == null || entries.isEmpty()) return;
        ArrayList<MediaItem> items = new ArrayList<>(); HashSet<String> seen = new HashSet<>();
        ArrayList<String> savedTraversal = new ArrayList<>();
        for (Bundle row : entries) {
            String id = row.getString("entryId", ""); NativeTrack track = NativeTrack.fromBundle(row.getBundle("track"));
            if (track == null || !seen.add(id)) continue;
            tracks.put(id, track); savedTraversal.add(id);
        }
        if (savedTraversal.isEmpty()) return;
        ArrayList<String> savedBase = saved.getStringArrayList("baseOrder");
        List<String> base = QueueOrder.retainExisting(savedBase == null ? savedTraversal : savedBase, savedTraversal);
        for (String id : base) items.add(item(id, tracks.get(id)));
        video = saved.getBoolean("video"); shuffle = saved.getBoolean("shuffle"); background = saved.getBoolean("background");
        int current = 0; for (int i = 0; i < items.size(); i++) if (items.get(i).mediaId.equals(saved.getString("currentId"))) current = i;
        player.setMediaItems(items, current, saved.getLong("position", 0));
        traversal = savedTraversal; applyTraversal(traversal); player.setRepeatMode(saved.getInt("repeat", 0));
        player.setPlayWhenReady(false); // Restore intent without a network request or unexpected autoplay.
    }
    private void signal() {
        if (notificationPending || closed) return;
        notificationPending = true;
        main.post(() -> {
            notificationPending = false; if (closed) return;
            HashSet<String> live = new HashSet<>(baseOrder()); tracks.keySet().retainAll(live);
            traversal = QueueOrder.retainExisting(traversal, baseOrder());
            changed.run(); requestCheckpoint();
        });
    }
    @Override public void close() {
        if (closed) return; checkpoint(); closed = true; main.removeCallbacksAndMessages(null);
        player.release(); resolutions.shutdownNow(); tracks.clear();
    }
}
