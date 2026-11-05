package com.corridoriq.backend.web;

import org.springframework.http.HttpStatus;

/** An error the API reports as {@code {"detail": message}} with the given status. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    /** 422, as FastAPI used for bad parameters; the portal shows the detail to the user. */
    public static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }
}
