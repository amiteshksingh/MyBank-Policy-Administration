package com.mybank.pbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mybank.pbac.repository.AttributeDefinitionRepository;
import com.mybank.pbac.repository.ObligationRepository;
import com.mybank.pbac.repository.PolicyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:rego-import-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PolicyImportIntegrationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private PolicyRepository policies;
    @Autowired private ObligationRepository obligations;
    @Autowired private AttributeDefinitionRepository attributes;

    @Test
    void importsPoliciesAndRelatedRecordsForTheEditor() throws Exception {
        String rego = """
                package mybank.imported
                permit_read_customer if {
                  input.context.custom.segment == "gold"
                }
                permit_with_obligations_sensitive_access if {
                  input.context.resource.classification == "sensitive"
                }
                obligations_sensitive_access := [
                  {
                    "code": "audit_sensitive_access",
                    "name": "Audit sensitive access",
                    "parameters": {"event": "sensitive_access"}
                  }
                ]
                """;
        MockMultipartFile file = new MockMultipartFile("files", "policies.rego",
                "text/plain", rego.getBytes());

        mockMvc.perform(multipart("/api/policies/import").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedPolicies").value(2))
                .andExpect(jsonPath("$.importedAttributes").value(1))
                .andExpect(jsonPath("$.importedObligations").value(1));

        var importedPolicy = policies.findByCode("read_customer").orElseThrow();
        assertEquals("Read Customer", importedPolicy.getName());
        assertEquals("gold", importedPolicy.getConditions().get(0).getExpectedValue());
        assertTrue(attributes.findByAttributeKey("custom.segment").isPresent());
        assertEquals(1, policies.findByCode("sensitive_access").orElseThrow().getObligations().size());
        assertTrue(obligations.findByCode("audit_sensitive_access").isPresent());
    }

    @Test
    void importsDecisionRulesFromOutputRego() throws Exception {
        String rego = """
                package pbac.output
                default decision := {
                    "decision": "DENY",
                    "obligations": [],
                    "reason": "Output not permitted"
                }
                decision := {
                    "decision": "PERMIT_WITH_OBLIGATIONS",
                    "obligations": ["mask_loyalty_points"],
                    "reason": "Customer service may see loyalty status but not points"
                } if {
                    input.user.role == "CUSTOMER_SERVICE_AGENT"
                }
                """;
        MockMultipartFile file = new MockMultipartFile("files", "output.rego",
                "text/plain", rego.getBytes());

        mockMvc.perform(multipart("/api/policies/import").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedPolicies").value(1))
                .andExpect(jsonPath("$.importedAttributes").value(1))
                .andExpect(jsonPath("$.importedObligations").value(1));

        var imported = policies.findByCode("customer_service_agent").orElseThrow();
        assertEquals("Customer service may see loyalty status but not points", imported.getDescription());
        assertEquals(1, imported.getObligations().size());
        assertTrue(obligations.findByCode("mask_loyalty_points").isPresent());
    }
}
