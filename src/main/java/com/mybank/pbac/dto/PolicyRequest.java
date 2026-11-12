package com.mybank.pbac.dto; import com.mybank.pbac.model.*; import jakarta.validation.constraints.*; import java.util.*;
public record PolicyRequest(@NotBlank String code,@NotBlank String name,String description,@NotNull PolicyEffect effect,LogicalOperator conditionLogic,Integer priority,Boolean enabled,List<ConditionInput> conditions,Set<Long> obligationIds){
 public record ConditionInput(@NotBlank String attributeKey,@NotNull ConditionOperator operator,String expectedValue,Integer sequenceNo){}
}
