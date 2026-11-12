package com.mybank.pbac.dto;

import com.mybank.pbac.model.Policy;
import java.util.List;

public record RuntimePolicyPayload(
        Long id,
        String code,
        String name,
        String description,
        String industry,
        String guardrailType,
        String guardrailSubtype,
        String effect,
        String conditionLogic,
        int priority,
        boolean enabled,
        long version,
        List<ConditionPayload> conditions,
        List<ObligationPayload> obligations) {

    public static RuntimePolicyPayload from(Policy p) {
        return new RuntimePolicyPayload(
                p.getId(), p.getCode(), p.getName(), p.getDescription(),
                p.getIndustry().getCode().toLowerCase(),
                p.getGuardrailType().name().toLowerCase(),
                normalize(p.getGuardrailSubtype()), p.getEffect().name(),
                p.getConditionLogic().name(), p.getPriority(), p.isEnabled(), p.getVersion(),
                p.getConditions().stream().map(c -> new ConditionPayload(
                        c.getAttributeKey(), c.getOperator().name(),
                        c.getExpectedValue(), c.getSequenceNo())).toList(),
                p.getObligations().stream().filter(o -> o.isEnabled()).map(o ->
                        new ObligationPayload(o.getCode(), o.getName(), o.getParametersJson())).toList());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase();
    }

    public record ConditionPayload(
            String attributeKey, String operator, String expectedValue, int sequenceNo) {}
    public record ObligationPayload(String code, String name, String parametersJson) {}
}
