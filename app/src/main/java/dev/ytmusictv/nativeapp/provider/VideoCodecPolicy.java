package dev.ytmusictv.nativeapp.provider;

import java.util.Locale;

/** Conservative metadata checks before considering optional hardware VP9 playback. */
final class VideoCodecPolicy {
    private VideoCodecPolicy() { }

    // VP Codec ISO binding: omitted optional fields default to BT.709 SDR.
    // https://www.webmproject.org/vp9/mp4/#codecs-parameter-string
    static int verifiedVp9SdrLevel(String codec) {
        if (codec == null || !codec.matches("(?i)vp09(?:\\.[0-9]{2}){3}(?:(?:\\.[0-9]{2}){5})?")) return 0;
        String[] fields = codec.split("\\.");
        if (!fields[1].equals("00") || !fields[3].equals("08")) return 0;
        int level = Integer.parseInt(fields[2]);
        switch (level) {
            case 10: case 11: case 20: case 21: case 30: case 31:
            case 40: case 41: case 50: case 51: case 52: case 60: case 61: case 62: break;
            default: return 0;
        }
        if (fields.length == 9) {
            // Only explicit BT.709 SDR, 4:2:0, and a legal range flag are supported.
            if ((!fields[4].equals("00") && !fields[4].equals("01"))
                    || !fields[5].equals("01") || !fields[6].equals("01") || !fields[7].equals("01")
                    || (!fields[8].equals("00") && !fields[8].equals("01"))) return 0;
        }
        return level;
    }

    /** API 26-28 have no hardware flag: accept only known vendor decoder prefixes. */
    static boolean legacyHardwareDecoderName(String value) {
        if (value == null) return false;
        String name = value.toLowerCase(Locale.ROOT);
        if (name.contains(".sw.") || name.contains("software") || name.endsWith(".sw")) return false;
        for (String prefix : new String[]{"omx.rtk.", "omx.realtek.", "omx.mtk.", "omx.qcom.", "omx.exynos.",
                "omx.amlogic.", "omx.hisi.", "omx.nvidia.", "omx.intel.", "omx.rk."}) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
