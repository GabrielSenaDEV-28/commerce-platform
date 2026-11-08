package com.gabrielsena.commerce.api;

import com.gabrielsena.commerce.store.InvalidSubdomainException;
import com.gabrielsena.commerce.store.SubdomainAlreadyInUseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(SubdomainAlreadyInUseException.class)
    public ResponseEntity<ProblemDetail> handleSubdomainAlreadyInUse() {
        return problem(HttpStatus.CONFLICT, "The requested store subdomain is already in use.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRequest(MethodArgumentNotValidException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request validation failed."
        );
        problem.setProperty(
                "errors",
                exception.getBindingResult().getFieldErrors().stream()
                        .map(error -> error.getField() + ": " + error.getDefaultMessage())
                        .toList()
        );
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(InvalidSubdomainException.class)
    public ResponseEntity<ProblemDetail> handleInvalidSubdomain() {
        return problem(HttpStatus.BAD_REQUEST, "Request contains invalid values.");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        return ResponseEntity.status(status).body(problem);
    }
}
