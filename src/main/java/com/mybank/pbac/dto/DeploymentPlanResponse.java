package com.mybank.pbac.dto;

public record DeploymentPlanResponse(
        Long policyId,
        String policyCode,
        String industry,
        String guardrailType,
        String guardrailSubtype,
        String decisionPath,
        String policyCollectionPath,
        String policyResourcePath,
        String opaUrl) {}
