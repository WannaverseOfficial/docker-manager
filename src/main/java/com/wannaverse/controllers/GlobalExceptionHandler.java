package com.wannaverse.controllers;

import com.wannaverse.service.ImagePolicyService;
import com.wannaverse.service.NginxApplier;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ImagePolicyService.ImagePolicyViolationException.class)
    public ResponseEntity<Map<String, Object>> handleImagePolicyViolationException(
            ImagePolicyService.ImagePolicyViolationException ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("error", "Image policy violation");
        response.put("message", ex.getReason());
        response.put("image", ex.getImageName());
        if (ex.getPolicyName() != null) {
            response.put("policy", ex.getPolicyName());
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(
            MethodArgumentNotValidException ex) {
        Map<String, Object> response = new HashMap<>();
        StringBuilder message = new StringBuilder();

        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            if (!message.isEmpty()) {
                message.append(", ");
            }
            message.append(error.getDefaultMessage());
        }

        response.put("error", message.toString());
        response.put("message", message.toString());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolationException(
            ConstraintViolationException ex) {
        Map<String, Object> response = new HashMap<>();
        String message =
                ex.getConstraintViolations().stream()
                        .map(ConstraintViolation::getMessage)
                        .collect(Collectors.joining(", "));
        response.put("error", message);
        response.put("message", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException ex) {
        Map<String, Object> response = new HashMap<>();

        String detailedMessage = "Invalid request body";
        Throwable cause = ex.getCause();
        if (cause != null) {
            String causeMessage = cause.getMessage();
            if (causeMessage != null) {
                if (causeMessage.contains("Cannot deserialize")) {
                    detailedMessage = "JSON parsing error: " + causeMessage;
                } else if (causeMessage.contains("Unrecognized field")) {
                    detailedMessage = "Unknown field in request: " + causeMessage;
                } else if (causeMessage.contains("Missing required")) {
                    detailedMessage = "Missing required field: " + causeMessage;
                } else {
                    detailedMessage = "Invalid request body: " + causeMessage;
                }
            }
        }

        response.put("error", detailedMessage);
        response.put("message", detailedMessage);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(
            ResponseStatusException ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("error", ex.getReason());
        response.put("message", ex.getReason());
        return ResponseEntity.status(ex.getStatusCode()).body(response);
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(Exception ex) {
        return message(HttpStatus.NOT_FOUND, "Not found");
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethod(Exception ex) {
        return message(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage());
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(Exception ex) {
        return message(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return message(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        return message(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(com.github.dockerjava.api.exception.NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleDockerNotFound(
            com.github.dockerjava.api.exception.NotFoundException ex) {
        return message(HttpStatus.NOT_FOUND, dockerMessage(ex));
    }

    @ExceptionHandler(com.github.dockerjava.api.exception.ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleDockerConflict(
            com.github.dockerjava.api.exception.ConflictException ex) {
        return message(HttpStatus.CONFLICT, dockerMessage(ex));
    }

    @ExceptionHandler(com.github.dockerjava.api.exception.DockerException.class)
    public ResponseEntity<Map<String, Object>> handleDocker(
            com.github.dockerjava.api.exception.DockerException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getHttpStatus());
        if (status == null || status.is5xxServerError()) status = HttpStatus.BAD_GATEWAY;
        return message(status, dockerMessage(ex));
    }

    @ExceptionHandler(NginxApplier.ApplyException.class)
    public ResponseEntity<Map<String, Object>> handleNginx(NginxApplier.ApplyException ex) {
        return message(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Docker's messages arrive as {"message":"..."}; show just the text. */
    private static String dockerMessage(Exception ex) {
        String m = ex.getMessage() == null ? "Docker error" : ex.getMessage();
        int i = m.indexOf("{\"message\":\"");
        if (i >= 0) {
            String rest = m.substring(i + 12);
            int end = rest.lastIndexOf("\"}");
            return end > 0 ? rest.substring(0, end).replace("\\\"", "\"") : rest;
        }
        return m.replaceFirst("^Status \\d+: ", "");
    }

    private static ResponseEntity<Map<String, Object>> message(HttpStatus status, String message) {
        Map<String, Object> response = new HashMap<>();
        String text = message == null ? status.getReasonPhrase() : message;
        response.put("error", text);
        response.put("message", text);
        return ResponseEntity.status(status).body(response);
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("Unhandled error", ex);
        if (ex instanceof org.springframework.dao.DataAccessException) {
            return message(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "A database error occurred. The server log has the details.");
        }
        Map<String, Object> response = new HashMap<>();
        String message = ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred";
        response.put("error", message);
        response.put("message", message);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
