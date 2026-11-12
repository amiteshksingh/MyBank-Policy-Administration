package com.mybank.pbac.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybank.pbac.repository.PolicyRepository;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class BundleService {
    private final PolicyRepository repo;
    private final ObjectMapper om;
    private final AtomicReference<PublishedBundle> published = new AtomicReference<>();

    public BundleService(PolicyRepository repo, ObjectMapper om) {
        this.repo = repo;
        this.om = om;
    }

    public synchronized PublishedBundle publish() throws IOException {
        String revision = Instant.now().toString() + "-" + UUID.randomUUID().toString().substring(0, 8);
        var enabled = repo.findAll().stream().filter(p -> p.isEnabled()).toList();

        Map<String, Object> policyMap = new LinkedHashMap<>();
        enabled.forEach(p -> policyMap.put(p.getCode(), p));

        Map<String, Object> data = Map.of("mybank", Map.of("bundle", Map.of("policies", policyMap)));
        Map<String, Object> manifest = Map.of(
                "revision", revision,
                "roots", new String[]{"mybank/bundle", "mybank/pbac"});

        byte[] artifact = archive(
                om.writerWithDefaultPrettyPrinter().writeValueAsBytes(data),
                om.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        PublishedBundle value = new PublishedBundle(revision, Instant.now(), enabled.size(), artifact);
        published.set(value);
        return value;
    }

    public PublishedBundle current() throws IOException {
        PublishedBundle current = published.get();
        return current == null ? publish() : current;
    }

    public byte[] build() throws IOException {
        return current().artifact();
    }

    private byte[] archive(byte[] data, byte[] manifest) throws IOException {
        byte[] rego = REGO.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var gz = new GzipCompressorOutputStream(out); var tar = new TarArchiveOutputStream(gz)) {
            add(tar, ".manifest", manifest);
            add(tar, "data.json", data);
            add(tar, "mybank/policy.rego", rego);
            tar.finish();
        }
        return out.toByteArray();
    }

    private void add(TarArchiveOutputStream tar, String name, byte[] bytes) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }

    public record PublishedBundle(String revision, Instant publishedAt, int policyCount, byte[] artifact) {}

    private static final String REGO = """
package mybank.pbac
import rego.v1

default decision := {"decision": "DENY", "obligations": [], "matchedPolicies": []}

value_at_path(obj, path) := value if {
  parts := split(path, ".")
  value := object.get(obj, parts, null)
}

csv(v) := [trim_space(x) | x := split(v, ",")[_]]

condition_matches(c) if {
  actual := value_at_path(input.context, c.attributeKey)
  c.operator == "EQ"
  lower(sprintf("%v", [actual])) == lower(c.expectedValue)
}
condition_matches(c) if { c.operator == "NEQ"; not condition_matches(object.union(c, {"operator":"EQ"})) }
condition_matches(c) if { actual := value_at_path(input.context, c.attributeKey); c.operator == "IN"; lower(sprintf("%v", [actual])) in {lower(x) | x := csv(c.expectedValue)[_]}}
condition_matches(c) if { value_at_path(input.context, c.attributeKey) != null; c.operator == "EXISTS" }
condition_matches(c) if { value_at_path(input.context, c.attributeKey) == null; c.operator == "NOT_EXISTS" }
condition_matches(c) if { actual := value_at_path(input.context, c.attributeKey); c.operator == "CIDR"; net.cidr_contains(c.expectedValue, actual) }

bundle_policies := object.get(object.get(object.get(data, "mybank", {}), "bundle", {}), "policies", {})
runtime_policies := object.get(object.get(object.get(data, "mybank", {}), "runtime", {}), "policies", {})
policy_codes := {code | some code; bundle_policies[code]} | {code | some code; runtime_policies[code]}
effective_policies := [object.get(runtime_policies, code, object.get(bundle_policies, code, {})) | code := policy_codes[_]]

policy_matches(p) if { p.enabled; p.conditionLogic == "AND"; every c in p.conditions { condition_matches(c) } }
policy_matches(p) if { p.enabled; p.conditionLogic == "OR"; some c in p.conditions; condition_matches(c) }

matched := [p | p := effective_policies[_]; policy_matches(p)]
deny_matches := [p.code | p := matched[_]; p.effect == "DENY"]
permit_matches := [p | p := matched[_]; p.effect != "DENY"]

decision := {"decision":"DENY", "obligations":[], "matchedPolicies":deny_matches} if { count(deny_matches) > 0 }
decision := {"decision":"PERMIT", "obligations":obs, "matchedPolicies":[p.code | p := permit_matches[_]]} if {
  count(deny_matches) == 0
  count(permit_matches) > 0
  obs := [o | p := permit_matches[_]; o := p.obligations[_]; o.enabled]
}
""";
}
