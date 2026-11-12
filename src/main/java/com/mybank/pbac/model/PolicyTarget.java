package com.mybank.pbac.model;
import jakarta.persistence.*; import jakarta.validation.constraints.*; import java.time.Instant;
@Entity @Table(name="mybank_policy_targets",uniqueConstraints=@UniqueConstraint(columnNames={"industry_id","guardrail_type","guardrail_subtype"}))
public class PolicyTarget {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(optional=false,fetch=FetchType.EAGER) @JoinColumn(name="industry_id",nullable=false) private Industry industry;
 @Enumerated(EnumType.STRING) @Column(name="guardrail_type",nullable=false) private GuardrailType guardrailType;
 @Column(name="guardrail_subtype",length=100) private String guardrailSubtype="";
 @NotBlank @Column(nullable=false,length=300) private String decisionPath;
 @NotBlank @Column(nullable=false,length=300) private String policyDataPath;
 private boolean enabled=true; private Instant updatedAt=Instant.now(); @PreUpdate void update(){updatedAt=Instant.now();}
 public Long getId(){return id;} public Industry getIndustry(){return industry;} public void setIndustry(Industry v){industry=v;}
 public GuardrailType getGuardrailType(){return guardrailType;} public void setGuardrailType(GuardrailType v){guardrailType=v;}
 public String getGuardrailSubtype(){return guardrailSubtype;} public void setGuardrailSubtype(String v){guardrailSubtype=v==null?"":v.trim().toUpperCase();}
 public String getDecisionPath(){return decisionPath;} public void setDecisionPath(String v){decisionPath=v;}
 public String getPolicyDataPath(){return policyDataPath;} public void setPolicyDataPath(String v){policyDataPath=v;}
 public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;} public Instant getUpdatedAt(){return updatedAt;}
}
