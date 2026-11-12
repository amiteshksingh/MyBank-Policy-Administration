package com.mybank.pbac.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.mybank.pbac.model.ConditionOperator;
import com.mybank.pbac.model.PolicyEffect;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class RegoPolicyParser {
    private static final Pattern POLICY_RULE = Pattern.compile(
            "(permit_with_obligations|permit|deny)_([a-zA-Z0-9_]+)\\s+if\\s*\\{");
    private static final Pattern DEFAULT_DECISION_RULE = Pattern.compile(
            "default\\s+decision\\s*:=\\s*\\{");
    private static final Pattern DECISION_RULE = Pattern.compile("decision\\s*:=\\s*\\{");
    private static final Pattern OBLIGATIONS_RULE = Pattern.compile(
            "obligations_([a-zA-Z0-9_]+)\\s*:=\\s*(\\[|\\{)");
    private static final Pattern CONDITION = Pattern.compile(
            "input\\.(?:context\\.)?([a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)*)\\s*(==|!=|in)\\s*(.+)");

    private final ObjectMapper objectMapper;

    public RegoPolicyParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ImportedPolicy> parse(String filename, String source) {
        List<String> lines = source.lines().toList();
        List<PolicyDraft> policies = new ArrayList<>();
        Map<String, List<ImportedObligation>> obligationsByPolicy = new HashMap<>();
        Set<String> codes = new HashSet<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = stripComment(lines.get(i)).trim();
            if (line.isEmpty() || line.startsWith("package ") || line.startsWith("import ")) {
                continue;
            }

            if (DEFAULT_DECISION_RULE.matcher(line).matches()) {
                JsonValue json = readJsonValue(filename, lines, i, "{", false);
                JsonNode value = parseDecisionObject(filename, json.source());
                if (!"DENY".equals(value.path("decision").asText())) {
                    throw invalid(filename, "default decision must be DENY");
                }
                i = json.endLine();
                continue;
            }

            if (DECISION_RULE.matcher(line).matches()) {
                JsonValue json = readJsonValue(filename, lines, i, "{", true);
                JsonNode value = parseDecisionObject(filename, json.source());
                int ruleStart;
                if (json.suffix().equals("if {")) {
                    ruleStart = json.endLine();
                } else {
                    ruleStart = nextNonEmptyLine(filename, lines, json.endLine() + 1);
                    if (!"if {".equals(stripComment(lines.get(ruleStart)).trim())) {
                        throw invalid(filename, "decision rule must be followed by an 'if {' condition block");
                    }
                }
                ConditionBlock block = parseConditionBlock(filename, "decision rule", lines, ruleStart + 1);
                if (block.conditions().size() != 1
                        || !block.conditions().get(0).attributeKey().equals("user.role")
                        || block.conditions().get(0).operator() != ConditionOperator.EQ) {
                    throw invalid(filename, "decision rules require one input.user.role == \"ROLE\" condition");
                }
                String role = block.conditions().get(0).expectedValue();
                if (role.isBlank()) {
                    throw invalid(filename, "decision rule role must not be blank");
                }
                String code = role.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                        .replaceAll("^_+|_+$", "");
                if (code.isBlank() || !codes.add(code)) {
                    throw invalid(filename, "decision rule role produces an empty or duplicate policy code: " + role);
                }
                String decision = value.path("decision").textValue();
                PolicyEffect effect = switch (decision == null ? "" : decision) {
                    case "PERMIT" -> PolicyEffect.PERMIT;
                    case "DENY" -> PolicyEffect.DENY;
                    case "PERMIT_WITH_OBLIGATIONS" -> PolicyEffect.PERMIT_WITH_OBLIGATIONS;
                    default -> throw invalid(filename, "unsupported decision value: " + decision);
                };
                List<ImportedObligation> inlineObligations = parseDecisionObligations(
                        filename, code, value.path("obligations"));
                if (effect == PolicyEffect.PERMIT_WITH_OBLIGATIONS && inlineObligations.isEmpty()) {
                    throw invalid(filename, "PERMIT_WITH_OBLIGATIONS decision for role '" + role
                            + "' must include at least one obligation code");
                }
                if (effect != PolicyEffect.PERMIT_WITH_OBLIGATIONS && !inlineObligations.isEmpty()) {
                    throw invalid(filename, "only PERMIT_WITH_OBLIGATIONS decisions may include obligations");
                }
                policies.add(new PolicyDraft(code, displayName(code), effect,
                        value.path("reason").asText("Imported from Rego decision"),
                        block.conditions(), inlineObligations));
                i = block.endLine();
                continue;
            }

            Matcher policyMatcher = POLICY_RULE.matcher(line);
            if (policyMatcher.matches()) {
                PolicyEffect effect = switch (policyMatcher.group(1)) {
                    case "permit" -> PolicyEffect.PERMIT;
                    case "deny" -> PolicyEffect.DENY;
                    default -> PolicyEffect.PERMIT_WITH_OBLIGATIONS;
                };
                String code = policyMatcher.group(2);
                if (!codes.add(code)) {
                    throw invalid(filename, "duplicate policy rule for code '" + code + "'");
                }
                ConditionBlock block = parseConditionBlock(filename, code, lines, i + 1);
                policies.add(new PolicyDraft(code, displayName(code), effect, "Imported from Rego",
                        block.conditions(), List.of()));
                i = block.endLine();
                continue;
            }

            Matcher obligationsMatcher = OBLIGATIONS_RULE.matcher(line);
            if (obligationsMatcher.matches()) {
                String code = obligationsMatcher.group(1);
                if (obligationsByPolicy.containsKey(code)) {
                    throw invalid(filename, "duplicate obligations declaration for policy '" + code + "'");
                }
                JsonValue json = readJsonValue(filename, lines, i, obligationsMatcher.group(2), false);
                i = json.endLine();
                obligationsByPolicy.put(code, parseObligations(filename, code, json.source()));
                continue;
            }

            throw invalid(filename, "unsupported Rego syntax: " + line);
        }

        if (policies.isEmpty()) {
            throw invalid(filename, "no supported policy rules were found");
        }

        List<ImportedPolicy> result = new ArrayList<>();
        for (PolicyDraft policy : policies) {
            List<ImportedObligation> obligations = obligationsByPolicy.remove(policy.code());
            if (!policy.obligations().isEmpty()) {
                if (obligations != null) {
                    throw invalid(filename, "policy '" + policy.code()
                            + "' cannot combine inline and obligations_ declarations");
                }
                obligations = policy.obligations();
            }
            if (policy.effect() == PolicyEffect.PERMIT_WITH_OBLIGATIONS
                    && (obligations == null || obligations.isEmpty())) {
                throw invalid(filename, "policy '" + policy.code()
                        + "' requires an obligations_" + policy.code() + " declaration");
            }
            if (policy.effect() != PolicyEffect.PERMIT_WITH_OBLIGATIONS && obligations != null) {
                throw invalid(filename, "obligations_" + policy.code()
                        + " can only be used with permit_with_obligations_" + policy.code());
            }
            result.add(new ImportedPolicy(policy.code(), policy.name(), policy.effect(), policy.description(),
                    policy.conditions(), obligations == null ? List.of() : obligations));
        }
        if (!obligationsByPolicy.isEmpty()) {
            throw invalid(filename, "obligations declaration has no matching policy: "
                    + obligationsByPolicy.keySet().iterator().next());
        }
        return result;
    }

    private ImportedCondition parseCondition(String filename, String policyCode, String source) {
        Matcher matcher = CONDITION.matcher(source);
        if (!matcher.matches()) {
            throw invalid(filename, "unsupported condition in policy '" + policyCode + "': " + source);
        }
        String attributeKey = matcher.group(1);
        String operator = matcher.group(2);
        JsonNode value;
        try {
            value = readJson(matcher.group(3));
        } catch (IOException e) {
            throw invalid(filename, "condition value must be a JSON literal in policy '"
                    + policyCode + "': " + source);
        }

        if (value == null) {
            throw invalid(filename, "condition value must be a JSON literal in policy '" + policyCode + "'");
        }
        if (value.isNull()) {
            if (operator.equals("in")) {
                throw invalid(filename, "'in null' is not supported in policy '" + policyCode + "'");
            }
            return new ImportedCondition(attributeKey,
                    operator.equals("==") ? ConditionOperator.NOT_EXISTS : ConditionOperator.EXISTS,
                    "");
        }

        String expectedValue;
        ConditionOperator conditionOperator;
        if (operator.equals("in")) {
            if (!value.isArray() || value.isEmpty()) {
                throw invalid(filename, "'in' requires a non-empty JSON array in policy '" + policyCode + "'");
            }
            List<String> values = new ArrayList<>();
            for (JsonNode item : value) {
                if (!item.isValueNode() || item.isNull()) {
                    throw invalid(filename, "'in' supports only scalar, non-null values in policy '" + policyCode + "'");
                }
                String itemValue = item.isTextual() ? item.textValue() : item.asText();
                if (itemValue.contains(",")) {
                    throw invalid(filename, "'in' values cannot contain commas in policy '" + policyCode + "'");
                }
                values.add(itemValue);
            }
            expectedValue = String.join(",", values);
            conditionOperator = ConditionOperator.IN;
        } else {
            if (value.isContainerNode()) {
                throw invalid(filename, "condition value must be a scalar literal in policy '" + policyCode + "'");
            }
            expectedValue = value.isTextual() ? value.textValue() : value.asText();
            conditionOperator = operator.equals("==") ? ConditionOperator.EQ : ConditionOperator.NEQ;
        }
        return new ImportedCondition(attributeKey, conditionOperator, expectedValue);
    }

    private List<ImportedObligation> parseObligations(String filename, String policyCode, String json) {
        try {
            JsonNode root = readJson(json);
            List<JsonNode> nodes = new ArrayList<>();
            if (root.isArray()) {
                root.forEach(nodes::add);
            } else if (root.isObject()) {
                nodes.add(root);
            } else {
                throw invalid(filename, "obligations_" + policyCode + " must be an object or array of objects");
            }
            List<ImportedObligation> obligations = new ArrayList<>();
            Set<String> codes = new HashSet<>();
            for (JsonNode node : nodes) {
                if (!node.isObject() || !node.path("code").isTextual() || !node.path("name").isTextual()) {
                    throw invalid(filename, "each obligation for '" + policyCode
                            + "' must have string 'code' and 'name' fields");
                }
                String code = node.path("code").textValue();
                if (!codes.add(code)) {
                    throw invalid(filename, "duplicate obligation code '" + code
                            + "' in obligations_" + policyCode);
                }
                JsonNode parameters = node.path("parameters");
                if (!parameters.isMissingNode() && !parameters.isObject() && !parameters.isArray()) {
                    throw invalid(filename, "obligation parameters for '" + code + "' must be an object or array");
                }
                obligations.add(new ImportedObligation(code, node.path("name").textValue(),
                        node.path("description").asText(""),
                        parameters.isMissingNode() ? "{}" : objectMapper.writeValueAsString(parameters)));
            }
            if (obligations.isEmpty()) {
                throw invalid(filename, "obligations_" + policyCode + " must not be empty");
            }
            return obligations;
        } catch (IOException e) {
            throw invalid(filename, "obligations_" + policyCode + " must contain valid JSON");
        }
    }

    private JsonNode parseDecisionObject(String filename, String json) {
        try {
            JsonNode value = readJson(json);
            if (!value.isObject() || !value.path("decision").isTextual()
                    || !value.path("obligations").isArray()
                    || !value.path("reason").isTextual()) {
                throw invalid(filename, "decision objects must contain string decision/reason and an obligations array");
            }
            Set<String> supportedFields = Set.of("decision", "obligations", "reason");
            value.fieldNames().forEachRemaining(field -> {
                if (!supportedFields.contains(field)) {
                    throw invalid(filename, "unsupported decision object field: " + field);
                }
            });
            return value;
        } catch (IOException e) {
            throw invalid(filename, "decision rule must contain a valid JSON object");
        }
    }

    private List<ImportedObligation> parseDecisionObligations(
            String filename, String policyCode, JsonNode values) {
        List<ImportedObligation> result = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw invalid(filename, "decision obligations for '" + policyCode
                        + "' must be non-empty string codes");
            }
            String code = value.textValue();
            if (!codes.add(code)) {
                throw invalid(filename, "duplicate obligation code '" + code + "' for policy '" + policyCode + "'");
            }
            result.add(new ImportedObligation(code, displayName(code),
                    "Imported from Rego decision", "{}"));
        }
        return result;
    }

    private ConditionBlock parseConditionBlock(
            String filename, String policyCode, List<String> lines, int startLine) {
        List<ImportedCondition> conditions = new ArrayList<>();
        for (int i = startLine; i < lines.size(); i++) {
            String conditionLine = stripComment(lines.get(i)).trim();
            if (conditionLine.isEmpty()) {
                continue;
            }
            if (conditionLine.equals("}")) {
                if (conditions.isEmpty()) {
                    throw invalid(filename, "policy rule '" + policyCode + "' must contain at least one condition");
                }
                return new ConditionBlock(conditions, i);
            }
            conditions.add(parseCondition(filename, policyCode, conditionLine));
        }
        throw invalid(filename, "policy rule '" + policyCode + "' is missing its closing '}'");
    }

    private int nextNonEmptyLine(String filename, List<String> lines, int startLine) {
        for (int i = startLine; i < lines.size(); i++) {
            String line = stripComment(lines.get(i)).trim();
            if (!line.isEmpty()) return i;
        }
        throw invalid(filename, "decision rule is missing its condition block");
    }

    private JsonValue readJsonValue(
            String filename, List<String> lines, int start, String opening, boolean allowIfSuffix) {
        StringBuilder json = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < lines.size(); i++) {
            String line = stripComment(lines.get(i)).trim();
            int begin = i == start ? line.indexOf(opening) : 0;
            if (begin < 0) {
                throw invalid(filename, "invalid JSON declaration");
            }
            if (!json.isEmpty()) json.append('\n');
            json.append(line.substring(begin));
            for (int j = begin; j < line.length(); j++) {
                char ch = line.charAt(j);
                if (inString) {
                    if (escaped) escaped = false;
                    else if (ch == '\\') escaped = true;
                    else if (ch == '"') inString = false;
                } else if (ch == '"') {
                    inString = true;
                } else if (ch == '[' || ch == '{') {
                    depth++;
                } else if (ch == ']' || ch == '}') {
                    depth--;
                    if (depth == 0) {
                        String rawSuffix = line.substring(j + 1);
                        String suffix = rawSuffix.trim();
                        if (!suffix.isEmpty() && !(allowIfSuffix && suffix.equals("if {"))) {
                            throw invalid(filename, "unexpected text after JSON declaration");
                        }
                        if (!rawSuffix.isEmpty()) {
                            json.setLength(json.length() - rawSuffix.length());
                        }
                        if (suffix.equals("if {")) {
                            return new JsonValue(json.toString().strip(), i, suffix);
                        }
                    }
                }
            }
            if (depth == 0) {
                return new JsonValue(json.toString().strip(), i, "");
            }
        }
        throw invalid(filename, "unterminated obligations declaration");
    }

    private JsonNode readJson(String value) throws IOException {
        try (JsonParser parser = objectMapper.createParser(value)) {
            JsonNode node = objectMapper.readTree(parser);
            if (node == null || parser.nextToken() != null) {
                throw new IOException("Expected exactly one JSON value");
            }
            return node;
        }
    }

    private String stripComment(String line) {
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') inString = false;
            } else if (ch == '"') {
                inString = true;
            } else if (ch == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private String displayName(String code) {
        String[] parts = code.split("_");
        StringBuilder name = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!name.isEmpty()) name.append(' ');
            name.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
        }
        return name.toString();
    }

    private IllegalArgumentException invalid(String filename, String reason) {
        return new IllegalArgumentException(filename + ": " + reason);
    }

    private record PolicyDraft(String code, String name, PolicyEffect effect, String description,
            List<ImportedCondition> conditions, List<ImportedObligation> obligations) {}
    private record ConditionBlock(List<ImportedCondition> conditions, int endLine) {}
    private record JsonValue(String source, int endLine, String suffix) {}

    public record ImportedPolicy(String code, String name, PolicyEffect effect, String description,
            List<ImportedCondition> conditions, List<ImportedObligation> obligations) {}

    public record ImportedCondition(String attributeKey, ConditionOperator operator, String expectedValue) {}

    public record ImportedObligation(String code, String name, String description, String parametersJson) {}
}
