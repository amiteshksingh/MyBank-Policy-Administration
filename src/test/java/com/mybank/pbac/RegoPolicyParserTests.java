package com.mybank.pbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybank.pbac.model.ConditionOperator;
import com.mybank.pbac.model.PolicyEffect;
import com.mybank.pbac.service.RegoPolicyParser;
import org.junit.jupiter.api.Test;

class RegoPolicyParserTests {
    private final RegoPolicyParser parser = new RegoPolicyParser(new ObjectMapper());

    @Test
    void parsesPoliciesConditionsAndObligations() {
        String source = """
                package mybank.imported
                import rego.v1

                permit_read_customer if {
                  input.context.subject.role == "analyst"
                  input.context.action.tool in ["read", "review"]
                  input.context.resource.classification != null
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

        var result = parser.parse("policies.rego", source);

        assertEquals(2, result.size());
        assertEquals(PolicyEffect.PERMIT, result.get(0).effect());
        assertEquals("read_customer", result.get(0).code());
        assertEquals(ConditionOperator.EQ, result.get(0).conditions().get(0).operator());
        assertEquals(ConditionOperator.IN, result.get(0).conditions().get(1).operator());
        assertEquals("read,review", result.get(0).conditions().get(1).expectedValue());
        assertEquals(ConditionOperator.EXISTS, result.get(0).conditions().get(2).operator());
        assertEquals(PolicyEffect.PERMIT_WITH_OBLIGATIONS, result.get(1).effect());
        assertEquals("audit_sensitive_access", result.get(1).obligations().get(0).code());
        assertTrue(result.get(1).obligations().get(0).parametersJson().contains("sensitive_access"));
    }

    @Test
    void rejectsUnsupportedRegoInsteadOfIgnoringIt() {
        String source = """
                package mybank.imported
                permit_admin if {
                  some role in input.context.subject.roles
                }
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("unsupported.rego", source));

        assertTrue(error.getMessage().contains("unsupported.rego"));
        assertTrue(error.getMessage().contains("unsupported condition"));
    }

    @Test
    void requiresMatchingObligationDeclaration() {
        String source = """
                permit_with_obligations_audit if {
                  input.context.action.tool == "read"
                }
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("missing-obligation.rego", source));

        assertTrue(error.getMessage().contains("obligations_audit"));
    }

    @Test
    void parsesDecisionAssignmentRulesWithRoleConditionsAndStringObligations() {
        String source = """
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

                decision := {
                    "decision": "PERMIT",
                    "obligations": [],
                    "reason": "Supervisor full hospitality view"
                } if {
                    input.user.role == "SUPERVISOR"
                }
                """;

        var result = parser.parse("output.rego", source);

        assertEquals(2, result.size());
        assertEquals("customer_service_agent", result.get(0).code());
        assertEquals(PolicyEffect.PERMIT_WITH_OBLIGATIONS, result.get(0).effect());
        assertEquals("user.role", result.get(0).conditions().get(0).attributeKey());
        assertEquals("CUSTOMER_SERVICE_AGENT", result.get(0).conditions().get(0).expectedValue());
        assertEquals("mask_loyalty_points", result.get(0).obligations().get(0).code());
        assertEquals("Supervisor full hospitality view", result.get(1).description());
    }
}
