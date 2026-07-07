package com.example.perfume_budget.utils;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.example.perfume_budget.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CloudinaryFileUploadUtilTest {

    @Mock
    private Cloudinary cloudinary;
    @Mock
    private Uploader uploader;
    @Mock
    private MultipartFile multipartFile;

    @InjectMocks
    private CloudinaryFileUploadUtil cloudinaryFileUploadUtil;

    @Test
    void uploadProductImage_Success() throws IOException {
        when(multipartFile.isEmpty()).thenReturn(false);
        when(multipartFile.getBytes()).thenReturn(new byte[]{1, 2, 3});
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap())).thenReturn(Map.of("public_id", "id1", "secure_url", "http://url.com"));

        String result = cloudinaryFileUploadUtil.uploadProductImage(multipartFile);

        assertEquals("http://url.com", result);
    }

    @Test
    void uploadProductImage_EmptyFile_ThrowsException() {
        when(multipartFile.isEmpty()).thenReturn(true);
        assertThrows(BadRequestException.class, () -> cloudinaryFileUploadUtil.uploadProductImage(multipartFile));
    }

    @Test
    void uploadProductImage_IOException_ReturnsEmptyString() throws IOException {
        when(multipartFile.isEmpty()).thenReturn(false);
        when(multipartFile.getBytes()).thenThrow(new IOException("test"));

        String result = cloudinaryFileUploadUtil.uploadProductImage(multipartFile);

        assertEquals("", result);
    }
}
