package com.mybank.pbac.dto;
import com.mybank.pbac.model.*; import jakarta.validation.constraints.*; import java.util.*;
public record PolicyRequest(@NotBlank String code,@NotBlank String name,String description,@NotNull PolicyEffect effect,LogicalOperator conditionLogic,Integer priority,Boolean enabled,@NotNull Long industryId,@NotNull GuardrailType guardrailType,String guardrailSubtype,List<ConditionInput> conditions,Set<Long> obligationIds){
 public PolicyRequest{if(guardrailType==GuardrailType.OTHER && (guardrailSubtype==null||guardrailSubtype.isBlank()))throw new IllegalArgumentException("OTHER guardrail requires guardrailSubtype");}
 public record ConditionInput(@NotBlank String attributeKey,@NotNull ConditionOperator operator,String expectedValue,Integer sequenceNo){}
}
