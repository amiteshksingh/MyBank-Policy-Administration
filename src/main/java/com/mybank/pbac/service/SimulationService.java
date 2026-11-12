package com.mybank.pbac.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybank.pbac.dto.*;
import com.mybank.pbac.model.*;
import com.mybank.pbac.repository.PolicyRepository;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.net.*;
import java.util.*;

@Service
public class SimulationService {
    private final PolicyRepository repo;
    private final ObjectMapper om;

    public SimulationService(PolicyRepository r, ObjectMapper o) {
        repo = r;
        om = o;
    }

    public SimulationResponse evaluate(Map<String, Object> ctx) {
        List<Policy> matched = repo.findAll().stream().filter(Policy::isEnabled).filter(p -> matches(p, ctx))
                .sorted(Comparator.comparingInt(Policy::getPriority).reversed()).toList();
        if (matched.isEmpty())
            return new SimulationResponse("DENY", List.of(), List.of(), "Default deny: no enabled policy matched");
        List<Policy> denies = matched.stream().filter(p -> p.getEffect() == PolicyEffect.DENY).toList();
        if (!denies.isEmpty())
            return new SimulationResponse("DENY", List.of(), denies.stream().map(Policy::getCode).toList(),
                    "Explicit deny overrides permit");
        LinkedHashMap<String, Map<String, Object>> obs = new LinkedHashMap<>();
        for (Policy p : matched)
            for (Obligation o : p.getObligations())
                if (o.isEnabled()) {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("code", o.getCode());
                    x.put("name", o.getName());
                    try {
                        x.put("parameters", om.readValue(o.getParametersJson(), Object.class));
                    } catch (Exception e) {
                        x.put("parameters", o.getParametersJson());
                    }
                    obs.put(o.getCode(), x);
                }
        return new SimulationResponse("PERMIT", new ArrayList<>(obs.values()),
                matched.stream().map(Policy::getCode).toList(),
                obs.isEmpty() ? "Permitted" : "Permitted with obligations");
    }

    private boolean matches(Policy p, Map<String, Object> c) {
        if (p.getConditions().isEmpty())
            return true;
        var vals = p.getConditions().stream().map(x -> match(x, c)).toList();
        return p.getConditionLogic() == LogicalOperator.OR ? vals.stream().anyMatch(Boolean::booleanValue)
                : vals.stream().allMatch(Boolean::booleanValue);
    }

    private boolean match(PolicyCondition c, Map<String, Object> ctx) {
        Object a = lookup(ctx, c.getAttributeKey());
        String e = c.getExpectedValue();
        if (c.getOperator() == ConditionOperator.EXISTS)
            return a != null;
        if (c.getOperator() == ConditionOperator.NOT_EXISTS)
            return a == null;
        if (a == null)
            return false;
        String s = String.valueOf(a);
        return switch (c.getOperator()) {
            case EQ -> s.equalsIgnoreCase(e);
            case NEQ -> !s.equalsIgnoreCase(e);
            case IN -> csv(e).stream().anyMatch(x -> x.equalsIgnoreCase(s));
            case NOT_IN -> csv(e).stream().noneMatch(x -> x.equalsIgnoreCase(s));
            case CONTAINS -> s.contains(e);
            case STARTS_WITH -> s.startsWith(e);
            case ENDS_WITH -> s.endsWith(e);
            case GT -> num(s).compareTo(num(e)) > 0;
            case GTE -> num(s).compareTo(num(e)) >= 0;
            case LT -> num(s).compareTo(num(e)) < 0;
            case LTE -> num(s).compareTo(num(e)) <= 0;
            case CIDR -> cidr(s, e);
            default -> false;
        };
    }

 private Object lookup(Map<String,Object> m,String path){Object x=m;for(String k:path.split("\\.")){if(!(x instanceof Map<?,?> mm))return null;x=mm.get(k);}return x;}

    private List<String> csv(String x) {
        return x == null ? List.of() : Arrays.stream(x.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    private BigDecimal num(String x) {
        return new BigDecimal(x);
    }

    private boolean cidr(String ip, String cidr) {
        try {
            String[] p = cidr.split("/");
            byte[] a = InetAddress.getByName(ip).getAddress(), n = InetAddress.getByName(p[0]).getAddress();
            int bits = Integer.parseInt(p[1]);
            if (a.length != n.length)
                return false;
            for (int i = 0; i < a.length; i++) {
                int mask = Math.max(0, Math.min(8, bits - i * 8));
                int m = mask == 0 ? 0 : 0xff << (8 - mask) & 0xff;
                if ((a[i] & m) != (n[i] & m))
                    return false;
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
