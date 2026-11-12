package com.mybank.pbac.dto;
import com.mybank.pbac.model.GuardrailType; import jakarta.validation.constraints.*;
public record PolicyTargetRequest(@NotNull Long industryId,@NotNull GuardrailType guardrailType,String guardrailSubtype,@NotBlank String decisionPath,@NotBlank String policyDataPath,Boolean enabled){}
