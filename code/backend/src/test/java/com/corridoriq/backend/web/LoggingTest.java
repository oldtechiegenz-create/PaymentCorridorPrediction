package com.corridoriq.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.corridoriq.backend.history.NamedQueries;
import com.corridoriq.backend.config.CorridorIqProperties;

/** Every API call and every view query shows up in the log, tied together by the request id. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LoggingTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CorridorIqProperties props;

    private List<String> linesWith(CapturedOutput output, String requestId) {
        return output.getOut().lines().filter(l -> l.contains("[" + requestId + "]")).toList();
    }

    @Test
    void apiCallAndItsViewQueriesAreLogged(CapturedOutput output) throws Exception {
        MockHttpServletResponse r = mvc.perform(get("/v1/history/corridors/USD-INR/incidents?category=batch&limit=5")
                .header("X-Request-Id", "test-incidents")).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getHeader("X-Request-Id")).isEqualTo("test-incidents");

        List<String> lines = linesWith(output, "test-incidents");
        assertThat(lines).anyMatch(l -> l.contains("--> GET /v1/history/corridors/USD-INR/incidents?category=batch&limit=5"));
        assertThat(lines).anyMatch(l -> l.contains("SQL incident_count on [v_incident] {category=batch, corridor=USD-INR}"));
        assertThat(lines).anyMatch(l -> l.contains("SQL incident_categories on [v_incident]"));
        assertThat(lines).anyMatch(l -> l.contains("SQL incidents on [v_incident]") && l.contains("-> 5 rows"));
        assertThat(lines).anyMatch(l -> l.contains("<-- GET /v1/history/corridors/USD-INR/incidents?category=batch&limit=5 200"));
    }

    @Test
    void rejectedCallsAreLoggedAsWarnings(CapturedOutput output) throws Exception {
        mvc.perform(get("/v1/history/corridors/USD-JPY/fx?days=3").header("X-Request-Id", "test-bad-days"));
        List<String> lines = linesWith(output, "test-bad-days");
        assertThat(lines).anyMatch(l -> l.contains("WARN") && l.contains("Rejected with 422: days must be between 7 and 365"));
        assertThat(lines).anyMatch(l -> l.contains("WARN") && l.contains("<-- GET /v1/history/corridors/USD-JPY/fx?days=3 422"));
    }

    @Test
    void unsafeRequestIdsAreReplaced(CapturedOutput output) throws Exception {
        String id = mvc.perform(get("/v1/history/corridors").header("X-Request-Id", "evil\nINFO forged line"))
                .andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(id).matches("[0-9a-f]{8}");
        assertThat(output.getOut()).doesNotContain("forged line");
    }

    @Test
    void everyQueryKnowsTheViewsItReads() {
        NamedQueries q = NamedQueries.load(props.sqlPath());
        assertThat(q.sources("similar_cases")).containsExactly("v_case", "v_history_window");
        assertThat(q.sources("route_performance")).containsExactly("v_route_performance_18m");
        assertThat(q.sources("fx_series")).containsExactly("v_fx_corridor");
        assertThat(q.sources("corridors")).containsExactly("v_corridor_overview");
    }
}
