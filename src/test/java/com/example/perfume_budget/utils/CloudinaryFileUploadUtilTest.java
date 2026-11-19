package com.example.perfume_budget.utils;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.InvalidFileException;
import org.springframework.http.HttpStatus;
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
        when(multipartFile.getBytes()).thenReturn(PNG);
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
    void uploadProductImage_IOException_isA502NotAnEmptyUrl() throws IOException {
        when(multipartFile.isEmpty()).thenReturn(false);
        when(multipartFile.getBytes()).thenThrow(new IOException("test"));

        ApiCodeException e = assertThrows(ApiCodeException.class, () -> cloudinaryFileUploadUtil.uploadProductImage(multipartFile));
        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
        assertEquals("IMAGE_UPLOAD_FAILED", e.getCode());
        verifyNoInteractions(cloudinary);
    }

    @Test
    void uploadProductImage_uploaderIOException_isA502() throws IOException {
        when(multipartFile.isEmpty()).thenReturn(false);
        when(multipartFile.getBytes()).thenReturn(PNG);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap())).thenThrow(new IOException("down"));

        ApiCodeException e = assertThrows(ApiCodeException.class, () -> cloudinaryFileUploadUtil.uploadProductImage(multipartFile));
        assertEquals("IMAGE_UPLOAD_FAILED", e.getCode());
    }

    @Test
    void uploadProductImage_noUrlInTheAnswer_isA502NotAnEmptyUrl() throws IOException {
        when(multipartFile.isEmpty()).thenReturn(false);
        when(multipartFile.getBytes()).thenReturn(PNG);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap())).thenReturn(Map.of("public_id", "id1"));

        assertThrows(ApiCodeException.class, () -> cloudinaryFileUploadUtil.uploadProductImage(multipartFile));
    }

    @Test
    void uploadProductImage_notAnImage_is400AndNothingIsUploaded() throws IOException {
        when(multipartFile.isEmpty()).thenReturn(false);
        when(multipartFile.getBytes()).thenReturn("<svg onload=alert(1)>".getBytes());

        assertThrows(BadRequestException.class, () -> cloudinaryFileUploadUtil.uploadProductImage(multipartFile));
        verifyNoInteractions(cloudinary);
    }

    @Test
    void uploadProductImage_tooLarge_is413AndNothingIsUploaded() throws IOException {
        byte[] big = new byte[(int) ProductImages.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        when(multipartFile.isEmpty()).thenReturn(false);
        when(multipartFile.getBytes()).thenReturn(big);

        assertThrows(InvalidFileException.class, () -> cloudinaryFileUploadUtil.uploadProductImage(multipartFile));
        verifyNoInteractions(cloudinary);
    }

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
}
