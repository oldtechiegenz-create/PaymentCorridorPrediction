package com.corridoriq.backend.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Logs every API call: one line when it arrives, one when it completes, with status and duration.
 *
 * <p>Each request gets an id, taken from the caller's {@code X-Request-Id} header or generated. It is put
 * in the logging context (so every log line of the request shows it, including the SQL and prediction-service
 * lines), returned in the response header, and forwarded to the prediction service.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)   // outermost, so CORS rejections and errors are logged too
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    /** Accept a caller's id only if it is short and plain, so it cannot forge log lines. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        String id = given != null && SAFE_ID.matcher(given).matches()
                ? given : UUID.randomUUID().toString().substring(0, 8);
        String target = request.getQueryString() == null
                ? request.getRequestURI() : request.getRequestURI() + "?" + request.getQueryString();

        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        long start = System.nanoTime();
        log.info("--> {} {}", request.getMethod(), target);
        try {
            chain.doFilter(request, response);
            completed(request.getMethod(), target, response.getStatus(), start);
        } catch (IOException | ServletException | RuntimeException e) {
            log.error("<-- {} {} 500 failed after {} ms: {}", request.getMethod(), target, millis(start), e.toString());
            throw e;
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static void completed(String method, String target, int status, long start) {
        long ms = millis(start);
        if (status >= 500) {
            log.error("<-- {} {} {} ({} ms)", method, target, status, ms);
        } else if (status >= 400) {
            log.warn("<-- {} {} {} ({} ms)", method, target, status, ms);
        } else {
            log.info("<-- {} {} {} ({} ms)", method, target, status, ms);
        }
    }

    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
