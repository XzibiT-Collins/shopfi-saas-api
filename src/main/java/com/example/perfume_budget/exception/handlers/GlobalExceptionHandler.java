package com.example.perfume_budget.exception.handlers;


import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.exception.*;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<CustomApiResponse<String>> handleMissingServletRequestParameterException(MissingServletRequestParameterException ex){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error("Missing required parameter: " + ex.getParameterName() + "."));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<CustomApiResponse<List<String>>> handleValidationException(ValidationException ex){
        return new ResponseEntity<>(
                CustomApiResponse.error(
                        "Validation failed: ",
                        ex.getErrors()
                ),
                HttpStatus.UNPROCESSABLE_ENTITY
        );
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<CustomApiResponse<String>> handleBadRequestException(BadRequestException ex){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<CustomApiResponse<String>> handleDuplicateResourceException(DuplicateResourceException ex){
        return ResponseEntity.status(HttpStatus.CONFLICT).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<CustomApiResponse<String>> handleResourceNotFoundException(ResourceNotFoundException ex){
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(InvalidJWTTokenException.class)
    public ResponseEntity<CustomApiResponse<String>> handleExpiredJwtException(InvalidJWTTokenException ex){
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(InactiveAccountException.class)
    public ResponseEntity<CustomApiResponse<String>> handleInactiveAccountException(InactiveAccountException ex){
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<CustomApiResponse<String>> handleUnauthorizedException(UnauthorizedException ex){
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(CustomApiResponse.error(ex.getMessage()));
    }
    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<CustomApiResponse<String>> handleUnauthorizedException(PaymentException ex){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<CustomApiResponse<String>> handleBadCredentialsException(BadCredentialsException ex){
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<CustomApiResponse<String>> handleMaxSizeException(MaxUploadSizeExceededException ex) {
        String maxFileSizeErrorText = "File too large! Maximum allowed size is 10MB";
        return ResponseEntity.status(413).body(CustomApiResponse.error(maxFileSizeErrorText));
    }

    @ExceptionHandler(InvalidFileException.class)
    public ResponseEntity<CustomApiResponse<String>> handleInvalidFileException(InvalidFileException ex) {
        return ResponseEntity.status(413).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(InputOutputException.class)
    public ResponseEntity<CustomApiResponse<String>> handleInputOutputException(InputOutputException ex){
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(ServiceCommunicationException.class)
    public ResponseEntity<CustomApiResponse<String>> handleServiceCommunicationException(ServiceCommunicationException ex){
        log.error("interservice communication failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(CustomApiResponse.error("Interservice communication failed"));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<CustomApiResponse<String>> handleForbiddenException(ForbiddenException ex){
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<CustomApiResponse<Object>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String message = "A constraint violation occurred";
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(CustomApiResponse.error(message));
    }

    @ExceptionHandler(PartialSuccessException.class)
    public ResponseEntity<CustomApiResponse<List<String>>> handlePartialSuccess(PartialSuccessException ex) {
        return ResponseEntity.status(HttpStatus.MULTI_STATUS)
                .body(CustomApiResponse.error(ex.getMessage(), ex.getFailedItems()));
    }

    // Accounting Exception
    @ExceptionHandler(AccountingException.class)
    public void handleAccountingException(AccountingException ex){
        log.error("Accounting Exception: {}", ex.getMessage());
    }


    // Core Exceptions
    @ExceptionHandler(FileUploadException.class)
    public ResponseEntity<CustomApiResponse<String>> handleFileUploadException(FileUploadException ex){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error(ex.getMessage()));
    }


    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<CustomApiResponse<String>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException ex){
        if(ex.getRequiredType() == LocalDate.class){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error("Invalid date format. Use yyyy-MM-dd"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error("Invalid parameter type"));
    }

    @ExceptionHandler(ExpiredJwtException.class)
    public ResponseEntity<CustomApiResponse<String>> handleExpiredJwtException(ExpiredJwtException ex){
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<CustomApiResponse<Map<String, Object>>> handleConstraintViolationException(ConstraintViolationException ex) {
        Map<String, Object> body = new HashMap<>();

        // Collect field -> message pairs
        body.put("violations", ex.getConstraintViolations()
                .stream()
                .collect(Collectors.toMap(
                        v -> v.getPropertyPath().toString(),
                        ConstraintViolation::getMessage
                )));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(CustomApiResponse.error("Validation failed", body));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<CustomApiResponse<Map<String,String>>> handleBindException(BindException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage())
        );
        return ResponseEntity.badRequest().body(
                CustomApiResponse.error("Validation failed", errors)
        );
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<CustomApiResponse<String>> handleUnauthorizedAccess(DisabledException ex){
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<CustomApiResponse<String>> handleMissingServletException(MissingServletRequestPartException ex){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<CustomApiResponse<String>> handleAuthorizationDeniedException(AuthorizationDeniedException ex){
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CustomApiResponse<String>> handleEmptyBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error("Request body is missing or malformed."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<CustomApiResponse<String>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex){
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<CustomApiResponse<String>> handleMethodNotSupported(UnsupportedOperationException ex){
        log.error(String.valueOf(ex));
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(MissingRequestCookieException.class)
    public ResponseEntity<CustomApiResponse<String>> handleMissingRequestCookie(MissingRequestCookieException ex){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<CustomApiResponse<String>> handleNoResourceFoundException(NoResourceFoundException ex){
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<CustomApiResponse<Object>> handleIllegalArgumentException(IllegalArgumentException ex, WebRequest request) {
        return new ResponseEntity<>(
                CustomApiResponse.error(ex.getMessage()),
                HttpStatus.BAD_REQUEST
        );
    }

    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<CustomApiResponse<Void>> handleOptimisticLock(OptimisticLockException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(CustomApiResponse.error("Product was updated by another transaction, please try again"));
    }

    @ExceptionHandler(TransactionSystemException.class)
    public ResponseEntity<CustomApiResponse<Void>> handleTransactionException(TransactionSystemException ex) {
        Throwable cause = ex.getRootCause();
        log.error("Transaction failed: {}", cause != null ? cause.getMessage() : ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(CustomApiResponse.error(cause != null ? cause.getMessage() : "Transaction failed", null));
    }

    // General Exceptions
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CustomApiResponse<String>> handleGenericException(Exception ex){
        log.error("Unexpected error occurred: {}", ex.getMessage());
        log.error(String.valueOf(ex));
        return ResponseEntity.internalServerError().body(CustomApiResponse.error("Unexpected error occurred"));
    }
}
