package com.mybank.pbac.model;
import jakarta.persistence.*; import jakarta.validation.constraints.*;
@Entity @Table(name="mybank_policy_conditions") public class PolicyCondition {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @NotBlank private String attributeKey;
 @Enumerated(EnumType.STRING) private ConditionOperator operator=ConditionOperator.EQ; @Column(length=4000) private String expectedValue;
 private Integer sequenceNo=0;
 public Long getId(){return id;} public void setId(Long v){id=v;} public String getAttributeKey(){return attributeKey;} public void setAttributeKey(String v){attributeKey=v;}
 public ConditionOperator getOperator(){return operator;} public void setOperator(ConditionOperator v){operator=v;} public String getExpectedValue(){return expectedValue;} public void setExpectedValue(String v){expectedValue=v;}
 public Integer getSequenceNo(){return sequenceNo;} public void setSequenceNo(Integer v){sequenceNo=v;}
}
