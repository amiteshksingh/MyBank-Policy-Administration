package com.mybank.pbac.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybank.pbac.dto.DeploymentPlanResponse;
import com.mybank.pbac.dto.DeploymentResponse;
import com.mybank.pbac.dto.DeploymentVerificationResponse;
import com.mybank.pbac.dto.RuntimePolicyPayload;
import com.mybank.pbac.model.Policy;
import com.mybank.pbac.model.PolicyTarget;
import com.mybank.pbac.repository.PolicyRepository;
import com.mybank.pbac.repository.PolicyTargetRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StructuredDeploymentService {
    private final PolicyRepository policies;
    private final PolicyTargetRepository targets;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String opaUrl;

    public StructuredDeploymentService(
            PolicyRepository policies,
            PolicyTargetRepository targets,
            AuditService audit,
            ObjectMapper mapper,
            @Value("${mybank.opa.url:http://localhost:8181}") String opaUrl) {
        this.policies = policies;
        this.targets = targets;
        this.audit = audit;
        this.mapper = mapper;
        this.opaUrl = stripTrailingSlash(opaUrl);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Transactional(readOnly = true)
    public DeploymentPlanResponse plan(Long policyId) {
        Policy policy = findPolicy(policyId);
        PolicyTarget target = resolveTarget(policy);
        String collection = normalizeDataPath(target.getPolicyDataPath());
        String resource = collection + "/" + encode(policy.getCode());
        return new DeploymentPlanResponse(
                policy.getId(), policy.getCode(), policy.getIndustry().getCode(),
                policy.getGuardrailType().name(), policy.getGuardrailSubtype(),
                target.getDecisionPath(), collection, resource, opaUrl);
    }

    @Transactional(readOnly = true)
    public DeploymentResponse deploy(Long policyId) {
        Policy policy = findPolicy(policyId);
        DeploymentPlanResponse plan = plan(policyId);
        RuntimePolicyPayload payload = RuntimePolicyPayload.from(policy);
        try {
            HttpResponse<String> response = send("PUT", plan.policyResourcePath(),
                    mapper.writeValueAsString(payload));
            require2xx(response, "OPA rejected structured runtime policy deployment");
            DeploymentVerificationResponse verified = verify(policyId);
            if (!verified.stored() || !verified.contentMatches()) {
                throw new IllegalStateException("OPA accepted the PUT but verification did not match the policy payload");
            }
            audit.log("DEPLOY_POLICY", "POLICY", policy.getId(),
                    policy.getCode() + " -> " + plan.policyResourcePath());
            return new DeploymentResponse(
                    "SINGLE_POLICY", "ACTIVE", opaUrl + plan.policyResourcePath(),
                    policy.getCode(), String.valueOf(policy.getVersion()),
                    "Structured runtime policy stored and verified; OPA was not restarted.", Instant.now());
        } catch (Exception e) {
            throw new IllegalStateException("Structured policy deployment failed: " + message(e), e);
        }
    }

    @Transactional(readOnly = true)
    public DeploymentVerificationResponse verify(Long policyId) {
        Policy policy = findPolicy(policyId);
        DeploymentPlanResponse plan = plan(policyId);
        try {
            HttpResponse<String> response = send("GET", plan.policyResourcePath(), null);
            if (response.statusCode() == 404) {
                return new DeploymentVerificationResponse("NOT_FOUND", false, false,
                        policy.getCode(), policy.getEffect().name(), policy.getVersion(),
                        plan.policyResourcePath(), Instant.now(), null);
            }
            require2xx(response, "OPA runtime policy verification failed");
            JsonNode envelope = mapper.readTree(response.body());
            JsonNode stored = envelope.path("result");
            boolean exists = !stored.isMissingNode() && !stored.isNull();
            boolean matches = exists
                    && policy.getCode().equals(stored.path("code").asText())
                    && policy.getEffect().name().equals(stored.path("effect").asText())
                    && policy.getVersion() == stored.path("version").asLong(-1)
                    && policy.getIndustry().getCode().equalsIgnoreCase(stored.path("industry").asText())
                    && policy.getGuardrailType().name().equalsIgnoreCase(stored.path("guardrailType").asText());
            return new DeploymentVerificationResponse(matches ? "VERIFIED" : "MISMATCH",
                    exists, matches, policy.getCode(), policy.getEffect().name(), policy.getVersion(),
                    plan.policyResourcePath(), Instant.now(), exists ? mapper.treeToValue(stored, Object.class) : null);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to verify OPA storage: " + message(e), e);
        }
    }

    @Transactional(readOnly = true)
    public DeploymentResponse remove(Long policyId) {
        Policy policy = findPolicy(policyId);
        DeploymentPlanResponse plan = plan(policyId);
        try {
            HttpResponse<String> response = send("DELETE", plan.policyResourcePath(), null);
            require2xx(response, "OPA rejected runtime policy removal");
            audit.log("REMOVE_RUNTIME_OVERRIDE", "POLICY", policy.getId(), policy.getCode());
            return new DeploymentResponse("SINGLE_POLICY", "REMOVED",
                    opaUrl + plan.policyResourcePath(), policy.getCode(),
                    String.valueOf(policy.getVersion()),
                    "Structured runtime override removed. Existing Rego behavior is otherwise unchanged.", Instant.now());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to remove structured runtime policy: " + message(e), e);
        }
    }

    private Policy findPolicy(Long id) {
        Policy p = policies.findById(id).orElseThrow(() -> new NoSuchElementException("Policy not found: " + id));
        if (p.getIndustry() == null || p.getGuardrailType() == null) {
            throw new IllegalStateException("Policy must have Industry and Guardrail classification before deployment");
        }
        if (p.getGuardrailType().name().equals("OTHER")
                && (p.getGuardrailSubtype() == null || p.getGuardrailSubtype().isBlank())) {
            throw new IllegalStateException("OTHER policy requires guardrailSubtype before deployment");
        }
        return p;
    }

    private PolicyTarget resolveTarget(Policy policy) {
        return targets.findDeploymentTarget(policy.getIndustry().getId(),
                        policy.getGuardrailType(), normalizeSubtype(policy.getGuardrailSubtype()))
                .orElseThrow(() -> new IllegalStateException(
                        "No enabled PolicyTarget exists for " + policy.getIndustry().getCode()
                                + " / " + policy.getGuardrailType()
                                + (policy.getGuardrailSubtype() == null ? "" : " / " + policy.getGuardrailSubtype())));
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(opaUrl + path))
                .timeout(Duration.ofSeconds(10));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void require2xx(HttpResponse<String> r, String prefix) {
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IllegalStateException(prefix + ": HTTP " + r.statusCode() + " " + r.body());
        }
    }

    private static String normalizeDataPath(String path) {
        if (path == null || path.isBlank()) throw new IllegalStateException("PolicyTarget policyDataPath is blank");
        String value = path.trim();
        if (!value.startsWith("/")) value = "/" + value;
        if (!value.startsWith("/v1/data/")) {
            throw new IllegalStateException("policyDataPath must start with /v1/data/: " + value);
        }
        return stripTrailingSlash(value);
    }

    private static String stripTrailingSlash(String value) {
        return value == null ? null : value.replaceAll("/+$", "");
    }

    private static String normalizeSubtype(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
