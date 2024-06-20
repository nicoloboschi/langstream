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

import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

import ai.langstream.agents.azureblobstorage.AzureBlobStorageSource;
import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobContainerClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Slf4j
@Testcontainers
class AzureBlobStorageSourceIT extends AbstractKafkaApplicationRunner {

    @Container
    private static final LocalStackContainer localstack =
            new LocalStackContainer(
                            markAsDisposableImage(
                                    DockerImageName.parse("localstack/localstack:2.2.0")))
                    .withServices(S3);

    @Container
    private static final GenericContainer<?> azurite =
            new GenericContainer<>(
                            markAsDisposableImage(
                                    DockerImageName.parse(
                                            "mcr.microsoft.com/azure-storage/azurite:latest")))
                    .withExposedPorts(10000)
                    .withCommand("azurite-blob --blobHost 0.0.0.0")
                    .withLogConsumer(
                            outputFrame -> log.info("azurite> {}", outputFrame.getUtf8String()));

    @Test
    public void test() throws Exception {

        final String appId = "app-" + UUID.randomUUID().toString().substring(0, 4);

        String tenant = "tenant";

        String[] expectedAgents = new String[] {appId + "-step1"};
        String s3endpoint = localstack.getEndpointOverride(S3).toString();
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
                                  - type: "azure-blob-storage-source"
                                    id: "step1"
                                    output: "${globals.output-topic}"
                                    configuration:\s
                                        endpoint: http://0.0.0.0:%s/devstoreaccount1
                                        storage-account-name: devstoreaccount1
                                        storage-account-key: Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==
                                        container: test-container
                                        state-storage: s3
                                        state-storage-s3-bucket: "test-state-bucket"
                                        state-storage-s3-endpoint: "%s"
                                        deleted-objects-topic: "deleted-objects"
                                        delete-objects: false
                                        idle-time: 1
                                """
                                .formatted(azurite.getMappedPort(10000), s3endpoint));

        BlobContainerClient containerClient =
                AzureBlobStorageSource.createContainerClient(
                        Map.of(
                                "endpoint",
                                        "http://0.0.0.0:"
                                                + azurite.getMappedPort(10000)
                                                + "/devstoreaccount1",
                                // default credentials for azurite
                                // https://github.com/Azure/Azurite?tab=readme-ov-file#default-storage-account
                                "storage-account-name", "devstoreaccount1",
                                "storage-account-key",
                                        "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==",
                                "container", "test-container"));

        for (int i = 0; i < 2; i++) {
            final String s = "content" + i;
            containerClient.getBlobClient("test-" + i + ".txt").upload(BinaryData.fromString(s));
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

                containerClient.getBlobClient("test-0.txt").delete();

                executeAgentRunners(applicationRuntime);

                waitForMessages(deletedDocumentsConsumer, List.of("test-0.txt"));
            }
        }
    }
}
