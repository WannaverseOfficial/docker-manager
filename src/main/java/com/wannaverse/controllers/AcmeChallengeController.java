package com.wannaverse.controllers;

import com.wannaverse.service.CertificateService;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
