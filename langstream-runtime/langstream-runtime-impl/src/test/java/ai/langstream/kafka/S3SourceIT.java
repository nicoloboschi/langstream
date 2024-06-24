/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.langstream.kafka;

import static org.junit.jupiter.api.Assertions.*;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Slf4j
@Testcontainers
class S3SourceIT extends AbstractKafkaApplicationRunner {

    @Container
    private static final LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:2.2.0"))
                    .withServices(S3);

    @Test
    public void test() throws Exception {

        final String appId = "app-" + UUID.randomUUID().toString().substring(0, 4);

        String tenant = "tenant";

        String[] expectedAgents = new String[] {appId + "-step1"};
        String endpoint = localstack.getEndpointOverride(S3).toString();
        Map<String, String> application =
                Map.of(
                        "module.yaml",
                        """
                                module: "module-1"
                                id: "pipeline-1"
                                topics:
                                  - name: "${globals.output-topic}"
                                    creation-mode: create-if-not-exists
                                  - name: "deleted-documents"
                                    creation-mode: create-if-not-exists
                                pipeline:
                                  - type: "s3-source"
                                    id: "step1"
                                    output: "${globals.output-topic}"
                                    configuration:\s
                                        bucketName: "test-bucket"
                                        endpoint: "%s"
                                        state-storage: s3
                                        state-storage-s3-bucket: "test-state-bucket"
                                        state-storage-s3-endpoint: "%s"
                                        deleted-objects-topic: "deleted-objects"
                                        delete-objects: false
                                        idle-time: 1
                                """
                                .formatted(endpoint, endpoint));

        MinioClient minioClient = MinioClient.builder().endpoint(endpoint).build();

        minioClient.makeBucket(MakeBucketArgs.builder().bucket("test-bucket").build());

        for (int i = 0; i < 2; i++) {
            final String s = "content" + i;
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket("test-bucket")
                            .object("test-" + i + ".txt")
                            .stream(
                                    new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)),
                                    s.length(),
                                    -1)
                            .build());
        }

        try (ApplicationRuntime applicationRuntime =
                deployApplication(
                        tenant, appId, application, buildInstanceYaml(), expectedAgents)) {

            try (KafkaConsumer<String, String> deletedDocumentsConsumer =
                            createConsumer("deleted-objects");
                    KafkaConsumer<String, String> consumer =
                            createConsumer(applicationRuntime.getGlobal("output-topic")); ) {

                executeAgentRunners(applicationRuntime);

                waitForMessages(consumer, List.of("content0", "content1"));

                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket("test-bucket")
                                .object("test-0.txt")
                                .build());

                executeAgentRunners(applicationRuntime);

                waitForMessages(deletedDocumentsConsumer, List.of("test-0.txt"));
            }
        }
    }

    @Test
    public void testSourceActivitySummary() throws Exception {

        final String appId = "app-" + UUID.randomUUID().toString().substring(0, 4);

        String tenant = "tenant";

        String[] expectedAgents = new String[] {appId + "-step1"};
        String endpoint = localstack.getEndpointOverride(S3).toString();
        Map<String, String> application =
                Map.of(
                        "module.yaml",
                        """
                                module: "module-1"
                                id: "pipeline-1"
                                topics:
                                  - name: "${globals.output-topic}"
                                    creation-mode: create-if-not-exists
                                  - name: "deleted-documents"
                                    creation-mode: create-if-not-exists
                                pipeline:
                                  - type: "s3-source"
                                    id: "step1"
                                    output: "${globals.output-topic}"
                                    configuration:\s
                                        bucketName: "test-bucket"
                                        endpoint: "%s"
                                        state-storage: s3
                                        state-storage-s3-bucket: "test-state-bucket"
                                        state-storage-s3-endpoint: "%s"
                                        deleted-objects-topic: "deleted-objects"
                                        delete-objects: false
                                        source-activity-summary-topic: "s3-bucket-activity"
                                        source-activity-summary-events: "new,updated,deleted"
                                        source-activity-summary-events-threshold: 2
                                        source-activity-summary-time-seconds-threshold: 5
                                        idle-time: 1
                                """
                                .formatted(endpoint, endpoint));

        MinioClient minioClient = MinioClient.builder().endpoint(endpoint).build();

        minioClient.makeBucket(MakeBucketArgs.builder().bucket("test-bucket").build());

        for (int i = 0; i < 2; i++) {
            final String s = "content" + i;
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket("test-bucket")
                            .object("test-" + i + ".txt")
                            .stream(
                                    new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)),
                                    s.length(),
                                    -1)
                            .build());
        }

        try (ApplicationRuntime applicationRuntime =
                deployApplication(
                        tenant, appId, application, buildInstanceYaml(), expectedAgents)) {

            try (KafkaConsumer<String, String> deletedDocumentsConsumer =
                            createConsumer("deleted-objects");
                    KafkaConsumer<String, String> activitiesConsumer =
                            createConsumer("s3-bucket-activity");
                    KafkaConsumer<String, String> consumer =
                            createConsumer(applicationRuntime.getGlobal("output-topic")); ) {

                executeAgentRunners(applicationRuntime);

                waitForMessages(consumer, List.of("content0", "content1"));

                minioClient.putObject(
                        PutObjectArgs.builder().bucket("test-bucket").object("test-0.txt").stream(
                                        new ByteArrayInputStream(
                                                "another".getBytes(StandardCharsets.UTF_8)),
                                        "another".length(),
                                        -1)
                                .build());

                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket("test-bucket")
                                .object("test-1.txt")
                                .build());

                executeAgentRunners(applicationRuntime);

                waitForMessages(deletedDocumentsConsumer, List.of("test-1.txt"));

                waitForMessages(
                        activitiesConsumer,
                        List.of(
                                new java.util.function.Consumer<Object>() {
                                    @Override
                                    @SneakyThrows
                                    public void accept(Object o) {
                                        Map map =
                                                new ObjectMapper().readValue((String) o, Map.class);

                                        List<Map<String, Object>> newObjects =
                                                (List<Map<String, Object>>) map.get("newObjects");
                                        List<Map<String, Object>> updatedObjects =
                                                (List<Map<String, Object>>)
                                                        map.get("updatedObjects");
                                        List<Map<String, Object>> deletedObjects =
                                                (List<Map<String, Object>>)
                                                        map.get("deletedObjects");
                                        assertTrue(updatedObjects.isEmpty());
                                        assertTrue(deletedObjects.isEmpty());
                                        assertEquals(2, newObjects.size());
                                        assertEquals(
                                                "test-bucket", newObjects.get(0).get("bucket"));
                                        assertEquals("test-0.txt", newObjects.get(0).get("object"));
                                        assertNotNull(newObjects.get(0).get("detectedAt"));
                                        assertEquals(
                                                "test-bucket", newObjects.get(1).get("bucket"));
                                        assertEquals("test-1.txt", newObjects.get(1).get("object"));
                                        assertNotNull(newObjects.get(1).get("detectedAt"));
                                    }
                                },
                                new java.util.function.Consumer<Object>() {
                                    @Override
                                    @SneakyThrows
                                    public void accept(Object o) {
                                        Map map =
                                                new ObjectMapper().readValue((String) o, Map.class);
                                        List<Map<String, Object>> newObjects =
                                                (List<Map<String, Object>>) map.get("newObjects");
                                        List<Map<String, Object>> updatedObjects =
                                                (List<Map<String, Object>>)
                                                        map.get("updatedObjects");
                                        List<Map<String, Object>> deletedObjects =
                                                (List<Map<String, Object>>)
                                                        map.get("deletedObjects");
                                        assertTrue(newObjects.isEmpty());
                                        assertEquals(1, updatedObjects.size());
                                        assertEquals(1, deletedObjects.size());
                                        assertEquals(
                                                "test-bucket", updatedObjects.get(0).get("bucket"));
                                        assertEquals(
                                                "test-0.txt", updatedObjects.get(0).get("object"));
                                        assertNotNull(updatedObjects.get(0).get("detectedAt"));
                                        assertEquals(
                                                "test-bucket", deletedObjects.get(0).get("bucket"));
                                        assertEquals(
                                                "test-1.txt", deletedObjects.get(0).get("object"));
                                        assertNotNull(deletedObjects.get(0).get("detectedAt"));
                                    }
                                }));
            }
        }
    }
}
