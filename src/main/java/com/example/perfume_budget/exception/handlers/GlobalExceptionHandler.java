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
import com.example.perfume_budget.utils.DbErrorDetails;
import com.example.perfume_budget.utils.SafeStackTrace;

/**
 * SEC-01d: no response carries an exception's inner text. A framework, driver or library message can quote row values
 * (Postgres {@code Key (email)=(..) already exists}), a JDBC URL, credentials or the request itself, so those
 * handlers answer a FIXED description (constants below, listed in docs/api/errors.md). Messages of our own exception
 * types ({@link ApiCodeException}, {@link BadRequestException}, ...) are deliberate user-facing business text and are
 * kept. Status codes and {@code data.code}s are unchanged.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    public static final String UNEXPECTED_ERROR = "Unexpected error occurred";
    public static final String TRANSACTION_FAILED = "Transaction failed";
    public static final String ACCOUNTING_FAILED = "The operation could not be recorded in the ledger and was not saved. Nothing was changed; try again or contact support.";
    public static final String CONSTRAINT_VIOLATION = "A constraint violation occurred";
    public static final String OPERATION_NOT_SUPPORTED = "Operation not supported";
    public static final String UNSUPPORTED_MEDIA_TYPE_TEXT = "Unsupported content type";
    public static final String METHOD_NOT_SUPPORTED = "Request method not supported";
    public static final String RESOURCE_NOT_FOUND = "Resource not found";
    public static final String INVALID_REQUEST = "Invalid request";
    public static final String TOKEN_EXPIRED = "Token has expired";
    public static final String ACCESS_DENIED = "Access Denied";
    public static final String FILE_PROCESSING_FAILED = "File processing failed";
    public static final String INVALID_VALUE = "Invalid value";
    private static final String OUR_PACKAGE = "com.example.perfume_budget.";

    @org.springframework.beans.factory.annotation.Value("${spring.servlet.multipart.max-request-size:12MB}")
    private String maxRequestSize = "12MB";

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

    /**
     * API-06: a coupon refusal is the same 400 as any {@link BadRequestException} (same {@code description}), except
     * the per-customer limit, which also carries {@code data.code} {@code COUPON_PER_USER_LIMIT} (the code the cart
     * preview reports for it). The other reasons stay uncoded on purpose: they must not reveal which codes exist.
     */
    @ExceptionHandler(com.example.perfume_budget.exception.CouponRejectedException.class)
    public ResponseEntity<CustomApiResponse<Object>> handleCouponRejected(
            com.example.perfume_budget.exception.CouponRejectedException ex) {
        if (ex.getReason() == com.example.perfume_budget.exception.CouponRejectedException.Reason.PER_USER_LIMIT) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error(ex.getMessage(),
                    Map.of("code", com.example.perfume_budget.service.CouponPerUserLimit.CODE)));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<CustomApiResponse<Object>> handleBadRequestException(BadRequestException ex){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(coded(ex.getMessage(), ex.getCode()));
    }

    /** API-RD-11a: {@code data.code} only when the exception carries one; otherwise the historic {@code data: null}. */
    private static CustomApiResponse<Object> coded(String message, String code) {
        return code == null ? CustomApiResponse.error(message) : CustomApiResponse.error(message, Map.of("code", code));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<CustomApiResponse<String>> handleRateLimitExceeded(RateLimitExceededException ex){
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<CustomApiResponse<Object>> handleDuplicateResourceException(DuplicateResourceException ex){
        return ResponseEntity.status(HttpStatus.CONFLICT).body(coded(ex.getMessage(), ex.getCode()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<CustomApiResponse<Object>> handleResourceNotFoundException(ResourceNotFoundException ex){
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(coded(ex.getMessage(), ex.getCode()));
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
    /** Errors with a stable machine code (checkout/payment, docs/api/checkout.md): {description, data:{code,...}}. */
    @ExceptionHandler(ApiCodeException.class)
    public ResponseEntity<CustomApiResponse<Object>> handleApiCodeException(ApiCodeException ex){
        if (ex instanceof com.example.perfume_budget.exception.RetryAfterApiCodeException r) {
            return ResponseEntity.status(ex.getStatus())
                    .header(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(r.getRetryAfterSeconds()))
                    .body(CustomApiResponse.error(ex.getMessage(), ex.body()));
        }
        if (ex.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
            // API-04 rework 3: every busy / collided 503 (COUPON_BUSY, PAYMENT_SETTINGS_BUSY, TRY_AGAIN after the
            // in-process retries) says when to retry, like the TRY_AGAIN mapped from a raw deadlock below
            return ResponseEntity.status(ex.getStatus())
                    .header(org.springframework.http.HttpHeaders.RETRY_AFTER, "1")
                    .body(CustomApiResponse.error(ex.getMessage(), ex.body()));
        }
        return ResponseEntity.status(ex.getStatus()).body(CustomApiResponse.error(ex.getMessage(), ex.body()));
    }

    /** An unknown/invalid Pageable sort property is a client error (400), never a 500. */
    @ExceptionHandler(org.springframework.data.mapping.PropertyReferenceException.class)
    public ResponseEntity<CustomApiResponse<Object>> handleInvalidSortProperty(
            org.springframework.data.mapping.PropertyReferenceException ex){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                CustomApiResponse.error("Unsupported sort property.", java.util.Map.of("code", "INVALID_SORT")));
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
        // The effective ceiling is the global multipart max-request-size (12MB since THEME-04), not Boot's old 10MB default.
        String maxFileSizeErrorText = "File too large! Maximum allowed size is " + maxRequestSize;
        return ResponseEntity.status(413).body(CustomApiResponse.error(maxFileSizeErrorText));
    }

    /**
     * Safety net (THEME-04d): a multipart parse failure that got past ThemeWriteGuardFilter on the theme endpoint is a
     * client error (400 INVALID_FORMAT in the theme error shape), never a 500. Everywhere else the previous generic
     * behaviour is kept unchanged.
     */
    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public ResponseEntity<?> handleMultipartException(
            org.springframework.web.multipart.MultipartException ex, jakarta.servlet.http.HttpServletRequest request) {
        if ("PUT".equals(request.getMethod()) && com.example.perfume_budget.theme.ThemeRequestSizeFilter.isThemePath(request)) {
            log.debug("Theme multipart rejected by resolver: {}", ex.getClass().getSimpleName());
            return ResponseEntity.badRequest().body(CustomApiResponse.error("Theme validation failed",
                    java.util.List.of(new com.example.perfume_budget.theme.ThemeFieldError("$", "INVALID_FORMAT", "malformed multipart request"))));
        }
        return handleGenericException(ex);
    }

    @ExceptionHandler(InvalidFileException.class)
    public ResponseEntity<CustomApiResponse<String>> handleInvalidFileException(InvalidFileException ex) {
        return ResponseEntity.status(413).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(InputOutputException.class)
    public ResponseEntity<CustomApiResponse<String>> handleInputOutputException(InputOutputException ex){
        // SEC-01d: a 500 never echoes its message (an I/O failure's text can name paths or hosts)
        log.error("File processing failed: {}", SafeStackTrace.of(ex));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(CustomApiResponse.error(FILE_PROCESSING_FAILED));
    }

    @ExceptionHandler(ServiceCommunicationException.class)
    public ResponseEntity<CustomApiResponse<String>> handleServiceCommunicationException(ServiceCommunicationException ex){
        log.error("interservice communication failed: {}", ex.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(CustomApiResponse.error("Interservice communication failed"));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<CustomApiResponse<String>> handleForbiddenException(ForbiddenException ex){
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<CustomApiResponse<Object>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        // SEC-01d: SQLState and constraint name only (DbErrorDetails); the message / Detail quote the row's values
        log.warn("Constraint violation answered 409: {}", DbErrorDetails.of(ex));
        String message = CONSTRAINT_VIOLATION;
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
    public ResponseEntity<CustomApiResponse<Object>> handleAccountingException(AccountingException ex){
        // API-RD-13a: this used to return void, i.e. HTTP 200 with an empty body although the transaction had rolled
        // back. A ledger fault is a server fault: 500 with a stable code and a fixed text (the exception message names
        // ledger accounts and amounts, SEC-01d). The failed operation saved nothing, so a retry with the same
        // Idempotency-Key is safe.
        log.error("Accounting Exception: {}", ex.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(CustomApiResponse.error(ACCOUNTING_FAILED, java.util.Map.of("code", "ACCOUNTING_ERROR")));
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
        // SEC-01d: the library message carries the token's expiry and the server clock
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(CustomApiResponse.error(TOKEN_EXPIRED));
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
        // SEC-01d: a conversion failure's default message quotes the submitted value and Java types; a validation
        // message is author-written user-facing text and is kept
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.isBindingFailure() ? INVALID_VALUE : error.getDefaultMessage())
        );
        return ResponseEntity.badRequest().body(
                CustomApiResponse.error("Validation failed", errors)
        );
    }

    /**
     * Controller methods that mix {@code @Valid @RequestBody} with constraint annotations on other parameters
     * (e.g. reset-password's {@code @NotBlank @RequestParam}) get Spring's built-in method validation, which throws
     * this instead of a BindException. Same 400 shape as {@link #handleBindException}; it used to fall through to
     * the catch-all and answer 500 for an invalid body.
     */
    @ExceptionHandler(org.springframework.web.method.annotation.HandlerMethodValidationException.class)
    public ResponseEntity<CustomApiResponse<Map<String,String>>> handleHandlerMethodValidation(
            org.springframework.web.method.annotation.HandlerMethodValidationException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(error -> {
            String key = error instanceof org.springframework.validation.FieldError fe
                    ? fe.getField()
                    : String.valueOf(result.getMethodParameter().getParameterName());
            errors.put(key, error.getDefaultMessage());
        }));
        return ResponseEntity.badRequest().body(CustomApiResponse.error("Validation failed", errors));
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<CustomApiResponse<String>> handleUnauthorizedAccess(DisabledException ex){
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(CustomApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<CustomApiResponse<String>> handleMissingServletException(MissingServletRequestPartException ex){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error("Missing required part: " + ex.getRequestPartName() + "."));
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<CustomApiResponse<String>> handleAuthorizationDeniedException(AuthorizationDeniedException ex){
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(CustomApiResponse.error(ACCESS_DENIED));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CustomApiResponse<String>> handleEmptyBody(HttpMessageNotReadableException ex) {
        // API-04 rework: a strict integer field explains itself (fixed text + field name, never the submitted value)
        for (Throwable t = ex.getCause(); t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof com.example.perfume_budget.dto.json.StrictIntegerDeserializer.InvalidWholeNumberException w) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error(w.getOriginalMessage()));
            }
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error("Request body is missing or malformed."));
    }

    /** API-03: a body in the wrong format (JSON to a multipart route) is a clean 415, not the catch-all's 500. */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<CustomApiResponse<String>> handleMediaTypeNotSupported(
            org.springframework.web.HttpMediaTypeNotSupportedException ex){
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(CustomApiResponse.error(UNSUPPORTED_MEDIA_TYPE_TEXT));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<CustomApiResponse<String>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex){
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(CustomApiResponse.error(METHOD_NOT_SUPPORTED));
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<CustomApiResponse<String>> handleMethodNotSupported(UnsupportedOperationException ex){
        log.error("Unsupported operation: {}", SafeStackTrace.of(ex));
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(CustomApiResponse.error(OPERATION_NOT_SUPPORTED));
    }

    @ExceptionHandler(MissingRequestCookieException.class)
    public ResponseEntity<CustomApiResponse<String>> handleMissingRequestCookie(MissingRequestCookieException ex){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CustomApiResponse.error("Missing required cookie: " + ex.getCookieName() + "."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<CustomApiResponse<String>> handleNoResourceFoundException(NoResourceFoundException ex){
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(CustomApiResponse.error(RESOURCE_NOT_FOUND));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<CustomApiResponse<Object>> handleIllegalArgumentException(IllegalArgumentException ex, WebRequest request) {
        return new ResponseEntity<>(
                CustomApiResponse.error(ownBusinessMessage(ex) ? ex.getMessage() : INVALID_REQUEST),
                HttpStatus.BAD_REQUEST
        );
    }

    /**
     * SEC-01d: an IllegalArgumentException we threw ourselves (top frame in our code, no cause) carries a fixed,
     * author-written message ("Invalid or expired token", "Malformed ETag"); one from a library or the JDK
     * ({@code UUID.fromString}, {@code Enum.valueOf}, Hibernate, Spring) can quote the input or internals.
     */
    static boolean ownBusinessMessage(Throwable ex) {
        StackTraceElement[] frames = ex.getStackTrace();
        return ex.getCause() == null && ex.getMessage() != null && frames.length > 0
                && frames[0].getClassName().startsWith(OUR_PACKAGE);
    }

    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<CustomApiResponse<Void>> handleOptimisticLock(OptimisticLockException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(CustomApiResponse.error("Product was updated by another transaction, please try again"));
    }

    /**
     * API-04 rework 2: a deadlock victim (40P01), serialization failure (40001) or lock timeout (55P03) is a clean
     * {@code 503 {data.code:"TRY_AGAIN"}} with {@code Retry-After: 1}, never a 500. The transaction was rolled back,
     * nothing was changed, and the same request can simply be sent again.
     */
    @ExceptionHandler({org.springframework.dao.PessimisticLockingFailureException.class,
            jakarta.persistence.PessimisticLockException.class, jakarta.persistence.LockTimeoutException.class,
            org.hibernate.exception.LockAcquisitionException.class})
    public ResponseEntity<CustomApiResponse<Object>> handleLockFailure(Exception ex) {
        return tryAgain(ex);
    }

    /**
     * API-05: an optimistic-lock conflict that reaches the controller (Spring's translation of Hibernate's stale-state
     * failure; JPA's own {@link OptimisticLockException} keeps its 409 above). Stock writers no longer produce it
     * (ProductStockLocks), but a plain entity save racing another writer still can: the transaction was rolled back,
     * nothing changed, so it is the same {@code 503 TRY_AGAIN} with {@code Retry-After: 1}, never a 500.
     */
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<CustomApiResponse<Object>> handleOptimisticLockingFailure(Exception ex) {
        return tryAgain(ex);
    }

    private static ResponseEntity<CustomApiResponse<Object>> tryAgain(Throwable ex) {
        log.warn("Concurrent update, answered 503 TRY_AGAIN: {}", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, "1")
                .body(CustomApiResponse.error(com.example.perfume_budget.service.DbLockTimeout.TRY_AGAIN_MESSAGE,
                        Map.of("code", com.example.perfume_budget.service.DbLockTimeout.TRY_AGAIN)));
    }

    @ExceptionHandler(TransactionSystemException.class)
    public ResponseEntity<? extends CustomApiResponse<?>> handleTransactionException(TransactionSystemException ex) {
        if (com.example.perfume_budget.service.DbLockTimeout.isTransient(ex)) {
            return tryAgain(ex);
        }
        // EVT-01c: class only; a constraint message quotes row values (Postgres "Key (email)=(...) already exists")
        log.error("Transaction failed: {}", SafeStackTrace.of(ex)); // EVT-01c rework 1: classes + frames, no messages
        // SEC-01d: the body used to carry the root cause's message; now the fixed text
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(CustomApiResponse.error(TRANSACTION_FAILED, null));
    }

    // General Exceptions
    @ExceptionHandler(Exception.class)
    public ResponseEntity<? extends CustomApiResponse<?>> handleGenericException(Exception ex){
        if (com.example.perfume_budget.service.DbLockTimeout.isTransient(ex)) {
            return tryAgain(ex); // however it was wrapped (commit-time failure, JpaSystemException, ...)
        }
        // EVT-01c (overlaps SEC-01d): class only at ERROR; a database message can quote row values (Postgres
        // "Key (email)=(...) already exists"), a JDBC URL or credentials. Rework 1: the stack trace (class names and
        // frames, no messages) is back at ERROR; the full detail only at DEBUG.
        log.error("Unexpected error occurred: {}", SafeStackTrace.of(ex)); // classes + frames of the cause chain, no messages
        log.debug("Unexpected error detail", ex);
        return ResponseEntity.internalServerError().body(CustomApiResponse.error(UNEXPECTED_ERROR));
    }
}
