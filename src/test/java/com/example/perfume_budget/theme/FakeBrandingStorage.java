package com.example.perfume_budget.theme;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory {@link BrandingStorage}: no network, no keys. */
class FakeBrandingStorage implements BrandingStorage {

    final String cloud;
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final List<String> uploads = new CopyOnWriteArrayList<>();
    final List<String> deletes = new CopyOnWriteArrayList<>();
    volatile boolean failUploads;

    FakeBrandingStorage(String cloud) {
        this.cloud = cloud;
    }

    @Override
    public String upload(byte[] bytes, String folder, String name) {
        if (failUploads) {
            throw new BrandingStorageException("fake store down", null);
        }
        String key = folder + "/" + name;
        objects.put(key, bytes);
        uploads.add(key);
        return "https://res.cloudinary.com/" + cloud + "/image/upload/v1/" + key + ".png";
    }

    @Override
    public void delete(String folder, String name) {
        String key = folder + "/" + name;
        objects.remove(key);
        deletes.add(key);
    }

    void reset() {
        objects.clear();
        uploads.clear();
        deletes.clear();
        failUploads = false;
    }

    /** Minimal valid image headers for the sniffer. */
    static byte[] png(int w, int h) {
        byte[] b = new byte[32];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, b, 0, 8);
        b[11] = 13;
        b[12] = 'I'; b[13] = 'H'; b[14] = 'D'; b[15] = 'R';
        be32(b, 16, w);
        be32(b, 20, h);
        return b;
    }

    static byte[] jpeg(int w, int h) {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 4, 0, 0,
                (byte) 0xFF, (byte) 0xC0, 0, 11, 8, (byte) (h >> 8), (byte) h, (byte) (w >> 8), (byte) w, 1, 1, 0x11, 0};
    }

    static byte[] webpLossy(int w, int h) {
        byte[] b = new byte[40];
        System.arraycopy("RIFF".getBytes(), 0, b, 0, 4);
        System.arraycopy("WEBP".getBytes(), 0, b, 8, 4);
        System.arraycopy("VP8 ".getBytes(), 0, b, 12, 4);
        b[23] = (byte) 0x9D; b[24] = 0x01; b[25] = 0x2A;
        b[26] = (byte) w; b[27] = (byte) (w >> 8);
        b[28] = (byte) h; b[29] = (byte) (h >> 8);
        return b;
    }

    private static void be32(byte[] b, int i, int v) {
        b[i] = (byte) (v >>> 24); b[i + 1] = (byte) (v >>> 16); b[i + 2] = (byte) (v >>> 8); b[i + 3] = (byte) v;
    }

    List<String> uploadsCopy() {
        return new ArrayList<>(uploads);
    }
}
