package br.com.adpilot.integrations.meta;

import org.springframework.http.HttpStatus;

public class MetaApiException extends RuntimeException {
    private final HttpStatus status;
    private final String errorCode;
    private final Integer upstreamStatus;
    private final Integer metaCode;
    private final Integer metaSubcode;
    private final String clientException;
    private final String rootException;

    public MetaApiException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, null, null, null, null, null);
    }

    public MetaApiException(HttpStatus status, String errorCode, String message,
                            Integer upstreamStatus, Integer metaCode, Integer metaSubcode) {
        this(status, errorCode, message, upstreamStatus, metaCode, metaSubcode, null, null);
    }

    public MetaApiException(HttpStatus status, String errorCode, String message,
                            String clientException, String rootException) {
        this(status, errorCode, message, null, null, null, clientException, rootException);
    }

    private MetaApiException(HttpStatus status, String errorCode, String message,
                             Integer upstreamStatus, Integer metaCode, Integer metaSubcode,
                             String clientException, String rootException) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.upstreamStatus = upstreamStatus;
        this.metaCode = metaCode;
        this.metaSubcode = metaSubcode;
        this.clientException = clientException;
        this.rootException = rootException;
    }

    public HttpStatus status() { return status; }
    public String errorCode() { return errorCode; }
    public Integer upstreamStatus() { return upstreamStatus; }
    public Integer metaCode() { return metaCode; }
    public Integer metaSubcode() { return metaSubcode; }
    public String clientException() { return clientException; }
    public String rootException() { return rootException; }
}
