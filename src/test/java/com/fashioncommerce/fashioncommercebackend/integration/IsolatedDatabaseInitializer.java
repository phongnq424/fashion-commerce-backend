package com.fashioncommerce.fashioncommercebackend.integration;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Runs before any DataSource/Flyway beans are created. Never enables the suite by itself. */
public class IsolatedDatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override public void initialize(ConfigurableApplicationContext context) {
        if (!"ISOLATED_DATABASE_ONLY".equals(System.getenv("FC_RUN_POSTGRES_TESTS"))) {
            throw new IllegalStateException("PostgreSQL tests require FC_RUN_POSTGRES_TESTS=ISOLATED_DATABASE_ONLY");
        }
        for (String name : new String[]{"TEST_DB_HOST", "TEST_DB_NAME", "TEST_DB_USERNAME", "TEST_DB_PASSWORD"}) {
            if (System.getenv(name) == null || System.getenv(name).isBlank()) throw new IllegalStateException("Missing " + name);
        }
        String testHost = System.getenv("TEST_DB_HOST");
        if (!testHost.matches("[a-zA-Z0-9.-]+(?::[0-9]{1,5})?") || !System.getenv("TEST_DB_NAME").matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalStateException("Test database host/name must not contain JDBC parameters");
        }
        Properties ordinary = new Properties();
        Path envFile = Path.of(".env");
        if (Files.exists(envFile)) {
            try (Reader reader = Files.newBufferedReader(envFile)) { ordinary.load(reader); }
            catch (IOException ex) { throw new IllegalStateException("Cannot check ordinary DB endpoint safely"); }
        }
        String configuredHost = context.getEnvironment().getProperty("DB_HOST", "");
        if (sameEndpoint(testHost, configuredHost) || sameEndpoint(testHost, ordinary.getProperty("DB_HOST", ""))) {
            throw new IllegalStateException("Test database must use a different endpoint from ordinary DB_HOST; production is prohibited");
        }
    }

    private boolean sameEndpoint(String a, String b) {
        // Neon pooled/direct endpoints for the same branch must also be treated as equal.
        return canonical(a).equals(canonical(b));
    }
    private String canonical(String host) { return host.strip().toLowerCase(java.util.Locale.ROOT).replace("-pooler", "").replaceFirst(":5432$", ""); }
}
