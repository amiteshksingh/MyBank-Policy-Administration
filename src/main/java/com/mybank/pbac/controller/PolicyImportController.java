package com.mybank.pbac.controller;

import com.mybank.pbac.service.PolicyImportService;
import com.mybank.pbac.service.PolicyImportService.ImportResult;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/policies")
public class PolicyImportController {
    private final PolicyImportService importer;

    public PolicyImportController(PolicyImportService importer) {
        this.importer = importer;
    }

    @PostMapping("/import")
    public ImportResult importPolicies(@RequestParam("files") List<MultipartFile> files) {
        return importer.importFiles(files);
    }
}
