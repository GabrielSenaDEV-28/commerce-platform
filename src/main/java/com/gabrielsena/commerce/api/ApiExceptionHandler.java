package com.gabrielsena.commerce.api;

import com.gabrielsena.commerce.auth.EmailAlreadyRegisteredException;
import com.gabrielsena.commerce.auth.InvalidCredentialsException;
import com.gabrielsena.commerce.auth.InvalidRefreshTokenException;
import com.gabrielsena.commerce.catalog.ItemTypeNameInUseException;
import com.gabrielsena.commerce.catalog.ItemTypeNotFoundException;
import com.gabrielsena.commerce.catalog.CatalogConflictException;
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

    @ExceptionHandler(CatalogConflictException.class)
    public ResponseEntity<ProblemDetail> handleCatalogConflict(CatalogConflictException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(ItemTypeNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleItemTypeNotFound() {
        return problem(HttpStatus.NOT_FOUND, "Item type not found.");
    }

    @ExceptionHandler(ItemTypeNameInUseException.class)
    public ResponseEntity<ProblemDetail> handleItemTypeNameInUse() {
        return problem(HttpStatus.CONFLICT, "An item type with this name already exists.");
    }

    @ExceptionHandler(SubdomainAlreadyInUseException.class)
    public ResponseEntity<ProblemDetail> handleSubdomainAlreadyInUse() {
        return problem(HttpStatus.CONFLICT, "The requested store subdomain is already in use.");
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ProblemDetail> handleEmailAlreadyRegistered() {
        return problem(HttpStatus.CONFLICT, "An account already exists for the supplied email.");
    }

    @ExceptionHandler({InvalidCredentialsException.class, InvalidRefreshTokenException.class})
    public ResponseEntity<ProblemDetail> handleInvalidAuthentication() {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication request is invalid.");
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
