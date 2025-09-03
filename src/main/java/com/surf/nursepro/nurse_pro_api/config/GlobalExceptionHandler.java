package com.surf.nursepro.nurse_pro_api.config;

import com.surf.nursepro.nurse_pro_api.dto.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.HttpClientErrorException;

import java.util.stream.Collectors;

@ControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<ApiError> handleHttpClientError(HttpClientErrorException ex) {
        logger.warn("Client error: {}", ex.getMessage());
        return ResponseEntity.status(ex.getStatusCode())
                .body(new ApiError(ex.getMessage(), ex.getStatusCode().toString(), ex));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationExceptions(MethodArgumentNotValidException ex) {
        String errorMessage = ex.getBindingResult().getAllErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining("; "));
        logger.warn("Validation error: {}", errorMessage, ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("Validation failed: " + errorMessage, HttpStatus.BAD_REQUEST.getReasonPhrase(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleException(Exception e) {
        ApiError error = new ApiError();
        error.setMessage(e.getMessage());
        error.setCode("INTERNAL_SERVER_ERROR");

        logger.warn("INTERNAL_SERVER_ERROR: {}", error.getMessage(), e);

        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgumentException(IllegalArgumentException e) {
        ApiError error = new ApiError();
        error.setMessage(e.getMessage());
        error.setCode("BAD_REQUEST");

        logger.warn("Bad request error: {}", error.getMessage(), e);

        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }
}