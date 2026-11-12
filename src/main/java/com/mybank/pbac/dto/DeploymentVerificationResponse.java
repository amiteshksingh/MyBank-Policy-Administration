package com.mybank.pbac.dto;

import java.time.Instant;

public record DeploymentVerificationResponse(
        String status,
        boolean stored,
        boolean contentMatches,
        String policyCode,
        String effect,
        long version,
        String resourcePath,
        Instant verifiedAt,
        Object storedValue) {}
