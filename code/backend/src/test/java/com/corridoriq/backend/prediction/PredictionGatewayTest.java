package com.corridoriq.backend.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
 * POST /v1/routes/rank and /health against a fake scoring service, so the backend's side of the contract is
 * tested without Python: what it sends, how it ranks the scores it gets back, and how it reports failures.
 * RouteRankerTest covers the ranking rules without HTTP; an end-to-end run against the real service is in
 * code/README.md.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PredictionGatewayTest {

    record Reply(int status, String body) {
    }

    static final HttpServer FAKE;
    /** null = answer with default scores for whatever routes were sent; otherwise this fixed reply. */
    static final AtomicReference<Reply> REPLY = new AtomicReference<>();
    static final AtomicReference<String> LAST_BODY = new AtomicReference<>();
    static final AtomicReference<String> LAST_REQUEST_ID = new AtomicReference<>();
    static final AtomicInteger CALLS = new AtomicInteger();

    /** Model scores the fake gives each route, so the ranking is known: EXP, PAY, ASIA, LDN, GULF, ECO. */
    static final Map<String, Double> SCORES = Map.of("EXP", 1.2345, "PAY", 0.9, "ASIA", 0.5, "LDN", 0.1, "GULF", -0.3, "ECO", -1.0);

    static final String HEALTH = """
            {"status": "ok", "model_version": "lgbm-test", "trained_until": "2026-04-01"}""";

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
        FAKE.createContext("/v1/routes/score", ex -> {
            CALLS.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            LAST_BODY.set(body);
            LAST_REQUEST_ID.set(ex.getRequestHeaders().getFirst("X-Request-Id"));
            respond(ex, REPLY.get() != null ? REPLY.get() : new Reply(200, defaultScores(body)));
        });
        FAKE.start();
    }

    /** A scoring answer for the routes in the request, with an extra field the backend must ignore. */
    static String defaultScores(String requestBody) {
        List<String> templates = JsonPath.parse(requestBody).read("$.routes[*].template_id");
        return "{\"model_version\": \"lgbm-test\", \"scores\": [" + templates.stream().map(t -> String.format(
                "{\"template_id\": \"%s\", \"model_score\": %s, \"predicted_hours\": 22.1, \"predicted_cost_usd\": 31.5, "
                        + "\"heuristic_score\": -0.1, \"some_future_field\": \"ignored\"}", t, SCORES.get(t)))
                .collect(Collectors.joining(",")) + "]}";
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
        REPLY.set(null);
        LAST_BODY.set(null);
        CALLS.set(0);
    }

    private MockHttpServletResponse rank(String body) throws Exception {
        return mvc.perform(post("/v1/routes/rank").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }

    @Test
    void sendsTheModelInputsAsSnakeCaseAndReturnsTheRankedRoutes() throws Exception {
        MockHttpServletResponse r = rank(PAYMENT);
        assertThat(r.getStatus()).isEqualTo(200);

        // What the Python service receives: payment context, then one feature row per eligible route.
        DocumentContext sent = JsonPath.parse(LAST_BODY.get());
        assertThat(sent.read("$.payment.corridor_id", String.class)).isEqualTo("USD-INR");
        assertThat(sent.read("$.payment.bene_bank_id", String.class)).isEqualTo("IN_B2");
        assertThat(sent.read("$.payment.client_type", String.class)).isEqualTo("Retail");
        assertThat(sent.read("$.payment.amount_usd", Double.class)).isEqualTo(580.0);
        assertThat(sent.read("$.payment.priority", Integer.class)).isEqualTo(15);
        assertThat(sent.read("$.payment.release_hour_et", Double.class)).isEqualTo(9.02);
        assertThat(sent.read("$.payment.release_weekday", Integer.class)).isEqualTo(3);
        assertThat(sent.read("$.payment.day_of_month", Integer.class)).isEqualTo(1);
        assertThat(sent.read("$.payment.has_deadline", Integer.class)).isZero();
        assertThat(sent.read("$.routes[*].template_id", List.class)).containsExactly("EXP", "ASIA", "LDN", "GULF", "PAY", "ECO");
        assertThat(sent.read("$.routes[0].est_hours", Double.class)).isPositive();
        assertThat(sent.read("$.routes[0].calendar_conflict", Integer.class)).isBetween(0, 1);
        assertThat(sent.read("$.routes[0].stp_rate_nominal", Double.class)).isEqualTo(0.972);

        // What the caller receives: ranked by model score, with the simulator estimate and the model outputs.
        DocumentContext d = JsonPath.parse(r.getContentAsString(StandardCharsets.UTF_8));
        assertThat(d.read("$.model_version", String.class)).isEqualTo("lgbm-test");
        assertThat(d.read("$.recommended_template_id", String.class)).isEqualTo("EXP");
        assertThat(d.read("$.routes[*].template_id", List.class)).containsExactly("EXP", "PAY", "ASIA", "LDN", "GULF", "ECO");
        assertThat(d.read("$.routes[*].rank", List.class)).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(d.read("$.routes[0].model_score", Double.class)).isEqualTo(1.2345);
        assertThat(d.read("$.routes[0].predicted_hours", Double.class)).isEqualTo(22.1);
        assertThat(d.read("$.routes[0].name", String.class)).isEqualTo("Express gpi");
        assertThat(d.read("$.routes[0].est_hours", Double.class)).isPositive();
        assertThat(d.read("$.routes[0].cutoff_slack_hours", Double.class)).isNotNull();
        assertThat(r.getContentAsString()).doesNotContain("some_future_field");
    }

    @Test
    void routesTheClientCannotUseAreNotScoredAndComeLast() throws Exception {
        MockHttpServletResponse r = rank(PAYMENT.replace("\"amount_usd\": 580", "\"amount_usd\": 30000"));
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(JsonPath.parse(LAST_BODY.get()).read("$.routes[*].template_id", List.class))
                .containsExactly("EXP", "ASIA", "LDN", "GULF");
        DocumentContext d = JsonPath.parse(r.getContentAsString(StandardCharsets.UTF_8));
        assertThat(d.read("$.routes[*].template_id", List.class)).containsExactly("EXP", "ASIA", "LDN", "GULF", "PAY", "ECO");
        assertThat(d.read("$.routes[4].rank", Object.class)).isNull();
        assertThat(d.read("$.routes[4].model_score", Object.class)).isNull();
        assertThat(d.read("$.routes[4].ineligible_reasons[0]", String.class)).contains("per-transfer cap");
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

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"corridor_id\": \"USD-INR\"        | \"corridor_id\": \"USD-XXX\"          | Unknown corridor USD-XXX",
            "\"bene_bank_id\": \"IN_B2\"         | \"bene_bank_id\": \"JP_B1\"           | JP_B1 is not a beneficiary bank on USD-INR",
            "2026-04-01T09:01:00-04:00           | 2031-04-01T09:01:00-04:00             | Banking calendar only covers 2023-2027",
    })
    void paymentsTheBackendCanTellAreWrongAreRejectedWithAReasonBeforeCallingTheModel(String from, String to, String detail)
            throws Exception {
        MockHttpServletResponse r = rank(PAYMENT.replace(from, to));
        assertThat(r.getStatus()).isEqualTo(422);
        assertThat(JsonPath.parse(r.getContentAsString()).read("$.detail", String.class)).contains(detail);
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
        assertThat(JsonPath.parse(LAST_BODY.get()).read("$.payment.has_deadline", Integer.class)).isZero();
        r = rank(PAYMENT.replace("\"has_deadline\": false", "\"has_deadline\": true"));
        assertThat(JsonPath.parse(LAST_BODY.get()).read("$.payment.has_deadline", Integer.class)).isEqualTo(1);
    }

    @Test
    void modelValidationErrorsPassThrough() throws Exception {
        String detail = "{\"detail\":\"Unknown template_id ZZZ; the models know ['ASIA', 'ECO']\"}";
        REPLY.set(new Reply(422, detail));
        MockHttpServletResponse r = rank(PAYMENT);
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
    void scoresForTheWrongRoutesBecomeBadGateway() throws Exception {
        REPLY.set(new Reply(200, "{\"model_version\": \"lgbm-test\", \"scores\": [{\"template_id\": \"EXP\", "
                + "\"model_score\": 1, \"predicted_hours\": 1, \"predicted_cost_usd\": 1, \"heuristic_score\": 1}]}"));
        MockHttpServletResponse r = rank(PAYMENT);
        assertThat(r.getStatus()).isEqualTo(502);
        assertThat(JsonPath.parse(r.getContentAsString()).read("$.detail", String.class)).contains("were sent");
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
