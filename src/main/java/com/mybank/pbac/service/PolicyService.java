package com.mybank.pbac.service;

import com.mybank.pbac.dto.PolicyRequest;
import com.mybank.pbac.model.*;
import com.mybank.pbac.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class PolicyService {
    private final PolicyRepository policies;
    private final ObligationRepository obligations;
    private final AuditService audit;

    public PolicyService(PolicyRepository p, ObligationRepository o, AuditService a) {
        policies = p;
        obligations = o;
        audit = a;
    }

    public List<Policy> all() { return policies.findAll(); }

    public Policy one(Long id) {
        return policies.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Policy not found"));
    }

    @Transactional
    public Policy save(Long id, PolicyRequest r) {
        Policy p = id == null ? new Policy() : one(id);

        p.setCode(r.code());
        p.setName(r.name());
        p.setDescription(r.description());
        p.setEffect(r.effect());
        p.setConditionLogic(r.conditionLogic() == null ? LogicalOperator.AND : r.conditionLogic());
        p.setPriority(r.priority() == null ? 100 : r.priority());
        p.setEnabled(r.enabled() == null || r.enabled());

        // NEVER replace the Hibernate PersistentCollection reference.
        List<PolicyCondition> managedConditions = p.getConditions();
        managedConditions.clear();
        if (r.conditions() != null) {
            int sequence = 0;
            for (var x : r.conditions()) {
                PolicyCondition c = new PolicyCondition();
                c.setAttributeKey(x.attributeKey());
                c.setOperator(x.operator());
                c.setExpectedValue(x.expectedValue());
                c.setSequenceNo(x.sequenceNo() == null ? sequence : x.sequenceNo());
                managedConditions.add(c);
                sequence++;
            }
        }

        Set<Obligation> selected = new LinkedHashSet<>();
        if (r.obligationIds() != null) {
            selected.addAll(obligations.findAllById(r.obligationIds()));
        }
        if (p.getEffect() == PolicyEffect.PERMIT_WITH_OBLIGATIONS && selected.isEmpty()) {
            throw new IllegalArgumentException("PERMIT_WITH_OBLIGATIONS requires at least one obligation");
        }
        p.getObligations().clear();
        p.getObligations().addAll(selected);

        // p is managed inside this transaction; save() is not required for an edit.
        // Keep save() only for a newly-created entity.
        if (id == null) {
            p = policies.save(p);
        } else {
            policies.flush();
        }

        audit.log(id == null ? "CREATE" : "UPDATE", "POLICY", p.getId(), p.getCode());
        return p;
    }

    @Transactional
    public void delete(Long id) {
        Policy p = one(id);
        policies.delete(p);
        audit.log("DELETE", "POLICY", id, p.getCode());
    }
}
