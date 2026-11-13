package com.example.perfume_budget.theme;

/**
 * Identifies PNG / JPEG / WebP purely from magic bytes and reads pixel dimensions from the header (no image
 * decoding, so no decompression-bomb or codec attack surface here). Everything else, including SVG, GIF, AVIF
 * and PDF, is unrecognised.
 */
final class ImageSniffer {

    enum Type {
        PNG("image/png"), JPEG("image/jpeg"), WEBP("image/webp");

        final String mime;

        Type(String mime) {
            this.mime = mime;
        }
    }

    record Info(Type type, int width, int height) {
    }

    private ImageSniffer() {
    }

    /** @return type and dimensions, or {@code null} if not a well-formed PNG/JPEG/WebP header */
    static Info sniff(byte[] b) {
        if (b == null) {
            return null;
        }
        if (b.length >= 24 && u8(b, 0) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && u8(b, 4) == 0x0D && u8(b, 5) == 0x0A && u8(b, 6) == 0x1A && u8(b, 7) == 0x0A
                && b[12] == 'I' && b[13] == 'H' && b[14] == 'D' && b[15] == 'R') {
            return dims(Type.PNG, be32(b, 16), be32(b, 20));
        }
        if (b.length >= 4 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8 && u8(b, 2) == 0xFF) {
            return jpeg(b);
        }
        if (b.length >= 30 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return webp(b);
        }
        return null;
    }

    private static Info dims(Type t, long w, long h) {
        if (w < 1 || h < 1 || w > Integer.MAX_VALUE || h > Integer.MAX_VALUE) {
            return null;
        }
        return new Info(t, (int) w, (int) h);
    }

    private static Info jpeg(byte[] b) {
        int i = 2;
        while (i + 3 < b.length) {
            if (u8(b, i) != 0xFF) {
                return null;
            }
            int marker = u8(b, i + 1);
            if (marker == 0xFF) { // fill byte
                i++;
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;
                continue;
            }
            int len = (u8(b, i + 2) << 8) | u8(b, i + 3);
            if (len < 2) {
                return null;
            }
            boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                if (i + 8 >= b.length) {
                    return null;
                }
                int h = (u8(b, i + 5) << 8) | u8(b, i + 6);
                int w = (u8(b, i + 7) << 8) | u8(b, i + 8);
                return dims(Type.JPEG, w, h);
            }
            i += 2 + len;
        }
        return null;
    }

    private static Info webp(byte[] b) {
        String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        switch (chunk) {
            case "VP8 ":
                if (b.length >= 30 && u8(b, 23) == 0x9D && u8(b, 24) == 0x01 && u8(b, 25) == 0x2A) {
                    return dims(Type.WEBP, le16(b, 26) & 0x3FFF, le16(b, 28) & 0x3FFF);
                }
                return null;
            case "VP8L":
                if (u8(b, 20) == 0x2F) {
                    long bits = (u8(b, 21)) | ((long) u8(b, 22) << 8) | ((long) u8(b, 23) << 16) | ((long) u8(b, 24) << 24);
                    return dims(Type.WEBP, (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1);
                }
                return null;
            case "VP8X":
                long w = 1 + (u8(b, 24) | (u8(b, 25) << 8) | (u8(b, 26) << 16));
                long h = 1 + (u8(b, 27) | (u8(b, 28) << 8) | (u8(b, 29) << 16));
                return dims(Type.WEBP, w, h);
            default:
                return null;
        }
    }

    private static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    private static int le16(byte[] b, int i) {
        return u8(b, i) | (u8(b, i + 1) << 8);
    }

    private static long be32(byte[] b, int i) {
        return ((long) u8(b, i) << 24) | (u8(b, i + 1) << 16) | (u8(b, i + 2) << 8) | u8(b, i + 3);
    }
}
