package com.example.perfume_budget.theme;

/**
 * Image store for tenant branding. A mockable collaborator: production uses Cloudinary, tests use a fake
 * (no network, no keys).
 */
public interface BrandingStorage {

    /**
     * Stores {@code bytes} at {@code <folder>/<name>} (overwriting) and returns the HTTPS delivery URL.
     *
     * @throws BrandingStorageException when the store fails
     */
    String upload(byte[] bytes, String folder, String name);

    /** Best-effort removal of {@code <folder>/<name>}; implementations must not throw for missing assets. */
    void delete(String folder, String name);
}
