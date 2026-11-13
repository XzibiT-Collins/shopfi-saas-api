package com.example.perfume_budget.theme;

import com.cloudinary.Cloudinary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Cloudinary-backed {@link BrandingStorage}. Callers pass a folder derived server-side, never client input. */
@Slf4j
@Component
@RequiredArgsConstructor
public class CloudinaryBrandingStorage implements BrandingStorage {

    private final Cloudinary cloudinary;

    @Override
    public String upload(byte[] bytes, String folder, String name) {
        Map<String, Object> options = new HashMap<>();
        options.put("public_id", folder + "/" + name);
        options.put("resource_type", "image");
        options.put("overwrite", true);
        options.put("invalidate", true);
        options.put("allowed_formats", "png,jpg,webp");
        try {
            Map<?, ?> result = cloudinary.uploader().upload(bytes, options);
            Object url = result.get("secure_url");
            if (!(url instanceof String s) || s.isBlank()) {
                throw new BrandingStorageException("Image store returned no URL", null);
            }
            return s;
        } catch (BrandingStorageException e) {
            throw e;
        } catch (Exception e) {
            log.error("Branding upload failed for {}/{}: {}", folder, name, e.getClass().getSimpleName());
            throw new BrandingStorageException("Image upload failed", e);
        }
    }

    @Override
    public void delete(String folder, String name) {
        try {
            Map<String, Object> options = new HashMap<>();
            options.put("resource_type", "image");
            options.put("invalidate", true);
            cloudinary.uploader().destroy(folder + "/" + name, options);
        } catch (Exception e) {
            log.warn("Best-effort branding delete failed for {}/{}: {}", folder, name, e.getClass().getSimpleName());
        }
    }
}
