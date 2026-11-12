package com.mybank.pbac.config;

import com.mybank.pbac.model.*;
import com.mybank.pbac.repository.*;
import java.util.Map;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PolicyTargetBootstrap {
    @Bean
    CommandLineRunner seedStructuredTargets(IndustryRepository industries, PolicyTargetRepository targets) {
        return args -> {
            for (String industryCode : new String[]{"BANKING", "HOSPITALITY"}) {
                Industry industry = industries.findByCodeIgnoreCase(industryCode).orElse(null);
                if (industry == null) continue;
                String key = industryCode.toLowerCase();
                seed(targets, industry, GuardrailType.PROMPT, null,
                        "/v1/data/pbac/prompt/decision", "/v1/data/mybank/runtime/" + key + "/prompt");
                seed(targets, industry, GuardrailType.TOOL, null,
                        "/v1/data/pbac/tool/result", "/v1/data/mybank/runtime/" + key + "/tool");
                seed(targets, industry, GuardrailType.DATA, null,
                        "/v1/data/pbac/rag/decision", "/v1/data/mybank/runtime/" + key + "/data");
                seed(targets, industry, GuardrailType.OUTPUT, null,
                        "/v1/data/pbac/output/decision", "/v1/data/mybank/runtime/" + key + "/output");
                seed(targets, industry, GuardrailType.GLOBAL, null,
                        "/v1/data/pbac/authorize/decision", "/v1/data/mybank/runtime/" + key + "/global");
            }
        };
    }

    private static void seed(PolicyTargetRepository repo, Industry industry,
            GuardrailType type, String subtype, String decisionPath, String dataPath) {
        if (repo.findDeploymentTarget(industry.getId(), type, subtype).isPresent()) return;
        PolicyTarget target = new PolicyTarget();
        target.setIndustry(industry); target.setGuardrailType(type);
        target.setGuardrailSubtype(subtype); target.setDecisionPath(decisionPath);
        target.setPolicyDataPath(dataPath); target.setEnabled(true); repo.save(target);
    }
}
