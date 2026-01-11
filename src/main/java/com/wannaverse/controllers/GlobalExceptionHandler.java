package com.wannaverse.controllers;

import com.wannaverse.service.ImagePolicyService;

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

        ex.printStackTrace();

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        Map<String, Object> response = new HashMap<>();
        String message = ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred";
        response.put("error", message);
        response.put("message", message);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
