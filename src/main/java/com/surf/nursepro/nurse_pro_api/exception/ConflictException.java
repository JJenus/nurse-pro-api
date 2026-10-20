package com.surf.nursepro.nurse_pro_api.exception;

/** Thrown when a request conflicts with existing state (duplicates, stale versions). Mapped to HTTP 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
