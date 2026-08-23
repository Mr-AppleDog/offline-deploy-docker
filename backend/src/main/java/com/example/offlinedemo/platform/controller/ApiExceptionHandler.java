package com.example.offlinedemo.platform.controller;

import com.example.offlinedemo.platform.store.PlatformStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(PlatformStore.NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> notFound(PlatformStore.NotFoundException exception) { return error(exception); }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(RuntimeException exception) { return error(exception); }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> internal(Exception exception) {
        return Map.of("ok", false, "message", exception.getMessage() == null ? "服务器内部错误" : exception.getMessage(),
                "timestamp", Instant.now());
    }

    private Map<String, Object> error(Exception exception) {
        return Map.of("ok", false, "message", exception.getMessage(), "timestamp", Instant.now());
    }
}
