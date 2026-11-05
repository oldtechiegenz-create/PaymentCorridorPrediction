package com.corridoriq.backend.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The /v1/routes/rank gateway and /health against a fake prediction service, so the backend's side of
 * the contract is tested without Python. An end-to-end run against the real service is in code/README.md.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PredictionGatewayTest {

    record Reply(int status, String body) {
    }

    static final HttpServer FAKE;
    static final AtomicReference<Reply> REPLY = new AtomicReference<>();
    static final AtomicReference<String> LAST_BODY = new AtomicReference<>();
    static final AtomicReference<String> LAST_REQUEST_ID = new AtomicReference<>();
    static final AtomicInteger CALLS = new AtomicInteger();

    static final String RANK_RESPONSE = """
            {"model_version": "lgbm-test", "recommended_template_id": "EXP", "routes": [
              {"rank": 1, "template_id": "EXP", "name": "Express", "via": "NY1", "eligible": true,
               "ineligible_reasons": [], "est_hours": 20.5, "naive_hours": 8.0, "est_cost_usd": 31.2,
               "fee_total_usd": 25.0, "fx_bps": 10.0, "hop_count": 3, "intermediaries": 1,
               "est_nonbusiness_days": 0, "calendar_conflict": false, "cutoff_slack_hours": 3.98, "max_risk": 2,
               "stp_rate_nominal": 0.93, "predicted_hours": 22.1, "predicted_cost_usd": 31.5,
               "model_score": 1.2345, "heuristic_score": -0.1, "some_future_field": "ignored"},
              {"rank": null, "template_id": "PAY", "name": "Payout partner", "via": "API", "eligible": false,
               "ineligible_reasons": ["Above the $5,000 per-transfer cap"], "est_hours": 2.0, "naive_hours": 2.0,
               "est_cost_usd": 9.0, "fee_total_usd": 5.0, "fx_bps": 60.0, "hop_count": 2, "intermediaries": 0,
               "est_nonbusiness_days": 0, "calendar_conflict": false, "cutoff_slack_hours": 3.98, "max_risk": 2,
               "stp_rate_nominal": 0.97, "predicted_hours": null, "predicted_cost_usd": null,
               "model_score": null, "heuristic_score": null}]}""";

    static final String HEALTH = """
            {"status": "ok", "model_version": "lgbm-test", "trained_until": "2026-04-01",
             "calendar_years": [2023, 2027]}""";

    static final String PAYMENT = """
            {"corridor_id": "USD-INR", "bene_bank_id": "IN_B2", "client_type": "Retail", "amount_usd": 580,
             "priority": 15, "released_at": "2026-04-01T09:01:00-04:00", "has_deadline": false}""";

    static {
        try {
            FAKE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        FAKE.createContext("/health", ex -> respond(ex, new Reply(200, HEALTH)));
        FAKE.createContext("/v1/routes/rank", ex -> {
            CALLS.incrementAndGet();
            LAST_BODY.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            LAST_REQUEST_ID.set(ex.getRequestHeaders().getFirst("X-Request-Id"));
            respond(ex, REPLY.get());
        });
        FAKE.start();
    }

    @DynamicPropertySource
    static void predictionUrl(DynamicPropertyRegistry registry) {
        registry.add("corridoriq.prediction.url", () -> "http://127.0.0.1:" + FAKE.getAddress().getPort() + "/");
    }

    @AfterAll
    static void stop() {
        FAKE.stop(0);
    }

    private static void respond(HttpExchange ex, Reply reply) throws IOException {
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(reply.status(), body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void reset() {
        REPLY.set(new Reply(200, RANK_RESPONSE));
        LAST_BODY.set(null);
        CALLS.set(0);
    }

    private MockHttpServletResponse rank(String body) throws Exception {
        return mvc.perform(post("/v1/routes/rank").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }

    @Test
    void forwardsSnakeCaseRequestAndReturnsRanking() throws Exception {
        MockHttpServletResponse r = rank(PAYMENT);
        assertThat(r.getStatus()).isEqualTo(200);

        DocumentContext sent = JsonPath.parse(LAST_BODY.get());
        assertThat(sent.read("$.corridor_id", String.class)).isEqualTo("USD-INR");
        assertThat(sent.read("$.bene_bank_id", String.class)).isEqualTo("IN_B2");
        assertThat(sent.read("$.amount_usd", Double.class)).isEqualTo(580.0);
        assertThat(sent.read("$.has_deadline", Boolean.class)).isFalse();
        // The same instant, with its zone: the prediction service needs a zone-aware time.
        assertThat(java.time.OffsetDateTime.parse(sent.read("$.released_at", String.class)).toInstant())
                .isEqualTo(java.time.Instant.parse("2026-04-01T13:01:00Z"));

        DocumentContext d = JsonPath.parse(r.getContentAsString(StandardCharsets.UTF_8));
        assertThat(d.read("$.model_version", String.class)).isEqualTo("lgbm-test");
        assertThat(d.read("$.recommended_template_id", String.class)).isEqualTo("EXP");
        assertThat(d.read("$.routes[0].predicted_hours", Double.class)).isEqualTo(22.1);
        assertThat(d.read("$.routes[0].cutoff_slack_hours", Double.class)).isEqualTo(3.98);
        assertThat(d.read("$.routes[1].rank", Object.class)).isNull();
        assertThat(d.read("$.routes[1].ineligible_reasons[0]", String.class)).contains("per-transfer cap");
        assertThat(r.getContentAsString()).doesNotContain("some_future_field");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"priority\": 15               | \"priority\": 101",
            "\"amount_usd\": 580            | \"amount_usd\": 0",
            "\"client_type\": \"Retail\"    | \"client_type\": \"Bank\"",
            "\"corridor_id\": \"USD-INR\",  | ''",
            "09:01:00-04:00                 | 09:01:00",              // no time zone
            "\"has_deadline\": false}       | ",                       // not JSON
    })
    void invalidRequestsAreRejectedBeforeCallingTheModel(String from, String to) throws Exception {
        MockHttpServletResponse r = rank(PAYMENT.replace(from, to == null ? "" : to));
        assertThat(r.getStatus()).isEqualTo(422);
        assertThat(JsonPath.parse(r.getContentAsString()).read("$.detail", String.class)).isNotBlank();
        assertThat(CALLS.get()).isZero();
    }

    @Test
    void requestIdIsForwardedToThePredictionService() throws Exception {
        MockHttpServletResponse r = mvc.perform(post("/v1/routes/rank").contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "rank-123").content(PAYMENT)).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(LAST_REQUEST_ID.get()).isEqualTo("rank-123");
    }

    @Test
    void hasDeadlineIsOptional() throws Exception {
        MockHttpServletResponse r = rank(PAYMENT.replace(", \"has_deadline\": false", ""));
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(JsonPath.parse(LAST_BODY.get()).read("$.has_deadline", Boolean.class)).isFalse();
    }

    @Test
    void modelValidationErrorsPassThrough() throws Exception {
        String detail = "{\"detail\":\"IN_B9 is not a beneficiary bank on USD-INR; expected one of ['IN_B1', 'IN_B2']\"}";
        REPLY.set(new Reply(422, detail));
        MockHttpServletResponse r = rank(PAYMENT.replace("IN_B2", "IN_B9"));
        assertThat(r.getStatus()).isEqualTo(422);
        assertThat(r.getContentAsString()).isEqualTo(detail);
    }

    @Test
    void modelServerErrorsBecomeBadGateway() throws Exception {
        REPLY.set(new Reply(500, "Internal Server Error"));
        MockHttpServletResponse r = rank(PAYMENT);
        assertThat(r.getStatus()).isEqualTo(502);
        assertThat(JsonPath.parse(r.getContentAsString()).read("$.detail", String.class)).contains("500");
    }

    @Test
    void healthReportsTheModel() throws Exception {
        DocumentContext d = JsonPath.parse(mvc.perform(get("/health")).andReturn().getResponse().getContentAsString());
        assertThat(d.read("$.status", String.class)).isEqualTo("ok");
        assertThat(d.read("$.history_as_of", String.class)).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(d.read("$.prediction_service.model.model_version", String.class)).isEqualTo("lgbm-test");
        assertThat(d.read("$.prediction_service.model.trained_until", String.class)).isEqualTo("2026-04-01");
    }
}
