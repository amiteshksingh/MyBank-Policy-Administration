package com.mybank.pbac.service;

import com.mybank.pbac.model.AttributeDataType;
import com.mybank.pbac.model.AttributeDefinition;
import com.mybank.pbac.model.Obligation;
import com.mybank.pbac.model.Policy;
import com.mybank.pbac.model.PolicyCondition;
import com.mybank.pbac.repository.AttributeDefinitionRepository;
import com.mybank.pbac.repository.ObligationRepository;
import com.mybank.pbac.repository.PolicyRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PolicyImportService {
    private static final int MAX_FILES = 10;
    private static final long MAX_FILE_SIZE = 1024 * 1024;

    private final RegoPolicyParser parser;
    private final PolicyRepository policies;
    private final ObligationRepository obligations;
    private final AttributeDefinitionRepository attributes;
    private final AuditService audit;

    public PolicyImportService(RegoPolicyParser parser, PolicyRepository policies,
            ObligationRepository obligations, AttributeDefinitionRepository attributes, AuditService audit) {
        this.parser = parser;
        this.policies = policies;
        this.obligations = obligations;
        this.attributes = attributes;
        this.audit = audit;
    }

    @Transactional
    public ImportResult importFiles(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Select at least one .rego file");
        }
        if (files.size() > MAX_FILES) {
            throw new IllegalArgumentException("A maximum of " + MAX_FILES + " Rego files can be imported at once");
        }

        List<RegoPolicyParser.ImportedPolicy> parsedPolicies = new ArrayList<>();
        Set<String> importedCodes = new HashSet<>();
        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename();
            if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".rego")) {
                throw new IllegalArgumentException("Only .rego files can be imported");
            }
            if (file.isEmpty()) {
                throw new IllegalArgumentException(filename + ": file is empty");
            }
            if (file.getSize() > MAX_FILE_SIZE) {
                throw new IllegalArgumentException(filename + ": file exceeds the 1 MiB import limit");
            }
            try {
                for (RegoPolicyParser.ImportedPolicy policy : parser.parse(filename,
                        new String(file.getBytes(), StandardCharsets.UTF_8))) {
                    if (!importedCodes.add(policy.code())) {
                        throw new IllegalArgumentException("Policy code '" + policy.code()
                                + "' occurs in more than one imported file");
                    }
                    if (policies.findByCode(policy.code()).isPresent()) {
                        throw new IllegalArgumentException("Policy code '" + policy.code()
                                + "' already exists; edit or remove it before importing");
                    }
                    parsedPolicies.add(policy);
                }
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException(filename + ": unable to read file", e);
            }
        }

        Map<String, RegoPolicyParser.ImportedObligation> importedObligations = new LinkedHashMap<>();
        for (RegoPolicyParser.ImportedPolicy policy : parsedPolicies) {
            for (RegoPolicyParser.ImportedObligation obligation : policy.obligations()) {
                RegoPolicyParser.ImportedObligation previous = importedObligations.putIfAbsent(
                        obligation.code(), obligation);
                if (previous != null && !previous.equals(obligation)) {
                    throw new IllegalArgumentException("Conflicting imported definitions for obligation code '"
                            + obligation.code() + "'");
                }
            }
        }

        Map<String, Obligation> obligationEntities = new LinkedHashMap<>();
        for (RegoPolicyParser.ImportedObligation importedObligation : importedObligations.values()) {
            Obligation obligation = obligations.findByCode(importedObligation.code()).orElseGet(Obligation::new);
            obligation.setCode(importedObligation.code());
            obligation.setName(importedObligation.name());
            obligation.setDescription(importedObligation.description());
            obligation.setParametersJson(importedObligation.parametersJson());
            obligation.setEnabled(true);
            obligationEntities.put(importedObligation.code(), obligations.save(obligation));
        }

        int importedAttributeCount = 0;
        List<String> policyCodes = new ArrayList<>();
        for (RegoPolicyParser.ImportedPolicy imported : parsedPolicies) {
            for (RegoPolicyParser.ImportedCondition importedCondition : imported.conditions()) {
                if (attributes.findByAttributeKey(importedCondition.attributeKey()).isEmpty()) {
                    AttributeDefinition attribute = new AttributeDefinition();
                    attribute.setAttributeKey(importedCondition.attributeKey());
                    attribute.setDisplayName(importedCondition.attributeKey());
                    attribute.setDataType(inferType(importedCondition));
                    attribute.setDescription("Imported from Rego");
                    attributes.save(attribute);
                    importedAttributeCount++;
                }
            }

            Set<Obligation> policyObligations = new LinkedHashSet<>();
            for (RegoPolicyParser.ImportedObligation importedObligation : imported.obligations()) {
                policyObligations.add(obligationEntities.get(importedObligation.code()));
            }

            Policy policy = new Policy();
            policy.setCode(imported.code());
            policy.setName(imported.name());
            policy.setDescription(imported.description());
            policy.setEffect(imported.effect());
            policy.setPriority(100);
            policy.setEnabled(true);
            policy.setObligations(policyObligations);
            List<PolicyCondition> conditions = new ArrayList<>();
            for (int i = 0; i < imported.conditions().size(); i++) {
                RegoPolicyParser.ImportedCondition condition = imported.conditions().get(i);
                PolicyCondition entity = new PolicyCondition();
                entity.setAttributeKey(condition.attributeKey());
                entity.setOperator(condition.operator());
                entity.setExpectedValue(condition.expectedValue());
                entity.setSequenceNo(i);
                conditions.add(entity);
            }
            policy.setConditions(conditions);
            policy = policies.save(policy);
            audit.log("IMPORT", "POLICY", policy.getId(), policy.getCode());
            policyCodes.add(policy.getCode());
        }

        return new ImportResult(parsedPolicies.size(), importedAttributeCount, importedObligations.size(), policyCodes);
    }

    private AttributeDataType inferType(RegoPolicyParser.ImportedCondition condition) {
        if (condition.operator() == com.mybank.pbac.model.ConditionOperator.IN) {
            return AttributeDataType.STRING_LIST;
        }
        String value = condition.expectedValue();
        if (value == null || value.isEmpty()) return AttributeDataType.STRING;
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return AttributeDataType.BOOLEAN;
        }
        try {
            Double.parseDouble(value);
            return AttributeDataType.NUMBER;
        } catch (NumberFormatException ignored) {
            return AttributeDataType.STRING;
        }
    }

    public record ImportResult(int importedPolicies, int importedAttributes,
            int importedObligations, List<String> policyCodes) {}
}
