package com.wannaverse.controllers;

import com.wannaverse.service.CertificateService;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * ACME HTTP-01 Challenge endpoint. This must be publicly accessible (no authentication) for Let's
 * Encrypt to validate domain ownership.
 *
 * <p>Let's Encrypt will make a request to: http://{domain}/.well-known/acme-challenge/{token} Nginx
 * proxies this to: /api/ingress/acme/{token}
 *
 * <p>And expects to receive the authorization key in response.
 */
@RestController
@RequestMapping("/api/ingress/acme")
public class AcmeChallengeController {

    private final CertificateService certificateService;

    public AcmeChallengeController(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    @GetMapping(value = "/{token}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> respondToChallenge(@PathVariable String token) {
        return certificateService
                .getChallengeResponse(token)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
