package com.corridoriq.backend.prediction;

import java.net.http.HttpClient;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.corridoriq.backend.config.CorridorIqProperties;
import com.corridoriq.backend.prediction.PredictionModels.ModelHealth;
import com.corridoriq.backend.prediction.PredictionModels.PaymentRequest;
import com.corridoriq.backend.prediction.PredictionModels.RankResponse;
import com.corridoriq.backend.web.RequestLoggingFilter;

/**
 * HTTP client for the Python prediction service, which hosts the LightGBM ranker and the time/cost
 * regressors. Uses the application's JSON settings (snake_case) and the configured timeouts. Every call
 * is logged with its outcome and duration, and carries the caller's request id in X-Request-Id.
 */
@Component
public class PredictionClient {

    private static final Logger log = LoggerFactory.getLogger(PredictionClient.class);

    private final RestClient http;
    private final String baseUrl;

    public PredictionClient(RestClient.Builder builder, CorridorIqProperties props) {
        CorridorIqProperties.Prediction p = props.prediction();
        this.baseUrl = p.url().replaceAll("/+$", "");
        // HTTP/1.1: uvicorn does not speak cleartext HTTP/2.
        HttpClient jdk = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(p.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(p.readTimeout());
        this.http = builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    String id = MDC.get(RequestLoggingFilter.MDC_KEY);
                    if (id != null) {
                        request.getHeaders().set(RequestLoggingFilter.HEADER, id);
                    }
                    return execution.execute(request, body);
                })
                .build();
        log.info("Prediction service at {} (connect timeout {}, read timeout {})", baseUrl, p.connectTimeout(),
                p.readTimeout());
    }

    public String baseUrl() {
        return baseUrl;
    }

    public ModelHealth health() {
        return call("GET /health", () -> http.get().uri("/health").retrieve().body(ModelHealth.class),
                h -> "model " + h.modelVersion());
    }

    /** Every route of the corridor: eligible ones ranked best first, then ineligible ones with reasons. */
    public RankResponse rankRoutes(PaymentRequest payment) {
        log.info("ML POST /v1/routes/rank: {} {} {} USD {} priority {} released {}", payment.corridorId(),
                payment.beneBankId(), payment.clientType(), payment.amountUsd(), payment.priority(),
                payment.releasedAt());
        return call("POST /v1/routes/rank", () -> http.post().uri("/v1/routes/rank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payment)
                        .retrieve()
                        .body(RankResponse.class),
                r -> "recommended " + r.recommendedTemplateId() + " of " + r.routes().size() + " routes, model "
                        + r.modelVersion());
    }

    private <T> T call(String what, Supplier<T> request, Function<T, String> summary) {
        long start = System.nanoTime();
        try {
            T result = request.get();
            log.info("ML {} -> 200 {} ({} ms)", what, summary.apply(result), millis(start));
            return result;
        } catch (RestClientResponseException e) {
            HttpStatusCode upstream = e.getStatusCode();
            log.warn("ML {} -> {} ({} ms): {}", what, upstream.value(), millis(start), e.getResponseBodyAsString());
            if (upstream.is4xxClientError()) {
                // The service's own validation answer (e.g. unknown corridor): pass it through unchanged.
                throw failure(upstream, e.getResponseBodyAsString(), what, e);
            }
            throw failure(HttpStatus.BAD_GATEWAY,
                    detail("Prediction service error " + upstream.value() + " on " + what), what, e);
        } catch (ResourceAccessException e) {
            log.error("ML {} -> unreachable at {} ({} ms): {}", what, baseUrl, millis(start),
                    e.getMostSpecificCause().toString());
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, detail("Cannot reach the prediction service at " + baseUrl
                    + ". Start it from the code folder: python3 -m uvicorn prediction_service.api:app --port 8001"),
                    what, e);
        }
    }

    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static PredictionServiceException failure(HttpStatusCode status, String body, String what,
                                                      Exception cause) {
        return new PredictionServiceException(status, body, what + " -> " + status.value(), cause);
    }

    /** {"detail": message}; the messages here hold no control characters, only quotes need escaping. */
    private static String detail(String message) {
        return "{\"detail\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }
}
