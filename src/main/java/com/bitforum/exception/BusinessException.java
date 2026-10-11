package com.bitforum.exception;

/** Explicit, safe business rejection. Unknown exceptions must not use this type. */
public class BusinessException extends IllegalArgumentException {
    private final int code;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(String message, Throwable cause) {
        this(400, message, cause);
    }

    public BusinessException(int code, String message) {
        this(code, message, null);
    }

    private BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        if (code < 400 || code > 499) {
            throw new IllegalArgumentException("Business rejection must use a 4xx status");
        }
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
