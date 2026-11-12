package com.mybank.pbac.dto;

import java.time.Instant;

public record DeploymentResponse(
        String deploymentType,
        String status,
        String target,
        String policyCode,
        String revision,
        String message,
        Instant deployedAt) {
}
