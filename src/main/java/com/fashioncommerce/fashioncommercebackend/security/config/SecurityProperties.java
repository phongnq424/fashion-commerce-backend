package com.fashioncommerce.fashioncommercebackend.security.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties("security")
public record SecurityProperties(@Valid @NotNull Jwt jwt, @NotNull Duration refreshTtl, List<String> corsOrigins) {
    public SecurityProperties {
        corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
        if (refreshTtl != null && (refreshTtl.compareTo(Duration.ofSeconds(1)) < 0 || refreshTtl.compareTo(Duration.ofDays(30)) > 0)) {
            throw new IllegalArgumentException("Refresh TTL must be at least one second and at most 30 days");
        }
    }

    public record Jwt(@NotBlank String issuer, @NotBlank String secretBase64, @NotNull Duration accessTtl) {
        public Jwt {
            if (accessTtl != null && (accessTtl.compareTo(Duration.ofSeconds(1)) < 0 || accessTtl.compareTo(Duration.ofMinutes(15)) > 0)) {
                throw new IllegalArgumentException("Access TTL must be at least one second and at most 15 minutes");
            }
        }
        @Override public String toString() { return "Jwt[issuer=" + issuer + ", secretBase64=[REDACTED], accessTtl=" + accessTtl + "]"; }
    }
}
