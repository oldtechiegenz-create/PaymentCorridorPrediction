package com.corridoriq.backend.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

/** With the prediction service down, ranking is 503 and health is degraded; the history API still works. */
@SpringBootTest
@AutoConfigureMockMvc
class PredictionUnavailableTest {

    @DynamicPropertySource
    static void deadUrl(DynamicPropertyRegistry registry) throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {   // a free port with nothing listening once closed
            port = s.getLocalPort();
        }
        String url = "http://127.0.0.1:" + port;
        registry.add("corridoriq.prediction.url", () -> url);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void rankingIsUnavailable() throws Exception {
        MockHttpServletResponse r = mvc.perform(post("/v1/routes/rank").contentType(MediaType.APPLICATION_JSON)
                .content(PredictionGatewayTest.PAYMENT)).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(503);
        assertThat(JsonPath.parse(r.getContentAsString()).read("$.detail", String.class))
                .contains("Cannot reach the prediction service");
    }

    @Test
    void healthIsDegradedButHistoryWorks() throws Exception {
        DocumentContext d = JsonPath.parse(mvc.perform(get("/health")).andReturn().getResponse().getContentAsString());
        assertThat(d.read("$.status", String.class)).isEqualTo("degraded");
        assertThat(d.read("$.prediction_service.status", String.class)).isEqualTo("unavailable");
        assertThat(mvc.perform(get("/v1/history/corridors")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
