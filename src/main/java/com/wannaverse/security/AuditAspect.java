package com.wannaverse.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannaverse.persistence.Resource;
import com.wannaverse.service.AuditService;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;

@Aspect
@Component
@Order(2)
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AuditAspect(AuditService auditService) {
        this.auditService = auditService;
        this.objectMapper = new ObjectMapper();
    }

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint joinPoint, Auditable auditable) throws Throwable {
        Resource resource = auditable.resource();
        String action = auditable.action();
        String resourceId = extractResourceId(joinPoint, auditable.resourceIdParam());
        Map<String, Object> details = new HashMap<>();

        // Capture path variables and request parameters
        details.putAll(extractPathVariablesAndParams(joinPoint));

        // Capture request body if needed
        if (auditable.captureRequestBody()) {
            Object requestBody = extractRequestBody(joinPoint);
            if (requestBody != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> bodyMap = objectMapper.convertValue(requestBody, Map.class);
                    details.put("request", bodyMap);
                } catch (Exception e) {
                    details.put("request", requestBody.toString());
                }
            }
        }

        try {
            Object result = joinPoint.proceed();
            auditService.logSuccess(action, resource, resourceId, details);
            return result;

        } catch (Exception e) {
            auditService.logFailure(action, resource, resourceId, details, e.getMessage());
            throw e;
        }
    }

    private String extractResourceId(ProceedingJoinPoint joinPoint, String paramName) {
        if (paramName == null || paramName.isEmpty()) {
            return null;
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < paramNames.length; i++) {
            if (paramNames[i].equals(paramName) && args[i] != null) {
                return args[i].toString();
            }
        }
        return null;
    }

    private Object extractRequestBody(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(RequestBody.class)) {
                return args[i];
            }
        }
        return null;
    }

    private Map<String, Object> extractPathVariablesAndParams(ProceedingJoinPoint joinPoint) {
        Map<String, Object> variables = new HashMap<>();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameters.length; i++) {
            if (args[i] == null) continue;

            PathVariable pathVar = parameters[i].getAnnotation(PathVariable.class);
            if (pathVar != null) {
                String name = pathVar.value().isEmpty() ? parameters[i].getName() : pathVar.value();
                variables.put(name, args[i].toString());
            }

            RequestParam reqParam = parameters[i].getAnnotation(RequestParam.class);
            if (reqParam != null) {
                String name =
                        reqParam.value().isEmpty() ? parameters[i].getName() : reqParam.value();
                // Skip large objects, only include simple types
                if (args[i] instanceof String
                        || args[i] instanceof Number
                        || args[i] instanceof Boolean) {
                    variables.put(name, args[i].toString());
                }
            }
        }
        return variables;
    }
}
