package br.com.adpilot.integrations.meta;

import org.springframework.http.HttpStatus;

public class MetaApiException extends RuntimeException {
    private final HttpStatus status;
    private final String errorCode;

    public MetaApiException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus status() { return status; }
    public String errorCode() { return errorCode; }
}
