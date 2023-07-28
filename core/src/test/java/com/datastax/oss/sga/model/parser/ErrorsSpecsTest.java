package com.datastax.oss.sga.model.parser;

import com.datastax.oss.sga.api.model.AgentConfiguration;
import com.datastax.oss.sga.api.model.Application;
import com.datastax.oss.sga.api.model.Module;
import com.datastax.oss.sga.api.model.Pipeline;
import com.datastax.oss.sga.impl.parser.ModelBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ErrorsSpecsTest {

    @Test
    public void testConfigureErrors() throws Exception {
        Application applicationInstance = ModelBuilder
                .buildApplicationInstance(Map.of("instance.yaml",
                        buildInstanceYaml(),
                        "module.yaml", """
                                module: "module-1"
                                id: "pipeline-1"
                                errors:
                                   retries: 7
                                   on-failure: skip
                                   dead-letter-topic: "dead-letter-topic"             
                                topics:
                                  - name: "input-topic"
                                    creation-mode: create-if-not-exists
                                  - name: "dead-letter-topic"
                                    creation-mode: create-if-not-exists
                                pipeline:
                                  - name: "step1"                                    
                                    type: "noop"
                                    input: "input-topic"                                    
                                  - name: "step2"
                                    type: "noop"
                                    errors:
                                       on-failure: fail
                                  - name: "step3"
                                    type: "noop"
                                    errors:
                                       retries: 3                                      
                                  - name: "step4"
                                    type: "noop"
                                    errors:
                                       retries: 5
                                       on-failure: fail
                                """,
                        "module2.yaml", """
                                module: "module-2"
                                id: "pipeline-2"             
                                topics:
                                  - name: "input-topic"
                                    creation-mode: create-if-not-exists
                                  - name: "dead-letter-topic"
                                    creation-mode: create-if-not-exists
                                pipeline:
                                  - name: "step1"                                    
                                    type: "noop"
                                    input: "input-topic"                                    
                                  - name: "step2"
                                    type: "noop"
                                    errors:
                                       on-failure: skip
                                  - name: "step3"
                                    type: "noop"
                                    errors:
                                       retries: 3                                      
                                  - name: "step3"
                                    type: "noop"
                                    errors:
                                       retries: 5
                                       on-failure: skip
                                """));

        {
            // use pipeline defaults
            Module module = applicationInstance.getModule("module-1");
            Pipeline pipeline = module.getPipelines().get("pipeline-1");

            AgentConfiguration agent1 = pipeline.getAgents().get(0);
            assertNotNull(agent1.getErrors());
            assertEquals(7, agent1.getErrors().getRetries());
            assertEquals("skip", agent1.getErrors().getOnFailure());

            AgentConfiguration agent2 = pipeline.getAgents().get(1);
            assertNotNull(agent2.getErrors());
            assertEquals(7, agent2.getErrors().getRetries());
            assertEquals("fail", agent2.getErrors().getOnFailure());

            AgentConfiguration agent3 = pipeline.getAgents().get(2);
            assertNotNull(agent3.getErrors());
            assertEquals(3, agent3.getErrors().getRetries());
            assertEquals("skip", agent3.getErrors().getOnFailure());

            AgentConfiguration agent4 = pipeline.getAgents().get(3);
            assertNotNull(agent4.getErrors());
            assertEquals(5, agent4.getErrors().getRetries());
            assertEquals("fail", agent4.getErrors().getOnFailure());
        }

        {
            // use system defaults
            Module module = applicationInstance.getModule("module-2");
            Pipeline pipeline = module.getPipelines().get("pipeline-2");

            AgentConfiguration agent1 = pipeline.getAgents().get(0);
            assertNotNull(agent1.getErrors());
            assertEquals(0, agent1.getErrors().getRetries());
            assertEquals("fail", agent1.getErrors().getOnFailure());

            AgentConfiguration agent2 = pipeline.getAgents().get(1);
            assertNotNull(agent2.getErrors());
            assertEquals(0, agent2.getErrors().getRetries());
            assertEquals("skip", agent2.getErrors().getOnFailure());

            AgentConfiguration agent3 = pipeline.getAgents().get(2);
            assertNotNull(agent3.getErrors());
            assertEquals(3, agent3.getErrors().getRetries());
            assertEquals("fail", agent3.getErrors().getOnFailure());

            AgentConfiguration agent4 = pipeline.getAgents().get(3);
            assertNotNull(agent4.getErrors());
            assertEquals(5, agent4.getErrors().getRetries());
            assertEquals("skip", agent4.getErrors().getOnFailure());
        }

    }

    private static String buildInstanceYaml() {
        return """
                instance:
                  streamingCluster:
                    type: "noop"
                  computeCluster:
                    type: "none"
                """;
    }
}