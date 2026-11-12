package com.mybank.pbac.dto; import java.util.*; public record SimulationResponse(String decision,List<Map<String,Object>> obligations,List<String> matchedPolicies,String reason) {}
