package com.mybank.pbac.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "mybank_policies", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
public class Policy {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @NotBlank
    @Column(nullable = false)
    private String code;
    @NotBlank
    private String name;
    @Column(length = 2000)
    private String description;
    @Enumerated(EnumType.STRING)
    private PolicyEffect effect = PolicyEffect.DENY;
    @Enumerated(EnumType.STRING)
    private LogicalOperator conditionLogic = LogicalOperator.AND;
    private int priority = 100;
    private boolean enabled = true;
    private long version = 1;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "industry_id")
    private Industry industry;
    @Enumerated(EnumType.STRING)
    @Column(name = "guardrail_type")
    private GuardrailType guardrailType = GuardrailType.OTHER;
    @Column(name = "guardrail_subtype", length = 100)
    private String guardrailSubtype;
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "policy_id")
    @OrderBy("sequenceNo ASC")
    private List<PolicyCondition> conditions = new ArrayList<>();
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "mybank_policy_obligations", joinColumns = @JoinColumn(name = "policy_id"), inverseJoinColumns = @JoinColumn(name = "obligation_id"))
    private Set<Obligation> obligations = new LinkedHashSet<>();
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void update() {
        updatedAt = Instant.now();
        version++;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long v) {
        id = v;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String v) {
        code = v;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        name = v;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        description = v;
    }

    public PolicyEffect getEffect() {
        return effect;
    }

    public void setEffect(PolicyEffect v) {
        effect = v;
    }

    public LogicalOperator getConditionLogic() {
        return conditionLogic;
    }

    public void setConditionLogic(LogicalOperator v) {
        conditionLogic = v;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int v) {
        priority = v;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public long getVersion() {
        return version;
    }

    public Industry getIndustry() {
        return industry;
    }

    public void setIndustry(Industry v) {
        industry = v;
    }

    public GuardrailType getGuardrailType() {
        return guardrailType;
    }

    public void setGuardrailType(GuardrailType v) {
        guardrailType = v == null ? GuardrailType.OTHER : v;
    }

    public String getGuardrailSubtype() {
        return guardrailSubtype;
    }

    public void setGuardrailSubtype(String v) {
        guardrailSubtype = v == null || v.isBlank() ? null : v.trim().toUpperCase();
    }

    public List<PolicyCondition> getConditions() {
        return conditions;
    }

    public void setConditions(List<PolicyCondition> v) {
        conditions.clear();
        if (v != null)
            conditions.addAll(v);
    }

    public Set<Obligation> getObligations() {
        return obligations;
    }

    public void setObligations(Set<Obligation> v) {
        obligations.clear();
        if (v != null)
            obligations.addAll(v);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
