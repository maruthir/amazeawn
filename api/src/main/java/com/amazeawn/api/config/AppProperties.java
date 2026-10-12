package com.amazeawn.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "amazeawn")
public record AppProperties(Auth auth, Admin admin, Seed seed, Fulfilment fulfilment, RateLimit rateLimit) {

    public record Auth(String jwtSecret, Duration accessTtl, Duration refreshTtl, int bcryptCost) {}

    public record Admin(String email, String password, String name) {}

    public record Seed(int catalogSize, int initialStock, int categories) {}

    public record Fulfilment(boolean enabled, Duration shipAfter, Duration deliverAfter, Duration pollInterval) {}

    public record RateLimit(boolean enabled, int requestsPerSecond, int burst) {}
}
