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
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<Error> invalid() {
        return ResponseEntity.badRequest().body(new Error("INVALID_INPUT", "اطلاعات ورودی معتبر نیست."));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Error> duplicate() {
        return ResponseEntity.status(409).body(new Error("USER_EXISTS", "نام کاربری یا ایمیل قبلاً ثبت شده است."));
    }
}
