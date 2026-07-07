package com.example.perfume_budget.utils;

import com.cloudinary.Cloudinary;
import com.example.perfume_budget.exception.BadRequestException;
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
    public String uploadProductImage(MultipartFile multipartFile){
        if(multipartFile.isEmpty()){
            throw new BadRequestException("Product Image is required");
        }
        Map<String, Object> options = new HashMap<>(Map.ofEntries());
        options.put("folder", "images");
        options.put("resource_type", "image");
        try{
            Map<String, Object> results = cloudinary.uploader().upload(multipartFile.getBytes(), options);
            String storageKey = (String) results.get("public_id");
            log.info("Uploaded image to cloudinary, Storage key: {}", storageKey);
            return (String) results.get("secure_url");
        }catch (IOException e){
            log.error("An error occurred during image upload to cloudinary: {}", e.getMessage());
            return "";
        }
    }
}
