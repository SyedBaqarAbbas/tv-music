package dev.ytmusictv.nativeapp.provider;

import java.nio.charset.StandardCharsets;

/** Metadata-only gate over a bounded WebM prefix; never decodes or retains a video frame. */
final class WebmVp9Header {
    private static final int MAX_BYTES = 256 * 1024;
    private final byte[] bytes;
    private final int limit;
    private int elements;
    private int trackNumber, trackWidth, trackHeight, transfer, primaries, matrix, bits;
    private boolean videoTrack, encrypted, docType, colourPresent, hdr;

    private WebmVp9Header(byte[] bytes, int length) {
        this.bytes = bytes;
        this.limit = Math.min(length, bytes.length);
    }

    static boolean verify(byte[] prefix, int length, int width, int height) {
        if (prefix == null || length <= 0 || length > prefix.length || length > MAX_BYTES
                || width <= 0 || width > 4096 || height <= 1080 || height > 2160) return false;
        try { return new WebmVp9Header(prefix, length).parse(width, height); }
        catch (IllegalArgumentException | IndexOutOfBoundsException ignored) { return false; }
    }

    private boolean parse(int width, int height) {
        Element ebml = element(0, limit);
        if (ebml.id != 0x1A45DFA3L || ebml.unknown || ebml.end > limit) return false;
        for (int position = ebml.start; position < ebml.end;) {
            Element child = element(position, (int)ebml.end);
            requireComplete(child);
            if (child.id == 0x4282) docType = ascii(child).equals("webm");
            position = (int)child.end;
        }
        if (!docType) return false;
        int position = (int)ebml.end;
        Element segment = element(position, limit, Long.MAX_VALUE);
        if (segment.id != 0x18538067L) return false;
        int segmentEnd = (int)Math.min(segment.end, limit);
        for (position = segment.start; position < segmentEnd;) {
            if (Thread.currentThread().isInterrupted()) return false;
            Element child = element(position, segmentEnd, segment.end);
            if (child.id == 0x1654AE6BL) {
                requireComplete(child);
                tracks(child);
            } else if (child.id == 0x1F43B675L) {
                if (!videoTrack || encrypted || trackWidth != width || trackHeight != height
                        || hdr || (bits != 0 && bits != 8) || !sdrOrUnspecified(transfer)
                        || !sdrOrUnspecified(primaries) || !sdrOrUnspecified(matrix)) return false;
                return cluster(child, width, height);
            }
            if (child.unknown || child.end > segmentEnd) return false;
            position = (int)child.end;
        }
        return false;
    }

    private void tracks(Element tracks) {
        int entries = 0;
        for (int position = tracks.start; position < tracks.end;) {
            Element entry = element(position, (int)tracks.end);
            requireComplete(entry);
            if (entry.id == 0xAE) {
                if (++entries > 8) throw bad();
                track(entry);
            }
            position = (int)entry.end;
        }
    }

    private void track(Element entry) {
        int number = 0, type = 0, width = 0, height = 0;
        String codec = "";
        Element video = null;
        boolean contentEncoding = false;
        int seen = 0;
        for (int position = entry.start; position < entry.end;) {
            Element field = element(position, (int)entry.end);
            requireComplete(field);
            int flag = field.id == 0xD7 ? 1 : field.id == 0x83 ? 2 : field.id == 0x86 ? 4
                    : field.id == 0xE0 ? 8 : field.id == 0x6D80 ? 16 : field.id == 0x63A2 ? 32 : 0;
            if (flag != 0 && (seen & flag) != 0) throw bad();
            seen |= flag;
            if (field.id == 0xD7) number = unsigned(field);
            else if (field.id == 0x83) type = unsigned(field);
            else if (field.id == 0x86) codec = ascii(field);
            else if (field.id == 0xE0) video = field;
            else if (field.id == 0x6D80 || field.id == 0x63A2) contentEncoding = true;
            position = (int)field.end;
        }
        if (type != 1) return;
        if (videoTrack || number <= 0 || !codec.equals("V_VP9") || video == null) throw bad();
        videoTrack = true;
        encrypted = contentEncoding; // Reject compression/encryption and unverified codec-private overrides.
        seen = 0;
        for (int position = video.start; position < video.end;) {
            Element field = element(position, (int)video.end);
            requireComplete(field);
            int flag = field.id == 0xB0 ? 1 : field.id == 0xBA ? 2 : field.id == 0x55B0 ? 4 : 0;
            if (flag != 0 && (seen & flag) != 0) throw bad();
            seen |= flag;
            if (field.id == 0xB0) width = unsigned(field);
            else if (field.id == 0xBA) height = unsigned(field);
            else if (field.id == 0x55B0) colour(field);
            position = (int)field.end;
        }
        trackNumber = number; trackWidth = width; trackHeight = height;
    }

    private void colour(Element colour) {
        if (colourPresent) throw bad();
        colourPresent = true;
        int seen = 0;
        for (int position = colour.start; position < colour.end;) {
            Element field = element(position, (int)colour.end);
            requireComplete(field);
            int flag = field.id == 0x55B1 ? 1 : field.id == 0x55B2 ? 2 : field.id == 0x55BA ? 4
                    : field.id == 0x55BB ? 8 : field.id == 0x55D0 ? 16 : field.id == 0x55BC ? 32 : field.id == 0x55BD ? 64 : 0;
            if (flag != 0 && (seen & flag) != 0) throw bad();
            seen |= flag;
            if (field.id == 0x55B1) matrix = unsigned(field);
            else if (field.id == 0x55B2) bits = unsigned(field);
            else if (field.id == 0x55BA) transfer = unsigned(field);
            else if (field.id == 0x55BB) primaries = unsigned(field);
            else if (field.id == 0x55D0 || field.id == 0x55BC || field.id == 0x55BD) hdr = true;
            position = (int)field.end;
        }
    }

    private boolean cluster(Element cluster, int width, int height) {
        int end = (int)Math.min(cluster.end, limit);
        for (int position = cluster.start; position < end;) {
            Element block = element(position, end, cluster.end);
            if (block.id == 0xA3) {
                int[] positionRef = {block.start};
                long number = vint(positionRef, end, false);
                if (number == trackNumber) {
                    if (block.unknown || positionRef[0] + 3 > end) return false;
                    int flags = bytes[positionRef[0] + 2] & 255;
                    if ((flags & 0x80) == 0 || (flags & 0x06) != 0) return false;
                    int frameStart = positionRef[0] + 3;
                    // A range can end inside a large compressed frame. Its complete uncompressed
                    // profile/color/dimensions header must fit the block and received prefix.
                    int frameEnd = (int)Math.min(block.end, end);
                    return keyframe(frameStart, frameEnd, width, height);
                }
            } else if (block.id == 0xA0) return false; // Unsupported BlockGroup/lacing paths fail closed.
            if (block.unknown || block.end > end) return false;
            position = (int)block.end;
        }
        return false;
    }

    // VP9 bitstream specification sections6.2/6.2.2: profile0 implies8-bit4:2:0.
    // https://downloads.webmproject.org/docs/vp9/vp9-bitstream_superframe-and-uncompressed-header_v1.0.pdf
    private boolean keyframe(int start, int end, int width, int height) {
        BitReader reader = new BitReader(bytes, start, end);
        if (reader.read(2) != 2 || reader.read(1) != 0 || reader.read(1) != 0
                || reader.read(1) != 0 || reader.read(1) != 0) return false;
        reader.read(1); // show_frame
        reader.read(1); // error_resilient_mode
        if (reader.read(24) != 0x498342) return false;
        int colour = reader.read(3);
        if (colour < 1 || colour > 4) return false; // Unknown/BT.2020/RGB do not establish this SDR path.
        reader.read(1); // color_range
        if (reader.read(16) + 1 != width || reader.read(16) + 1 != height) return false;
        if (reader.read(1) != 0) {
            int renderWidth = reader.read(16) + 1, renderHeight = reader.read(16) + 1;
            if (renderWidth != width || renderHeight != height) return false;
        }
        return true;
    }

    private static boolean sdrOrUnspecified(int code) {
        // Unspecified container color is accepted only with the explicit SDR frame color checked below.
        return code == 0 || code == 2 || code == 1 || code == 5 || code == 6 || code == 7;
    }
    private int unsigned(Element value) {
        int length = (int)(value.end - value.start);
        if (length < 1 || length > 4) throw bad();
        long number = 0;
        for (int i = value.start; i < value.end; i++) number = (number << 8) | (bytes[i] & 255);
        if (number > Integer.MAX_VALUE) throw bad();
        return (int)number;
    }
    private String ascii(Element value) {
        if (value.end - value.start > 32) throw bad();
        return new String(bytes, value.start, (int)value.end - value.start, StandardCharsets.US_ASCII);
    }
    private void requireComplete(Element value) {
        if (value.unknown || value.end > limit) throw bad();
    }
    private Element element(int position, int parentEnd) {
        return element(position,parentEnd,parentEnd);
    }
    private Element element(int position, int parentEnd, long declaredParentEnd) {
        if (++elements > 2048 || position < 0 || position >= parentEnd) throw bad();
        int[] cursor = {position};
        long id = vint(cursor, parentEnd, true);
        int sizeStart = cursor[0];
        long size = vint(cursor, parentEnd, false);
        int sizeLength = cursor[0] - sizeStart;
        long unknown = (1L << (sizeLength * 7)) - 1;
        boolean indefinite = size == unknown;
        long end = indefinite ? declaredParentEnd : cursor[0] + size;
        if (end < cursor[0] || end > declaredParentEnd || (indefinite && id != 0x18538067L && id != 0x1F43B675L)) throw bad();
        if (end > parentEnd && (parentEnd != limit
                || (id != 0x18538067L && id != 0x1F43B675L && id != 0xA3))) throw bad();
        return new Element(id, cursor[0], end, indefinite);
    }
    private long vint(int[] cursor, int end, boolean id) {
        if (cursor[0] >= end || cursor[0] >= limit) throw bad();
        int first = bytes[cursor[0]++] & 255, mask = 128, length = 1;
        while (length <= 8 && (first & mask) == 0) { mask >>= 1; length++; }
        if (length > (id ? 4 : 8) || cursor[0] + length - 1 > end || cursor[0] + length - 1 > limit) throw bad();
        long value = id ? first : first & (mask - 1);
        for (int i = 1; i < length; i++) value = (value << 8) | (bytes[cursor[0]++] & 255);
        return value;
    }
    private static IllegalArgumentException bad() { return new IllegalArgumentException("Unsupported WebM prefix"); }
    private static final class Element {
        final long id, end; final int start; final boolean unknown;
        Element(long id, int start, long end, boolean unknown) { this.id=id;this.start=start;this.end=end;this.unknown=unknown; }
    }
    private static final class BitReader {
        final byte[] bytes; final int end; int bit;
        BitReader(byte[] bytes,int start,int end) { this.bytes=bytes;this.bit=start*8;this.end=end*8; }
        int read(int length) {
            if (length < 1 || length > 24 || bit + length > end) throw bad();
            int result=0;
            for(int i=0;i<length;i++,bit++) result=(result<<1)|((bytes[bit/8]>>(7-bit%8))&1);
            return result;
        }
    }
}
