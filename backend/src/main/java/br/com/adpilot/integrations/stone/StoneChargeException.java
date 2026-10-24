package br.com.adpilot.integrations.stone;

import org.springframework.http.HttpStatus;

/** Sanitized error: never serialize provider URL, payload or credentials. */
public class StoneChargeException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Integer upstreamStatus;

    public StoneChargeException(HttpStatus status,String code,String message) {
        this(status,code,message,null);
    }

    public StoneChargeException(HttpStatus status,String code,String message,Integer upstreamStatus) {
        super(message);
        this.status=status;
        this.code=code;
        this.upstreamStatus=upstreamStatus;
    }

    public HttpStatus status() {return status;}
    public String code() {return code;}
    public Integer upstreamStatus() {return upstreamStatus;}
}
