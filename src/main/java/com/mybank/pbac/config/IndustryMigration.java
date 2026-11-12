package com.mybank.pbac.config;

import com.mybank.pbac.model.*;
import com.mybank.pbac.repository.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.*;

@Configuration
public class IndustryMigration {
    @Bean
    CommandLineRunner migrateIndustries(IndustryRepository industries, PolicyRepository policies) {
        return args -> {
            Industry common = industries.findByCodeIgnoreCase("COMMON").orElseGet(() -> {
                Industry i = new Industry();
                i.setCode("COMMON");
                i.setName("Common / Cross Industry");
                i.setDescription("Default classification for policies migrated from the existing database");
                return industries.save(i);
            });
            seed(industries, "BANKING", "Banking");
            seed(industries, "HOSPITALITY", "Travel & Hospitality");
            for (Policy p : policies.findAll()) {
                boolean changed = false;
                if (p.getIndustry() == null) {
                    p.setIndustry(common);
                    changed = true;
                }
                if (p.getGuardrailType() == null) {
                    p.setGuardrailType(GuardrailType.OTHER);
                    p.setGuardrailSubtype("LEGACY");
                    changed = true;
                }
                if (changed)
                    policies.save(p);
            }
        };
    }

    private void seed(IndustryRepository r, String code, String name) {
        if (r.findByCodeIgnoreCase(code).isEmpty()) {
            Industry i = new Industry();
            i.setCode(code);
            i.setName(name);
            r.save(i);
        }
    }
}
