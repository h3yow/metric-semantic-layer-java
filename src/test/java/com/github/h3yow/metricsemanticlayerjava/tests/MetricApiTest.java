package com.github.h3yow.metricsemanticlayerjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层冒烟：查询可用、默认版本可见、畸形查询返回可区分错误码。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MetricApiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void queryDefaultGmv() throws Exception {
        mvc.perform(post("/api/metrics/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"metricName":"gmv","metricVersion":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metricVersion").value(2))
                .andExpect(jsonPath("$.rows[0].gmv").value(1300.0));
    }

    @Test
    void registryListsVersions() throws Exception {
        mvc.perform(get("/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.gmv.versions[0]").value(1))
                .andExpect(jsonPath("$.metrics.aov.defaultVersion").value(1));
    }

    @Test
    void unknownMetricMappedTo404WithDiscriminator() throws Exception {
        mvc.perform(post("/api/metrics/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"metricName":"ghost"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorType").value("MetricNotFoundException"));
    }

    @Test
    void badRequestUnknownFieldMappedTo400() throws Exception {
        mvc.perform(post("/api/metrics/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"metricName":"gmv","metricVersion":1,"dimensions":["nope"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorType").value("UnknownFieldException"));
    }

    @Test
    void switchDefaultVersionViaApi() throws Exception {
        mvc.perform(post("/api/metrics/gmv/default").param("version", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultVersion").value(2));
        // 还原，避免影响其它测试对默认版本的假设
        mvc.perform(post("/api/metrics/gmv/default").param("version", "1"))
                .andExpect(status().isOk());
    }
}
