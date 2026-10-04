package com.vaultdrive.common.exception;

import com.vaultdrive.auth.exception.EmailAlreadyExistsException;
import com.vaultdrive.auth.exception.InvalidPasswordException;
import com.vaultdrive.auth.exception.InvalidCredentialsException;

import com.vaultdrive.folder.exception.InvalidFolderNameException;
import com.vaultdrive.folder.exception.FolderNotFoundException;
import com.vaultdrive.folder.exception.DuplicateFolderNameException;
import com.vaultdrive.folder.exception.InvalidFolderMoveException;

import com.vaultdrive.file.exception.InvalidFileNameException;
import com.vaultdrive.file.exception.DuplicateFileNameException;
import com.vaultdrive.file.exception.FileUploadException;
import com.vaultdrive.file.exception.InvalidFilePaginationException;
import com.vaultdrive.file.exception.FileNotFoundException;


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

    // AUTHENTICATION EXCEPTIONS

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

    // REQUEST VALIDATION

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

    // FOLDER EXCEPTIONS

    @ExceptionHandler(InvalidFolderNameException.class)
    public ResponseEntity<ApiError> handleInvalidFolderName(
            InvalidFolderNameException exception
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

    @ExceptionHandler(FolderNotFoundException.class)
    public ResponseEntity<ApiError> handleFolderNotFound(
            FolderNotFoundException exception
    ) {

        ApiError error = new ApiError(
                404,
                "NOT_FOUND",
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error);
    }

    @ExceptionHandler(DuplicateFolderNameException.class)
    public ResponseEntity<ApiError> handleDuplicateFolderName(
            DuplicateFolderNameException exception
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

    // DATABASE CONSTRAINT VIOLATIONS

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

                if ("uq_folders_active_name".equals(constraintName)) {

                    ApiError error = new ApiError(
                            409,
                            "CONFLICT",
                            "A folder with this name already exists"
                    );

                    return ResponseEntity
                            .status(HttpStatus.CONFLICT)
                            .body(error);
                }

                if ("uq_files_active_name".equals(constraintName)) {

                    ApiError error = new ApiError(
                            409,
                            "CONFLICT",
                            "A file with this name already exists"
                    );

                    return ResponseEntity
                            .status(HttpStatus.CONFLICT)
                            .body(error);
                }
            }

            cause = cause.getCause();
        }

        // Never expose internal database details.

        ApiError error = new ApiError(
                500,
                "INTERNAL_SERVER_ERROR",
                "An unexpected database error occurred"
        );

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error);
    }

    @ExceptionHandler(InvalidFolderMoveException.class)
    public ResponseEntity<ApiError> handleInvalidFolderMove(
            InvalidFolderMoveException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(
                        400,
                        "Bad Request",
                        ex.getMessage()
                ));
    }

    @ExceptionHandler(InvalidFileNameException.class)
    public ResponseEntity<ApiError> handleInvalidFileName(
            InvalidFileNameException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(
                        400,
                        "Bad Request",
                        ex.getMessage()
                ));
    }

    @ExceptionHandler(DuplicateFileNameException.class)
    public ResponseEntity<ApiError> handleDuplicateFileName(
            DuplicateFileNameException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ApiError(
                        409,
                        "Conflict",
                        ex.getMessage()
                ));
    }

    @ExceptionHandler(FileUploadException.class)
    public ResponseEntity<ApiError> handleFileUpload(
            FileUploadException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(
                        500,
                        "Internal Server Error",
                        ex.getMessage()
                ));
    }

    @ExceptionHandler(InvalidFilePaginationException.class)
    public ResponseEntity<ApiError> handleInvalidFilePagination(
            InvalidFilePaginationException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(
                        400,
                        "BAD_REQUEST",
                        ex.getMessage()
                ));
    }

    @ExceptionHandler(FileNotFoundException.class)
    public ResponseEntity<ApiError> handleFileNotFound(
            FileNotFoundException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiError(
                        404,
                        "NOT_FOUND",
                        ex.getMessage()
                ));
    }
}
