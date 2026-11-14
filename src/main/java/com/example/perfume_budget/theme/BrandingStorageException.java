package com.example.perfume_budget.theme;

/** The image store (Cloudinary) failed. Nothing was persisted. Maps to HTTP 502. */
public class BrandingStorageException extends RuntimeException {
    public BrandingStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
