package dev.ytmusictv.nativeapp.provider;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Queries capabilities only, never creates a decoder or uses display/UI resolution as a limit. */
final class HardwareVideoSupport implements YouTubeStreamResolver.DecoderSupport {
    private static final String MIME = "video/x-vnd.on2.vp9";
    private List<MediaCodecInfo.CodecCapabilities> decoders;

    @Override public boolean supports(int width, int height, int fps, int vp9Level) {
        if (width <= 0 || height <= 0 || fps <= 0 || fps > 30) return false;
        int level = androidVp9Level(vp9Level);
        if (vp9Level != 0 && level == 0) return false;
        for (MediaCodecInfo.CodecCapabilities decoder : decoders()) {
            try {
                boolean profileSupported = false;
                for (MediaCodecInfo.CodecProfileLevel profile : decoder.profileLevels) {
                    if (profile.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile0 && (vp9Level == 0 || profile.level >= level)) {
                        profileSupported = true;
                        break;
                    }
                }
                MediaCodecInfo.VideoCapabilities video = decoder.getVideoCapabilities();
                if (!profileSupported || video == null) continue;
                // Codec blocks align the decoded frame; visible cropped dimensions may be unaligned.
                int alignedWidth = alignDimension(width, video.getWidthAlignment());
                int alignedHeight = alignDimension(height, video.getHeightAlignment());
                if (alignedWidth == 0 || alignedHeight == 0
                        || !video.areSizeAndRateSupported(alignedWidth, alignedHeight, fps)) continue;
                MediaFormat format = MediaFormat.createVideoFormat(MIME, alignedWidth, alignedHeight);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.VP9Profile0);
                if (vp9Level != 0) format.setInteger(MediaFormat.KEY_LEVEL, level);
                if (decoder.isFormatSupported(format)) return true;
            } catch (RuntimeException ignored) { /* Vendor capability errors fail closed. */ }
        }
        return false;
    }

    private List<MediaCodecInfo.CodecCapabilities> decoders() {
        if (decoders != null) return decoders;
        List<MediaCodecInfo.CodecCapabilities> found = new ArrayList<>();
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) continue;
                boolean hardware = Build.VERSION.SDK_INT >= 29
                        ? info.isHardwareAccelerated() && !info.isSoftwareOnly()
                        : VideoCodecPolicy.legacyHardwareDecoderName(info.getName());
                if (!hardware) continue;
                for (String type : info.getSupportedTypes()) if (MIME.equalsIgnoreCase(type)) {
                    try { found.add(info.getCapabilitiesForType(type)); }
                    catch (RuntimeException ignored) { }
                    break;
                }
            }
        } catch (RuntimeException | LinkageError ignored) { /* Unsupported enumeration uses AVC fallback. */ }
        decoders = Collections.unmodifiableList(found);
        return decoders;
    }

    static int alignDimension(int value, int alignment) {
        if (value <= 0 || alignment <= 0) return 0;
        long aligned = ((long) value + alignment - 1) / alignment * alignment;
        return aligned <= Integer.MAX_VALUE ? (int) aligned : 0;
    }

    private static int androidVp9Level(int value) {
        switch (value) {
            case 10: return MediaCodecInfo.CodecProfileLevel.VP9Level1;
            case 11: return MediaCodecInfo.CodecProfileLevel.VP9Level11;
            case 20: return MediaCodecInfo.CodecProfileLevel.VP9Level2;
            case 21: return MediaCodecInfo.CodecProfileLevel.VP9Level21;
            case 30: return MediaCodecInfo.CodecProfileLevel.VP9Level3;
            case 31: return MediaCodecInfo.CodecProfileLevel.VP9Level31;
            case 40: return MediaCodecInfo.CodecProfileLevel.VP9Level4;
            case 41: return MediaCodecInfo.CodecProfileLevel.VP9Level41;
            case 50: return MediaCodecInfo.CodecProfileLevel.VP9Level5;
            case 51: return MediaCodecInfo.CodecProfileLevel.VP9Level51;
            case 52: return MediaCodecInfo.CodecProfileLevel.VP9Level52;
            case 60: return MediaCodecInfo.CodecProfileLevel.VP9Level6;
            case 61: return MediaCodecInfo.CodecProfileLevel.VP9Level61;
            case 62: return MediaCodecInfo.CodecProfileLevel.VP9Level62;
            default: return 0;
        }
    }
}
