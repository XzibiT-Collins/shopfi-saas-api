package com.example.perfume_budget.utils;

import com.cloudinary.Cloudinary;
import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.exception.BadRequestException;
import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CloudinaryFileUploadUtil {
    private final Cloudinary cloudinary;
    public static final String IMAGE_UPLOAD_FAILED = "IMAGE_UPLOAD_FAILED";

    private static ApiCodeException uploadFailed() {
        return new ApiCodeException(HttpStatus.BAD_GATEWAY, IMAGE_UPLOAD_FAILED, "Image storage is unavailable; nothing was saved");
    }

    public String uploadProductImage(MultipartFile multipartFile){
        if(multipartFile.isEmpty()){
            throw new BadRequestException("Product Image is required");
        }
        Map<String, Object> options = new HashMap<>(Map.ofEntries());
        options.put("folder", "images");
        options.put("resource_type", "image");
        try{
            byte[] bytes = multipartFile.getBytes();
            ProductImages.requireValid(bytes);
            Map<String, Object> results = cloudinary.uploader().upload(bytes, options);
            String storageKey = (String) results.get("public_id");
            log.info("Uploaded image to cloudinary, Storage key: {}", storageKey);
            String url = (String) results.get("secure_url");
            if (url == null || url.isBlank()) {
                throw uploadFailed();
            }
            return url;
        }catch (IOException e){
            // API-03: used to return "" and the product was saved with an empty image url
            log.error("An error occurred during image upload to cloudinary: {}", e.getClass().getName());
            throw uploadFailed();
        }
    }
}
