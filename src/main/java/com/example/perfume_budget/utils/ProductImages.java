package com.example.perfume_budget.utils;

import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.InvalidFileException;

/**
 * API-03: validation of a product image before it is sent to storage. The type is decided from the magic bytes (PNG,
 * JPEG, WebP; never the client's file name or declared content type, so an SVG, HTML or PDF renamed to .jpg is refused),
 * the size is capped at {@value #MAX_BYTES} bytes. No decoding happens here.
 */
public final class ProductImages {

    public static final long MAX_BYTES = 5L * 1024 * 1024;
    public static final String UNSUPPORTED_TYPE = "Product image must be a PNG, JPEG or WebP image";
    public static final String TOO_LARGE = "Product image must be at most 5 MB";

    private ProductImages() {
    }

    /** @throws InvalidFileException (413) over the size cap; @throws BadRequestException (400) not a PNG, JPEG or WebP */
    public static void requireValid(byte[] b) {
        if (b == null || b.length == 0) {
            throw new BadRequestException("Product Image is required");
        }
        if (b.length > MAX_BYTES) {
            throw new InvalidFileException(TOO_LARGE);
        }
        if (!(isPng(b) || isJpeg(b) || isWebp(b))) {
            throw new BadRequestException(UNSUPPORTED_TYPE);
        }
    }

    private static boolean isPng(byte[] b) {
        return b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A;
    }

    private static boolean isJpeg(byte[] b) {
        return b.length >= 4 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    private static boolean isWebp(byte[] b) {
        return b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }
}
