package com.fashioncommerce.fashioncommercebackend.common.exception;

import com.fashioncommerce.fashioncommercebackend.common.response.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
        return error(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            ConstraintViolationException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> invalidRequest(Exception ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "Invalid request", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied(AccessDeniedException ex, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missing(NoResourceFoundException ex, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MEDIA_TYPE_UNSUPPORTED", "Use application/json", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(DataIntegrityViolationException ex, HttpServletRequest request) {
        // Constraint names are matched internally; SQL detail is never included in the response.
        String constraint = constraintName(ex);
        if ("ix_users_3".equals(constraint))
            return error(HttpStatus.CONFLICT, "AUTH_EMAIL_EXISTS", "Email already registered", request);
        if ("ix_users_4".equals(constraint))
            return error(HttpStatus.CONFLICT, "AUTH_PHONE_EXISTS", "Phone already registered", request);
        if ("ix_seller_applications_3".equals(constraint))
            return error(HttpStatus.CONFLICT, "SELLER_APPLICATION_ACTIVE_EXISTS", "An active seller application already exists", request);
        if ("ix_seller_applications_4".equals(constraint))
            return error(HttpStatus.CONFLICT, "SELLER_ALREADY_APPROVED", "The user is already an approved seller", request);
        if ("uq_shops_owner_user_id".equals(constraint))
            return error(HttpStatus.CONFLICT, "SHOP_OWNER_LIMIT_REACHED", "A seller can own only one shop", request);
        if ("uq_shops_application_id".equals(constraint))
            return error(HttpStatus.CONFLICT, "SHOP_APPLICATION_ALREADY_USED", "The seller application already has a shop", request);
        if ("uq_shops_slug".equals(constraint))
            return error(HttpStatus.CONFLICT, "SHOP_SLUG_EXISTS", "Shop slug already exists", request);
        if ("uq_shop_members_shop_id_user_id".equals(constraint))
            return error(HttpStatus.CONFLICT, "SHOP_MEMBER_EXISTS", "The user is already associated with this shop", request);
        if ("ix_shop_ownership_transfers_pending_shop".equals(constraint))
            return error(HttpStatus.CONFLICT, "SHOP_TRANSFER_PENDING_EXISTS", "The shop already has a pending ownership transfer", request);
        return internal(ex, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> internal(Exception ex, HttpServletRequest request) {
        // Do not serialize exception messages, request bodies, SQL parameters or credentials.
        LOG.error("Unhandled API failure type={} path={}", ex.getClass().getSimpleName(), request.getRequestURI());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An internal error occurred", request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(), code, message, request.getRequestURI()));
    }

    private String constraintName(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && violation.getConstraintName() != null) return violation.getConstraintName();
            String message = cause.getMessage();
            if (message == null) continue;
            for (String known : new String[]{"ix_users_3", "ix_users_4", "ix_seller_applications_3",
                    "ix_seller_applications_4", "uq_shops_owner_user_id", "uq_shops_application_id",
                    "uq_shops_slug", "uq_shop_members_shop_id_user_id", "ix_shop_ownership_transfers_pending_shop"}) {
                if (message.contains(known)) return known;
            }
        }
        return null;
    }
}
