package com.mybank.pbac.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybank.pbac.dto.DeploymentResponse;
import com.mybank.pbac.model.Policy;
import com.mybank.pbac.repository.PolicyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class OpaDeploymentService {
    private final PolicyRepository policies;
    private final BundleService bundles;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final String opaUrl;

    public OpaDeploymentService(
            PolicyRepository policies,
            BundleService bundles,
            AuditService audit,
            ObjectMapper objectMapper,
            @Value("${mybank.opa.url:http://localhost:8181}") String opaUrl) {
        this.policies = policies;
        this.bundles = bundles;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.opaUrl = opaUrl.replaceAll("/+$", "");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public Map<String, Object> health() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(opaUrl + "/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return Map.of("connected", response.statusCode() >= 200 && response.statusCode() < 300,
                    "opaUrl", opaUrl, "httpStatus", response.statusCode());
        } catch (Exception e) {
            return Map.of("connected", false, "opaUrl", opaUrl, "error", safeMessage(e));
        }
    }

    public DeploymentResponse deployPolicy(Long id) {
        Policy policy = policies.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Policy not found: " + id));
        String encodedCode = URLEncoder.encode(policy.getCode(), StandardCharsets.UTF_8);
        String target = opaUrl + "/v1/data/mybank/runtime/policies/" + encodedCode;
        try {
            String body = objectMapper.writeValueAsString(policy);
            HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            requireSuccess(response, "OPA rejected runtime policy deployment");
            audit.log("DEPLOY_POLICY", "POLICY", policy.getId(),
                    policy.getCode() + " -> " + target);
            return new DeploymentResponse("SINGLE_POLICY", "ACTIVE", target, policy.getCode(),
                    String.valueOf(policy.getVersion()), "Runtime override is active; OPA was not restarted.", Instant.now());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to deploy policy to OPA: " + safeMessage(e), e);
        }
    }

    public DeploymentResponse removeRuntimeOverride(Long id) {
        Policy policy = policies.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Policy not found: " + id));
        String encodedCode = URLEncoder.encode(policy.getCode(), StandardCharsets.UTF_8);
        String target = opaUrl + "/v1/data/mybank/runtime/policies/" + encodedCode;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(10)).DELETE().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            requireSuccess(response, "OPA rejected runtime override removal");
            audit.log("REMOVE_RUNTIME_OVERRIDE", "POLICY", policy.getId(), policy.getCode());
            return new DeploymentResponse("SINGLE_POLICY", "REMOVED", target, policy.getCode(),
                    String.valueOf(policy.getVersion()), "Bundle policy is effective after override removal.", Instant.now());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to remove runtime override: " + safeMessage(e), e);
        }
    }

    public DeploymentResponse publishBundle() {
        try {
            BundleService.PublishedBundle published = bundles.publish();
            audit.log("PUBLISH_BUNDLE", "OPA_BUNDLE", published.revision(),
                    "Published " + published.policyCount() + " enabled policies");
            return new DeploymentResponse("FULL_BUNDLE", "PUBLISHED",
                    "/api/bundle", null, published.revision(),
                    "Bundle published. Configured OPA instances will pull and atomically activate it on their next poll.",
                    published.publishedAt());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to publish OPA bundle: " + safeMessage(e), e);
        }
    }

    private static void requireSuccess(HttpResponse<String> response, String prefix) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(prefix + ": HTTP " + response.statusCode() + " " + response.body());
        }
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
