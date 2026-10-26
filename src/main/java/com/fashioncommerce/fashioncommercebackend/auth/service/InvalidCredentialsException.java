package com.fashioncommerce.fashioncommercebackend.auth.service;

import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Deliberately committed by login's transaction so failed-attempt counters survive rejection. */
public class InvalidCredentialsException extends ApiException {
    public InvalidCredentialsException() { super(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid email or password"); }
}
