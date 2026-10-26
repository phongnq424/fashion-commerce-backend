package com.fashioncommerce.fashioncommercebackend.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserResponse(UUID id, String email, String displayName, String status, Instant emailVerifiedAt, List<String> roles) { }
