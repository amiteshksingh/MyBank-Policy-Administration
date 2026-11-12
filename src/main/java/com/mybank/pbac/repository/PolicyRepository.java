package com.mybank.pbac.repository;
import com.mybank.pbac.model.*; import org.springframework.data.jpa.repository.*; import java.util.*;
public interface PolicyRepository extends JpaRepository<Policy,Long>{
 Optional<Policy> findByCode(String code); long countByIndustryId(Long industryId);
 @Query("select p from Policy p where (:industryId is null or p.industry.id=:industryId) and (:guardrail is null or p.guardrailType=:guardrail) and (:enabled is null or p.enabled=:enabled) and (:q is null or lower(p.name) like lower(concat('%',:q,'%')) or lower(p.code) like lower(concat('%',:q,'%'))) order by p.industry.name,p.guardrailType,p.priority,p.name")
 List<Policy> search(Long industryId,GuardrailType guardrail,Boolean enabled,String q);
}
