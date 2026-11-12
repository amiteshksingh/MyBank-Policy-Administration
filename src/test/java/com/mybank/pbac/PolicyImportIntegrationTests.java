package com.mybank.pbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mybank.pbac.model.GuardrailType;
import com.mybank.pbac.model.Industry;
import com.mybank.pbac.repository.AttributeDefinitionRepository;
import com.mybank.pbac.repository.IndustryRepository;
import com.mybank.pbac.repository.ObligationRepository;
import com.mybank.pbac.repository.PolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:rego-import-test;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class PolicyImportIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private PolicyRepository policies;
    @Autowired private ObligationRepository obligations;
    @Autowired private AttributeDefinitionRepository attributes;
    @Autowired private IndustryRepository industries;

    private Long hospitalityId;

    @BeforeEach
    void setup() {
        policies.deleteAll();
        hospitalityId = industries.findByCodeIgnoreCase("HOSPITALITY")
            .orElseGet(() -> {
                Industry i = new Industry();
                i.setCode("HOSPITALITY");
                i.setName("Travel & Hospitality");
                return industries.save(i);
            }).getId();
    }

}
