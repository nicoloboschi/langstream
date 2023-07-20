package com.datastax.oss.sga.runtime.api.agent;

import java.util.Map;

public record AgentSpec (ComponentType componentType,
                         String tenant,
                         String agentId,
                         String applicationId,
                         String agentType,
                         Map<String, Object> configuration){
    public enum ComponentType {
        FUNCTION,
        SOURCE,
        SINK
    }
}