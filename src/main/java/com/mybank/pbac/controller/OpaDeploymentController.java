package com.mybank.pbac.controller;

import com.mybank.pbac.dto.DeploymentPlanResponse;
import com.mybank.pbac.dto.DeploymentResponse;
import com.mybank.pbac.dto.DeploymentVerificationResponse;
import com.mybank.pbac.service.BundleService;
import com.mybank.pbac.service.StructuredDeploymentService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/deployments")
public class OpaDeploymentController {
    private final StructuredDeploymentService structured;
    private final BundleService bundles;
    private final String opaUrl;

    public OpaDeploymentController(StructuredDeploymentService structured, BundleService bundles,
            @Value("${mybank.opa.url:http://localhost:8181}") String opaUrl) {
        this.structured = structured;
        this.bundles = bundles;
        this.opaUrl = opaUrl;
    }

    @GetMapping("/policies/{id}/plan")
    public DeploymentPlanResponse plan(@PathVariable Long id) { return structured.plan(id); }

    @PostMapping("/policies/{id}")
    public DeploymentResponse deploy(@PathVariable Long id) { return structured.deploy(id); }

    @GetMapping("/policies/{id}/verify")
    public DeploymentVerificationResponse verify(@PathVariable Long id) { return structured.verify(id); }

    @DeleteMapping("/policies/{id}/runtime-override")
    public DeploymentResponse remove(@PathVariable Long id) { return structured.remove(id); }

    @GetMapping("/opa/health")
    public Map<String,Object> health() {
        try {
            var result = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(opaUrl.replaceAll("/+$", "") + "/health")).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            return Map.of("connected", result.statusCode() >= 200 && result.statusCode() < 300,
                    "opaUrl", opaUrl, "httpStatus", result.statusCode());
        } catch (Exception e) {
            return Map.of("connected", false, "opaUrl", opaUrl, "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @PostMapping("/bundle")
    public Map<String,Object> bundle() throws Exception {
        byte[] bytes = bundles.build();
        return Map.of("status", "GENERATED", "bytes", bytes.length,
                "message", "Bundle generation retained; bundle publication/polling remains unchanged in phases 12-14.");
    }
}
