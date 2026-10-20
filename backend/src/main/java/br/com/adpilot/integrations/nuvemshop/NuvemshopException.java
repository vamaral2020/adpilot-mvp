package br.com.adpilot.integrations.nuvemshop;

import org.springframework.http.HttpStatus;

public class NuvemshopException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Integer upstreamStatus;

    public NuvemshopException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public NuvemshopException(HttpStatus status, String code, String message, Integer upstreamStatus) {
        super(message);
        this.status = status;
        this.code = code;
        this.upstreamStatus = upstreamStatus;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Integer upstreamStatus() { return upstreamStatus; }
}
