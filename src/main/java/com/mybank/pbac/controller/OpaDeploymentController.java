package com.mybank.pbac.controller;

import com.mybank.pbac.dto.DeploymentResponse;
import com.mybank.pbac.service.OpaDeploymentService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/deployments")
public class OpaDeploymentController {
    private final OpaDeploymentService deployment;

    public OpaDeploymentController(OpaDeploymentService deployment) {
        this.deployment = deployment;
    }

    @GetMapping("/opa/health")
    public Map<String, Object> health() {
        return deployment.health();
    }

    @PostMapping("/policies/{id}")
    public DeploymentResponse deployPolicy(@PathVariable Long id) {
        return deployment.deployPolicy(id);
    }

    @DeleteMapping("/policies/{id}/runtime-override")
    public DeploymentResponse removeRuntimeOverride(@PathVariable Long id) {
        return deployment.removeRuntimeOverride(id);
    }

    @PostMapping("/bundle")
    public DeploymentResponse publishBundle() {
        return deployment.publishBundle();
    }
}
