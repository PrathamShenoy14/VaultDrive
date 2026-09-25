package com.vaultdrive.common.exception;

import com.vaultdrive.auth.exception.EmailAlreadyExistsException;
import com.vaultdrive.auth.exception.InvalidPasswordException;
import com.vaultdrive.auth.exception.InvalidCredentialsException;

import org.hibernate.exception.ConstraintViolationException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 1. Duplicate email detected by RegistrationService

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleDuplicateEmail(
            EmailAlreadyExistsException exception
    ) {

        ApiError error = new ApiError(
                409,
                "CONFLICT",
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error);
    }

    // 2. Invalid password detected by RegistrationService

    @ExceptionHandler(InvalidPasswordException.class)
    public ResponseEntity<ApiError> handleInvalidPassword(
            InvalidPasswordException exception
    ) {

        ApiError error = new ApiError(
                400,
                "BAD_REQUEST",
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error);
    }

    // 3. Request DTO validation errors

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationErrors(
            MethodArgumentNotValidException exception
    ) {

        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error ->
                        error.getField() + ": " +
                        error.getDefaultMessage()
                )
                .sorted()
                .collect(Collectors.joining("; "));

        ApiError error = new ApiError(
                400,
                "BAD_REQUEST",
                message
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error);
    }

    // 4. Database constraint violations

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(
            DataIntegrityViolationException exception
    ) {

        Throwable cause = exception;

        while (cause != null) {

            if (cause instanceof ConstraintViolationException
                    constraintException) {

                String constraintName =
                        constraintException.getConstraintName();

                if ("users_email_unique_lower".equals(constraintName)) {

                    ApiError error = new ApiError(
                            409,
                            "CONFLICT",
                            "Email is already registered"
                    );

                    return ResponseEntity
                            .status(HttpStatus.CONFLICT)
                            .body(error);
                }
            }

            cause = cause.getCause();
        }

        // Don't expose internal database details to clients.
        ApiError error = new ApiError(
                500,
                "INTERNAL_SERVER_ERROR",
                "An unexpected database error occurred"
        );

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error);
    }

    // 5. Invalid credentials violation
    
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(
            InvalidCredentialsException exception
    ) {
        ApiError error = new ApiError(
                401,
                "UNAUTHORIZED",
                exception.getMessage()
        );
    
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error);
    }
}