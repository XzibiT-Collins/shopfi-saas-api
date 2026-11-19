package com.example.perfume_budget.utils;

import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.InvalidFileException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** API-03: the product image check decides by magic bytes and size, nothing the client declares. */
class ProductImagesTest {

    static byte[] pad(byte[] head, int len) {
        byte[] b = new byte[len];
        System.arraycopy(head, 0, b, 0, head.length);
        return b;
    }

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

    @Test
    void pngJpegAndWebpAreAccepted() {
        assertDoesNotThrow(() -> ProductImages.requireValid(pad(PNG, 64)));
        assertDoesNotThrow(() -> ProductImages.requireValid(pad(JPEG, 64)));
        assertDoesNotThrow(() -> ProductImages.requireValid(pad(WEBP, 64)));
    }

    @Test
    void otherTypesAreRefusedWhateverTheyAreCalled() {
        for (String body : new String[]{"<svg xmlns='http://www.w3.org/2000/svg'/>", "<html><script>1</script></html>", "%PDF-1.7", "GIF89a....", "plain"}) {
            assertThrows(BadRequestException.class, () -> ProductImages.requireValid(body.getBytes()), body);
        }
        assertThrows(BadRequestException.class, () -> ProductImages.requireValid(pad(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'}, 64)));
    }

    @Test
    void emptyAndNullAreRefused() {
        assertThrows(BadRequestException.class, () -> ProductImages.requireValid(new byte[0]));
        assertThrows(BadRequestException.class, () -> ProductImages.requireValid(null));
    }

    @Test
    void theSizeCapIsInclusiveAtFiveMegabytes() {
        assertDoesNotThrow(() -> ProductImages.requireValid(pad(PNG, (int) ProductImages.MAX_BYTES)));
        assertThrows(InvalidFileException.class, () -> ProductImages.requireValid(pad(PNG, (int) ProductImages.MAX_BYTES + 1)));
    }
}
