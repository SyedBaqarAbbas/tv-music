package dev.ytmusictv.nativeapp.playback;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.source.CompositeMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.upstream.Allocator;
import dev.ytmusictv.nativeapp.model.NativeStreamResolver;
import dev.ytmusictv.nativeapp.model.NativeTrack;
import dev.ytmusictv.nativeapp.model.PlaybackSource;
import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;

/** Resolve only a prepared entry, on one bounded worker; attach on playback looper. */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
final class ResolvingMediaSource extends CompositeMediaSource<Void> {
    private final Context context;
    private final MediaItem item;
    private final NativeTrack track;
    private final NativeStreamResolver resolver;
    private final ThreadPoolExecutor worker;
    private final BooleanSupplier videoMode;
    private final Runnable videoUnavailable;
    private MediaSource child;
    private Future<?> pending;
    private IOException failure;
    private long generation;

    ResolvingMediaSource(Context context, MediaItem item, NativeTrack track, NativeStreamResolver resolver,
            ThreadPoolExecutor worker, BooleanSupplier videoMode, Runnable videoUnavailable) {
        this.context = context; this.item = item; this.track = track; this.resolver = resolver;
        this.worker = worker; this.videoMode = videoMode;
        this.videoUnavailable = videoUnavailable;
    }
    @Override public MediaItem getMediaItem() { return item; }

    @Override protected void prepareSourceInternal(TransferListener listener) {
        super.prepareSourceInternal(listener);
        Handler playback = new Handler(Looper.myLooper());
        long expected = ++generation;
        boolean wantsVideo = videoMode.getAsBoolean();
        try {
            pending = worker.submit(() -> {
                try {
                    PlaybackSource source;
                    boolean fallback = false;
                    try { source = resolver.resolve(track, wantsVideo); }
                    catch (Exception unavailable) {
                        if (!wantsVideo || Thread.currentThread().isInterrupted()) throw unavailable;
                        source = resolver.resolve(track, false); fallback = true;
                    }
                    // Avoid preparing a URL that is already expired or about to expire.
                    if (source != null && source.expiresAtMillis > 0
                            && source.expiresAtMillis <= System.currentTimeMillis() + 5000) {
                        source = resolver.resolve(track, wantsVideo && !fallback);
                    }
                    final PlaybackSource resolved = source;
                    final boolean audioFallback = fallback || (wantsVideo && source != null && !source.hasVideo());
                    if (Thread.currentThread().isInterrupted()) return;
                    if (resolved == null || (resolved.audioUrl.isEmpty() && resolved.videoUrl.isEmpty())) {
                        throw new IOException("No playable source is available for this track");
                    }
                    playback.post(() -> {
                        if (expected != generation) return;
                        try {
                            if (audioFallback) videoUnavailable.run();
                            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                                    .setConnectTimeoutMs(10000).setReadTimeoutMs(15000)
                                    .setAllowCrossProtocolRedirects(false).setDefaultRequestProperties(resolved.headers);
                            DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(new DefaultDataSource.Factory(context, http));
                            MediaSource audio = resolved.audioUrl.isEmpty() ? null
                                    : factory.createMediaSource(sourceItem(resolved.audioUrl, resolved.audioMimeType));
                            MediaSource video = !wantsVideo || resolved.videoUrl.isEmpty() ? null
                                    : factory.createMediaSource(sourceItem(resolved.videoUrl, resolved.videoMimeType));
                            child = audio != null && video != null ? new MergingMediaSource(true, audio, video)
                                    : audio != null ? audio : video;
                            if (child == null) throw new IOException("Audio-only playback is unavailable for this source");
                            prepareChildSource(null, child);
                        } catch (Exception error) { failure = safeFailure(error); }
                    });
                } catch (Exception error) {
                    playback.post(() -> { if (expected == generation) failure = safeFailure(error); });
                }
            });
        } catch (RuntimeException busy) { failure = new IOException("Source resolution is busy. Try again."); }
    }

    private MediaItem sourceItem(String url, String mime) throws IOException {
        Uri uri = Uri.parse(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException("The source did not provide a secure media URL");
        }
        return item.buildUpon().setUri(uri).setMimeType(mime == null || mime.isEmpty() ? null : mime).build();
    }
    private static IOException safeFailure(Exception error) {
        // Provider exceptions can contain signed URLs; never surface/log their raw messages.
        return new IOException("The track source could not be loaded. Retry or choose another track.");
    }
    @Override public void maybeThrowSourceInfoRefreshError() throws IOException {
        if (failure != null) throw failure;
        super.maybeThrowSourceInfoRefreshError();
    }
    @Override protected void onChildSourceInfoRefreshed(Void id, MediaSource source, Timeline timeline) {
        refreshSourceInfo(timeline);
    }
    @Override public MediaPeriod createPeriod(MediaSource.MediaPeriodId id, Allocator allocator, long positionUs) {
        if (child == null) throw new IllegalStateException("Source has not been resolved");
        return child.createPeriod(id, allocator, positionUs);
    }
    @Override public void releasePeriod(MediaPeriod period) { if (child != null) child.releasePeriod(period); }
    @Override protected void releaseSourceInternal() {
        generation++;
        if (pending != null) {
            pending.cancel(true);
            if (pending instanceof Runnable) worker.remove((Runnable) pending);
            pending = null;
        }
        super.releaseSourceInternal(); child = null; failure = null;
    }
}
