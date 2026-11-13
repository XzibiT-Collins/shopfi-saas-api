package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.theme.BrandingStorageException;
import com.example.perfume_budget.theme.PayloadTooLargeException;
import com.example.perfume_budget.theme.ThemeConflictException;
import com.example.perfume_budget.theme.ThemeFieldError;
import com.example.perfume_budget.theme.ThemeRateLimitedException;
import com.example.perfume_budget.theme.ThemeValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.List;

/**
 * Maps theme exceptions for {@link StoreThemeController} only (contract 5.2/5.3). Scoped and ordered ahead of
 * {@code GlobalExceptionHandler}, whose catch-all would otherwise turn a 415 into a 500. 400 carries the
 * structured {@code data:[{path,code,message}]}; the other statuses use a plain-string description and null data.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = StoreThemeController.class)
public class StoreThemeExceptionHandler {

    @ExceptionHandler(ThemeValidationException.class)
    ResponseEntity<CustomApiResponse<List<ThemeFieldError>>> validation(ThemeValidationException ex) {
        return reply(HttpStatus.BAD_REQUEST, CustomApiResponse.error("Theme validation failed", ex.getErrors()));
    }

    @ExceptionHandler(ThemeConflictException.class)
    ResponseEntity<CustomApiResponse<Void>> conflict(ThemeConflictException ex) {
        return reply(HttpStatus.PRECONDITION_FAILED, CustomApiResponse.error("Theme was modified by someone else; reload and retry"));
    }

    @ExceptionHandler({PayloadTooLargeException.class, MaxUploadSizeExceededException.class})
    ResponseEntity<CustomApiResponse<Void>> tooLarge(Exception ex) {
        String msg = ex instanceof PayloadTooLargeException ? ex.getMessage() : "Request exceeds the 12 MB limit";
        return reply(HttpStatus.PAYLOAD_TOO_LARGE, CustomApiResponse.error(msg));
    }

    @ExceptionHandler(BrandingStorageException.class)
    ResponseEntity<CustomApiResponse<Void>> storage(BrandingStorageException ex) {
        log.error("Branding storage failed: {}", ex.getMessage());
        return reply(HttpStatus.BAD_GATEWAY, CustomApiResponse.error("Image storage is unavailable; theme unchanged"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<CustomApiResponse<Void>> mediaType(HttpMediaTypeNotSupportedException ex) {
        return reply(HttpStatus.UNSUPPORTED_MEDIA_TYPE, CustomApiResponse.error("Content type must be application/json or multipart/form-data"));
    }

    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class, HttpMessageNotReadableException.class})
    ResponseEntity<CustomApiResponse<List<ThemeFieldError>>> malformed(Exception ex) {
        return reply(HttpStatus.BAD_REQUEST, CustomApiResponse.error("Theme validation failed",
                List.of(new ThemeFieldError("$", "INVALID_FORMAT", "malformed multipart request"))));
    }

    @ExceptionHandler(ThemeRateLimitedException.class)
    ResponseEntity<CustomApiResponse<Void>> rateLimited(ThemeRateLimitedException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .cacheControl(CacheControl.noStore())
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(CustomApiResponse.error("Too many theme updates; retry later"));
    }

    private static <T> ResponseEntity<CustomApiResponse<T>> reply(HttpStatus status, CustomApiResponse<T> body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
