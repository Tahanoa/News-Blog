package org.example.newsblog.security;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiErrors {
    record Error(String code, String message) {}
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Error> api(ApiException exception) {
        return ResponseEntity.status(exception.status).body(new Error(exception.code, exception.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, jakarta.validation.ConstraintViolationException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<Error> invalid() {
        return ResponseEntity.badRequest().body(new Error("INVALID_INPUT", "Invalid input."));
    }
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    ResponseEntity<Error> stale() {
        return ResponseEntity.status(409).body(new Error("VERSION_CONFLICT", "Reload the resource before updating it."));
    }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Error> mediaType() {
        return ResponseEntity.status(415).body(new Error("UNSUPPORTED_MEDIA_TYPE", "Unsupported request content type."));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Error> duplicate() {
        return ResponseEntity.status(409).body(new Error("DATA_CONFLICT", "The operation conflicts with existing data."));
    }
}
