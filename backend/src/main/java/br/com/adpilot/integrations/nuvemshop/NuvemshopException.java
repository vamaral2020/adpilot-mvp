package br.com.adpilot.integrations.nuvemshop;

import org.springframework.http.HttpStatus;

public class NuvemshopException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Integer upstreamStatus;
    private final String clientException;
    private final String rootException;

    public NuvemshopException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null, null);
    }

    public NuvemshopException(HttpStatus status, String code, String message, Integer upstreamStatus) {
        this(status, code, message, upstreamStatus, null, null);
    }

    public NuvemshopException(HttpStatus status, String code, String message,
                             String clientException, String rootException) {
        this(status, code, message, null, clientException, rootException);
    }

    private NuvemshopException(HttpStatus status, String code, String message,
                              Integer upstreamStatus, String clientException, String rootException) {
        super(message);
        this.status = status;
        this.code = code;
        this.upstreamStatus = upstreamStatus;
        this.clientException = clientException;
        this.rootException = rootException;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Integer upstreamStatus() { return upstreamStatus; }
    public String clientException() { return clientException; }
    public String rootException() { return rootException; }
}
