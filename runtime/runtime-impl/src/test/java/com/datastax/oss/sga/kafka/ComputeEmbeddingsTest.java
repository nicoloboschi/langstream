package com.datastax.oss.sga.kafka;

import com.dastastax.oss.sga.kafka.runtime.KafkaTopic;
import com.datastax.oss.sga.api.model.Application;
import com.datastax.oss.sga.api.model.Connection;
import com.datastax.oss.sga.api.model.Module;
import com.datastax.oss.sga.api.model.TopicDefinition;
import com.datastax.oss.sga.api.runtime.ClusterRuntimeRegistry;
import com.datastax.oss.sga.api.runtime.ExecutionPlan;
import com.datastax.oss.sga.api.runtime.PluginsRegistry;
import com.datastax.oss.sga.common.AbstractApplicationRunner;
import com.datastax.oss.sga.deployer.k8s.agents.AgentResourcesFactory;
import com.datastax.oss.sga.impl.deploy.ApplicationDeployer;
import com.datastax.oss.sga.impl.k8s.tests.KubeTestServer;
import com.datastax.oss.sga.impl.parser.ModelBuilder;
import com.datastax.oss.sga.runtime.agent.AgentRunner;
import com.datastax.oss.sga.runtime.api.agent.RuntimePodConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.fabric8.kubernetes.api.model.Secret;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.utility.DockerImageName;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@WireMockTest
class ComputeEmbeddingsTest extends AbstractApplicationRunner {


    @AllArgsConstructor
    private static class EmbeddingsConfig {
        String model;
        String providerConfiguration;
        Runnable stubMakers;

        @Override
        public String toString() {
            return "EmbeddingsConfig{" +
                    "model='" + model + '\'' +
                    '}';
        }
    }


    static WireMockRuntimeInfo wireMockRuntimeInfo;

    @BeforeAll
    static void onBeforeAll(WireMockRuntimeInfo info){
        wireMockRuntimeInfo = info;
    }


    private static Stream<Arguments> providers() {
        return Stream.of(
                Arguments.of(
                        new EmbeddingsConfig("textembedding-gecko", """
                             configuration:
                                 resources:
                                    - type: "vertex-configuration"
                                      name: "Vertex configuration"
                                      configuration:                                        
                                        url: "%s"     
                                        region: "us-east1"
                                        project: "the-project"
                                        token: "some-token"
                        """.formatted(wireMockRuntimeInfo.getHttpBaseUrl()),
                                () -> {
                                    stubFor(post("/v1/projects/the-project/locations/us-east1/publishers/google/models/textembedding-gecko:predict")
                                            .willReturn(okJson(""" 
                                               {
                                                  "predictions": [
                                                    {
                                                      "embeddings": {
                                                        "statistics": {
                                                          "truncated": false,
                                                          "token_count": 6
                                                        },
                                                        "values": [ 1.0, 5.4, 8.7]
                                                      }
                                                    }
                                                  ]
                                                }
                """)));
                                })),
                Arguments.of(new EmbeddingsConfig("some-model", """
                             configuration:
                                 resources:
                                    - type: "hugging-face-configuration"
                                      name: "Hugging Face API configuration"
                                      configuration:                                        
                                        api-url: "%s"
                                        model-check-url: "%s"                                             
                                        access-key: "some-token"
                                        provider: "api"
                        """.formatted(wireMockRuntimeInfo.getHttpBaseUrl()+"/embeddings/",
                        wireMockRuntimeInfo.getHttpBaseUrl()+"/modelcheck/"),
                                () -> {
                                    stubFor(get("/modelcheck/some-model")
                                            .willReturn(okJson("{\"modelId\": \"some-model\",\"tags\": [\"sentence-transformers\"]}")));
                                    stubFor(post("/embeddings/some-model")
                                            .willReturn(okJson("[[1.0, 5.4, 8.7]]")));
                                    }
                )));
    }


    @ParameterizedTest
    @MethodSource("providers")
    public void testComputeEmbeddings(EmbeddingsConfig config) throws Exception {
        wireMockRuntimeInfo.getWireMock().allStubMappings().getMappings().forEach(stubMapping -> {
            log.info("Removing stub {}", stubMapping);
            wireMockRuntimeInfo.getWireMock().removeStubMapping(stubMapping);
        });
        config.stubMakers.run();
        // wait for WireMock to be ready
        Thread.sleep(1000);

        final String appId = "application-" + UUID.randomUUID();
        String inputTopic = "input-topic-" + UUID.randomUUID();
        String outputTopic = "output-topic-" + UUID.randomUUID();
        String tenant = "tenant";

        String[] expectedAgents = new String[] {appId + "-step1"};

        Map<String, String> application = Map.of(
                "configuration.yaml",
                config.providerConfiguration,
                "instance.yaml",
                buildInstanceYaml(),
                "module.yaml", """
                                module: "module-1"
                                id: "pipeline-1"
                                topics:
                                  - name: "%s"
                                    creation-mode: create-if-not-exists
                                  - name: "%s"
                                    creation-mode: create-if-not-exists
                                pipeline:
                                  - name: "compute-embeddings"
                                    id: "step1"
                                    type: "compute-ai-embeddings"
                                    input: "%s"
                                    output: "%s"
                                    configuration:                                      
                                      model: "%s"
                                      embeddings-field: "value.embeddings"
                                      text: "something to embed"
                                """.formatted(inputTopic, outputTopic, inputTopic, outputTopic, config.model));
        try (ApplicationRuntime applicationRuntime = deployApplication(tenant, appId, application, expectedAgents);) {


            ExecutionPlan implementation = applicationRuntime.implementation();
            Application applicationInstance = applicationRuntime.applicationInstance();

            Module module = applicationInstance.getModule("module-1");
            assertTrue(implementation.getConnectionImplementation(module,
                    Connection.from(TopicDefinition.fromName(inputTopic))) instanceof KafkaTopic);

            Set<String> topics = getKafkaAdmin().listTopics().names().get();
            log.info("Topics {}", topics);
            assertTrue(topics.contains(inputTopic));

            try (KafkaProducer<String, String> producer = createProducer();
                 KafkaConsumer<String, String> consumer = createConsumer(outputTopic)) {

                // produce one message to the input-topic
                sendMessage(inputTopic, "{\"name\": \"some name\", \"description\": \"some description\"}", producer);

                executeAgentRunners(applicationRuntime);

                waitForMessages(consumer, List.of("{\"name\":\"some name\",\"description\":\"some description\",\"embeddings\":[1.0,5.4,8.7]}"));
            }

        }

    }



}