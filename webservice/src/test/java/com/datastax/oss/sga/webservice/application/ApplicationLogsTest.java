package com.datastax.oss.sga.webservice.application;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.datastax.oss.sga.impl.k8s.tests.KubeTestServer;
import com.datastax.oss.sga.webservice.WebAppTestConfig;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import java.net.HttpURLConnection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultHandler;

@SpringBootTest(properties = {"application.tenants.default-tenant.create=false"})
@AutoConfigureMockMvc
@Slf4j
@Import(WebAppTestConfig.class)
@DirtiesContext
class ApplicationLogsTest {

    @Autowired
    MockMvc mockMvc;

    @RegisterExtension
    static final KubeTestServer k3s = new KubeTestServer();

    @Test
    void test() throws Exception {
        k3s.expectTenantCreated("default");
        mockMvc.perform(put("/api/tenants/default"))
                .andExpect(status().isOk());

        final Pod pod1 = Mockito.mock(Pod.class);

        when(pod1.getMetadata()).thenReturn(new ObjectMetaBuilder()
                .withName("pod1")
                .build());
        final Pod pod2 = Mockito.mock(Pod.class);
        when(pod2.getMetadata()).thenReturn(new ObjectMetaBuilder()
                .withName("pod2")
                .build());
        k3s.getServer().expect()
                .get()
                .withPath("/api/v1/namespaces/sga-default/pods?labelSelector=app%3Dsga-runtime%2Csga-application%3Dtest")
                .andReply(
                        HttpURLConnection.HTTP_OK,
                        recordedRequest -> {
                            final PodList res = new PodList();
                            res.setItems(List.of(pod1, pod2));
                            return res;
                        }
                ).always();


        mockMvc
                .perform(
                        get("/api/applications/default/test/logs")
                )
                .andExpect(status().isOk());

    }

}