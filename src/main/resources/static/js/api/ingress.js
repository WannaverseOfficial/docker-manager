// Ingress API Client
import { ingressApi } from './client.js';

// ========== Status & Config ==========

export async function getIngressStatus(hostId) {
    return ingressApi(`/hosts/${hostId}/status`);
}

export async function getIngressConfig(hostId) {
    return ingressApi(`/hosts/${hostId}/config`);
}

// ========== Enable/Disable Flows ==========

export async function previewEnableIngress(hostId, request) {
    return ingressApi(`/hosts/${hostId}/enable/preview`, {
        method: 'POST',
        body: JSON.stringify(request),
    });
}

export async function enableIngress(hostId, request) {
    return ingressApi(`/hosts/${hostId}/enable`, {
        method: 'POST',
        body: JSON.stringify(request),
    });
}

export async function previewDisableIngress(hostId) {
    return ingressApi(`/hosts/${hostId}/disable/preview`, {
        method: 'POST',
    });
}

export async function disableIngress(hostId) {
    return ingressApi(`/hosts/${hostId}/disable`, {
        method: 'POST',
    });
}

// ========== Routes ==========

export async function listRoutes(hostId) {
    return ingressApi(`/hosts/${hostId}/routes`);
}

export async function getRoute(routeId) {
    return ingressApi(`/routes/${routeId}`);
}

export async function previewExposeApp(hostId, request) {
    return ingressApi(`/hosts/${hostId}/routes/preview`, {
        method: 'POST',
        body: JSON.stringify(request),
    });
}

export async function createRoute(hostId, request) {
    return ingressApi(`/hosts/${hostId}/routes`, {
        method: 'POST',
        body: JSON.stringify(request),
    });
}

export async function deleteRoute(routeId, disconnectFromNetwork = false) {
    return ingressApi(`/routes/${routeId}?disconnectFromNetwork=${disconnectFromNetwork}`, {
        method: 'DELETE',
    });
}

// ========== Nginx ==========

export async function getNginxConfig(hostId) {
    return ingressApi(`/hosts/${hostId}/nginx/config`);
}

export async function getNginxStatus(hostId) {
    return ingressApi(`/hosts/${hostId}/nginx/status`);
}

export async function reloadNginx(hostId) {
    return ingressApi(`/hosts/${hostId}/nginx/reload`, {
        method: 'POST',
    });
}

// ========== Certificates ==========

export async function listCertificates(hostId) {
    return ingressApi(`/hosts/${hostId}/certificates`);
}

export async function getCertificate(certId) {
    return ingressApi(`/certificates/${certId}`);
}

export async function requestCertificate(certId) {
    return ingressApi(`/certificates/${certId}/request`, {
        method: 'POST',
    });
}

export async function retryCertificate(certId) {
    return ingressApi(`/certificates/${certId}/retry`, {
        method: 'POST',
    });
}

export async function renewCertificate(certId) {
    return ingressApi(`/certificates/${certId}/renew`, {
        method: 'POST',
    });
}

export async function uploadCertificate(certId, certificatePem, privateKeyPem, chainPem) {
    return ingressApi(`/certificates/${certId}/upload`, {
        method: 'POST',
        body: JSON.stringify({ certificatePem, privateKeyPem, chainPem }),
    });
}

export async function deleteCertificate(certId) {
    return ingressApi(`/certificates/${certId}`, {
        method: 'DELETE',
    });
}

export async function setAutoRenew(certId, autoRenew) {
    return ingressApi(`/certificates/${certId}/auto-renew`, {
        method: 'PATCH',
        body: JSON.stringify({ autoRenew }),
    });
}

// ========== Audit Logs ==========

export async function getAuditLogs(hostId) {
    return ingressApi(`/hosts/${hostId}/audit`);
}
