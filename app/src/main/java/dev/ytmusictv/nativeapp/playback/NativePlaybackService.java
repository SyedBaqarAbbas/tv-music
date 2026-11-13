package dev.ytmusictv.nativeapp.playback;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.Player;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;
import androidx.media3.session.SessionError;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import dev.ytmusictv.nativeapp.provider.YouTubeStreamResolver;

/** One in-process player/session, with standard Android media-key handling. */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
public final class NativePlaybackService extends MediaSessionService {
    private NativePlaybackEngine engine;
    private MediaSession session;

    @Override public void onCreate() {
        super.onCreate();
        engine = new NativePlaybackEngine(this, new YouTubeStreamResolver());
        // Session Previous always selects a previous entry; it does not turn
        // into "restart current" after a playback-position threshold.
        Player transports = new ForwardingPlayer(engine.player) {
            @Override public void play() {
                if (getPlaybackState() == Player.STATE_IDLE && getMediaItemCount() > 0) prepare();
                super.play();
            }
            @Override public void seekToPrevious() { if (hasPreviousMediaItem()) seekToPreviousMediaItem(); }
            @Override public void seekToNext() { if (hasNextMediaItem()) seekToNextMediaItem(); }
        };
        MediaSession.Builder builder = new MediaSession.Builder(this, transports).setCallback(new SessionCallbacks());
        Intent launch = new Intent().setClassName(getPackageName(),
                "dev.ytmusictv.nativeapp.ui.NativeTvActivity")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        builder.setSessionActivity(PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        session = builder.build();
        engine.setOnChanged(() -> {
            if (session != null) session.broadcastCustomCommand(
                    NativePlaybackCommands.command(NativePlaybackCommands.QUEUE_CHANGED), engine.snapshot());
        });
    }

    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) { return session; }

    private final class SessionCallbacks implements MediaSession.Callback {
        @Override public MediaSession.ConnectionResult onConnect(MediaSession session, MediaSession.ControllerInfo controller) {
            MediaSession.ConnectionResult defaults = MediaSession.Callback.super.onConnect(session, controller);
            SessionCommands.Builder commands = defaults.availableSessionCommands.buildUpon();
            if (getPackageName().equals(controller.getPackageName())) {
                for (String action : NativePlaybackCommands.ACTIONS) commands.add(NativePlaybackCommands.command(action));
            }
            Player.Commands allowed = defaults.availablePlayerCommands.buildUpon()
                    .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM)
                    .remove(Player.COMMAND_SET_SHUFFLE_MODE).build();
            return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands.build()).setAvailablePlayerCommands(allowed).build();
        }

        @Override public ListenableFuture<SessionResult> onCustomCommand(MediaSession session,
                MediaSession.ControllerInfo controller, SessionCommand command, Bundle args) {
            if (!getPackageName().equals(controller.getPackageName())) {
                return Futures.immediateFuture(new SessionResult(SessionError.ERROR_PERMISSION_DENIED));
            }
            try {
                Bundle state = engine.handle(command.customAction, args);
                return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS, state));
            } catch (IllegalArgumentException invalid) {
                Bundle error = engine.snapshot(); error.putString("error", invalid.getMessage());
                return Futures.immediateFuture(new SessionResult(SessionError.ERROR_BAD_VALUE, error));
            } catch (IllegalStateException unavailable) {
                return Futures.immediateFuture(new SessionResult(SessionError.ERROR_INVALID_STATE));
            }
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (engine != null) {
            engine.handle(NativePlaybackCommands.APP_FOREGROUND, NativePlaybackCommands.boolArgs(false));
            engine.checkpoint();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        if (session != null) { session.release(); session = null; }
        if (engine != null) { engine.close(); engine = null; }
        super.onDestroy();
    }
}
