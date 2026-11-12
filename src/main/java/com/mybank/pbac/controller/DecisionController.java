package com.mybank.pbac.controller;

import com.mybank.pbac.dto.SimulationRequest;
import com.mybank.pbac.dto.SimulationResponse;
import com.mybank.pbac.service.BundleService;
import com.mybank.pbac.service.SimulationService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
public class DecisionController {
    private final SimulationService simulation;
    private final BundleService bundles;

    public DecisionController(SimulationService simulation, BundleService bundles) {
        this.simulation = simulation;
        this.bundles = bundles;
    }

    @PostMapping("/simulate")
    public SimulationResponse simulate(@RequestBody SimulationRequest request) {
        return simulation.evaluate(request.context());
    }

    @GetMapping(value = "/bundle", produces = "application/gzip")
    public ResponseEntity<byte[]> bundle(
            @RequestHeader(value = "Accept", required = false) String accept) throws IOException {
        BundleService.PublishedBundle current = bundles.current();
        String contentType = accept != null && accept.contains("application/vnd.openpolicyagent.bundles")
                ? "application/vnd.openpolicyagent.bundles"
                : "application/gzip";
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(contentType))
                .cacheControl(CacheControl.noCache())
                .eTag('"' + current.revision() + '"')
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=MyBank-OPA-bundle.tar.gz")
                .header("X-OPA-Bundle-Revision", current.revision())
                .body(current.artifact());
    }
}
