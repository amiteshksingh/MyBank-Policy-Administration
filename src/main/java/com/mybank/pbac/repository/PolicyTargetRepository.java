package com.mybank.pbac.repository;

import com.mybank.pbac.model.GuardrailType;
import com.mybank.pbac.model.PolicyTarget;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PolicyTargetRepository extends JpaRepository<PolicyTarget, Long> {
    List<PolicyTarget> findByIndustryIdOrderByGuardrailTypeAsc(Long industryId);

    @Query("""
        select t from PolicyTarget t
        where t.industry.id = :industryId
          and t.guardrailType = :guardrailType
          and t.enabled = true
          and ((:subtype is null and (t.guardrailSubtype is null or t.guardrailSubtype = ''))
               or upper(t.guardrailSubtype) = upper(:subtype))
        """)
    Optional<PolicyTarget> findDeploymentTarget(
            @Param("industryId") Long industryId,
            @Param("guardrailType") GuardrailType guardrailType,
            @Param("subtype") String subtype);
}
