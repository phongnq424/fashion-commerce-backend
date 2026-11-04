package com.fashioncommerce.fashioncommercebackend.auth.dto;

public record OtpChallengeResponse(long expiresIn, long resendAfter) { }
