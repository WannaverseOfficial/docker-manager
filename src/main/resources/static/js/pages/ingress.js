// Ingress Page - Managed Nginx Reverse Proxy
import { state } from '../state.js';
import { showToast } from '../components/toast.js';
import { showConfirm } from '../components/confirm-dialog.js';
import * as ingressApi from '../api/ingress.js';
import { listContainers } from '../api/docker.js';

let ingressStatus = null;
let ingressConfig = null;
let routes = [];
let certificates = [];
let auditLogs = [];
let containers = [];
let availableCerts = [];
let currentTab = 'routes';
let currentView = 'main'; // 'main' or 'expose'

export function render() {
    return `
        <div class="ingress-page">
            <div id="ingress-content">
                <div class="loading-container">
                    <md-circular-progress indeterminate></md-circular-progress>
                </div>
            </div>
        </div>
    `;
}

export async function init() {
    await loadIngressStatus();
}

export function cleanup() {
    ingressStatus = null;
    ingressConfig = null;
    routes = [];
    certificates = [];
    auditLogs = [];
    containers = [];
    currentView = 'main';
}

async function loadIngressStatus() {
    const hostId = state.currentHostId;
    if (!hostId) {
        renderNoHostSelected();
        return;
    }

    try {
        ingressStatus = await ingressApi.getIngressStatus(hostId);
        ingressConfig = await ingressApi.getIngressConfig(hostId);

        if (ingressStatus.status === 'ENABLED') {
            await loadEnabledData();
            renderEnabledState();
        } else {
            renderDisabledState();
        }
    } catch (error) {
        console.error('Failed to load ingress status:', error);
        renderError(error.message);
    }
}

async function loadEnabledData() {
    const hostId = state.currentHostId;
    try {
        [routes, certificates, auditLogs] = await Promise.all([
            ingressApi.listRoutes(hostId),
            ingressApi.listCertificates(hostId),
            ingressApi.getAuditLogs(hostId),
        ]);
    } catch (error) {
        console.error('Failed to load ingress data:', error);
    }
}

function renderNoHostSelected() {
    const content = document.getElementById('ingress-content');
    content.innerHTML = `
        <div class="empty-state">
            <span class="material-symbols-outlined">dns</span>
            <h3>No Host Selected</h3>
            <p>Select a Docker host to manage ingress.</p>
        </div>
    `;
}

function renderError(message) {
    const content = document.getElementById('ingress-content');
    content.innerHTML = `
        <div class="empty-state">
            <span class="material-symbols-outlined">error</span>
            <h3>Error Loading Ingress</h3>
            <p>${message}</p>
            <md-filled-button onclick="window.location.reload()">
                <span class="material-symbols-outlined" slot="icon">refresh</span>
                Retry
            </md-filled-button>
        </div>
    `;
}

function renderDisabledState() {
    const content = document.getElementById('ingress-content');
    content.innerHTML = `
        <div class="empty-state ingress-disabled">
            <span class="material-symbols-outlined">router</span>
            <h3>Managed Ingress Disabled</h3>
            <p>Enable managed ingress to expose your applications through a reverse proxy with automatic TLS.</p>

            <div class="ingress-features">
                <div class="feature-item">
                    <span class="material-symbols-outlined">dns</span>
                    <div>
                        <strong>Single Nginx Container</strong>
                        <span>One inspectable reverse proxy for all routes</span>
                    </div>
                </div>
                <div class="feature-item">
                    <span class="material-symbols-outlined">hub</span>
                    <div>
                        <strong>Dedicated Network</strong>
                        <span>Isolated network for secure container communication</span>
                    </div>
                </div>
                <div class="feature-item">
                    <span class="material-symbols-outlined">lock</span>
                    <div>
                        <strong>TLS Certificates</strong>
                        <span>Let's Encrypt, custom upload, or bring your own</span>
                    </div>
                </div>
                <div class="feature-item">
                    <span class="material-symbols-outlined">visibility</span>
                    <div>
                        <strong>Full Transparency</strong>
                        <span>View and inspect all nginx configuration</span>
                    </div>
                </div>
            </div>

            <md-filled-button id="enable-ingress-btn" class="enable-btn">
                <span class="material-symbols-outlined" slot="icon">power_settings_new</span>
                Enable Managed Ingress
            </md-filled-button>

            <p class="hint">No apps or ports will be exposed automatically. You control every route.</p>
        </div>
    `;

    document.getElementById('enable-ingress-btn')?.addEventListener('click', showEnableDialog);
}

function renderEnabledState() {
    const content = document.getElementById('ingress-content');
    content.innerHTML = `
        <div class="ingress-enabled">
            <!-- Status Header -->
            <div class="ingress-header">
                <div class="status-cards">
                    <div class="status-card">
                        <span class="material-symbols-outlined ${ingressStatus.nginxContainerStatus === 'running' ? 'status-running' : 'status-stopped'}">
                            ${ingressStatus.nginxContainerStatus === 'running' ? 'check_circle' : 'error'}
                        </span>
                        <div>
                            <div class="status-value">${ingressStatus.nginxContainerStatus || 'Unknown'}</div>
                            <div class="status-label">Nginx Status</div>
                        </div>
                    </div>
                    <div class="status-card">
                        <span class="material-symbols-outlined">route</span>
                        <div>
                            <div class="status-value">${ingressStatus.activeRouteCount}</div>
                            <div class="status-label">Active Routes</div>
                        </div>
                    </div>
                    <div class="status-card">
                        <span class="material-symbols-outlined">verified_user</span>
                        <div>
                            <div class="status-value">${ingressStatus.certificateCount}</div>
                            <div class="status-label">Certificates</div>
                        </div>
                    </div>
                </div>
                <div class="header-actions">
                    <md-outlined-button id="reload-nginx-btn">
                        <span class="material-symbols-outlined" slot="icon">refresh</span>
                        Reload Nginx
                    </md-outlined-button>
                    <md-outlined-button id="disable-ingress-btn" class="danger-btn">
                        <span class="material-symbols-outlined" slot="icon">power_settings_new</span>
                        Disable Ingress
                    </md-outlined-button>
                </div>
            </div>

            <!-- Tabs -->
            <md-tabs id="ingress-tabs">
                <md-primary-tab data-tab="routes" ${currentTab === 'routes' ? 'active' : ''}>
                    <span class="material-symbols-outlined" slot="icon">route</span>
                    Routes
                </md-primary-tab>
                <md-primary-tab data-tab="certificates" ${currentTab === 'certificates' ? 'active' : ''}>
                    <span class="material-symbols-outlined" slot="icon">verified_user</span>
                    Certificates
                </md-primary-tab>
                <md-primary-tab data-tab="nginx-config" ${currentTab === 'nginx-config' ? 'active' : ''}>
                    <span class="material-symbols-outlined" slot="icon">code</span>
                    Nginx Config
                </md-primary-tab>
                <md-primary-tab data-tab="audit" ${currentTab === 'audit' ? 'active' : ''}>
                    <span class="material-symbols-outlined" slot="icon">history</span>
                    Audit Log
                </md-primary-tab>
            </md-tabs>

            <!-- Tab Content -->
            <div id="tab-content" class="tab-content">
                ${renderTabContent()}
            </div>
        </div>
    `;

    setupEnabledEventListeners();
}

function renderTabContent() {
    switch (currentTab) {
        case 'routes':
            return renderRoutesTab();
        case 'certificates':
            return renderCertificatesTab();
        case 'nginx-config':
            return renderNginxConfigTab();
        case 'audit':
            return renderAuditTab();
        default:
            return '';
    }
}

function renderRoutesTab() {
    return `
        <div class="routes-tab">
            <div class="tab-header">
                <h3>Exposed Applications</h3>
                <md-filled-button id="expose-app-btn">
                    <span class="material-symbols-outlined" slot="icon">add</span>
                    Expose App
                </md-filled-button>
            </div>

            ${routes.length === 0 ? `
                <div class="empty-state small">
                    <span class="material-symbols-outlined">route</span>
                    <h4>No Routes Configured</h4>
                    <p>Click "Expose App" to make a container accessible via hostname.</p>
                </div>
            ` : `
                <div class="routes-list">
                    ${routes.map(route => `
                        <div class="route-card ${route.enabled ? '' : 'disabled'}">
                            <div class="route-info">
                                <div class="route-hostname">
                                    <span class="material-symbols-outlined">${route.tlsMode !== 'NONE' ? 'lock' : 'lock_open'}</span>
                                    <a href="${route.tlsMode !== 'NONE' ? 'https' : 'http'}://${route.hostname}${route.pathPrefix}" target="_blank">
                                        ${route.hostname}${route.pathPrefix !== '/' ? route.pathPrefix : ''}
                                    </a>
                                </div>
                                <div class="route-target">
                                    <span class="material-symbols-outlined">arrow_forward</span>
                                    ${route.targetContainerName}:${route.targetPort}
                                </div>
                            </div>
                            <div class="route-meta">
                                <span class="chip ${route.tlsMode.toLowerCase()}">${route.tlsMode.replace('_', ' ')}</span>
                                ${route.authEnabled ? '<span class="chip auth">Auth</span>' : ''}
                            </div>
                            <div class="route-actions">
                                <md-icon-button class="delete-route-btn" data-route-id="${route.id}" title="Delete Route">
                                    <span class="material-symbols-outlined">delete</span>
                                </md-icon-button>
                            </div>
                        </div>
                    `).join('')}
                </div>
            `}
        </div>
    `;
}

function renderCertificatesTab() {
    return `
        <div class="certificates-tab">
            <div class="tab-header">
                <h3>TLS Certificates</h3>
            </div>

            ${certificates.length === 0 ? `
                <div class="empty-state small">
                    <span class="material-symbols-outlined">verified_user</span>
                    <h4>No Certificates</h4>
                    <p>Certificates will appear here when you expose apps with HTTPS.</p>
                </div>
            ` : `
                <div class="certificates-list">
                    ${certificates.map(cert => `
                        <div class="cert-card ${cert.status.toLowerCase()} expandable" data-cert-id="${cert.id}">
                            <div class="cert-main">
                                <div class="cert-icon">
                                    <span class="material-symbols-outlined ${getCertStatusIcon(cert.status).class}">
                                        ${getCertStatusIcon(cert.status).icon}
                                    </span>
                                </div>
                                <div class="cert-info">
                                    <div class="cert-hostname">${cert.hostname}</div>
                                    <div class="cert-details">
                                        ${cert.issuer ? `<span>Issuer: ${cert.issuer}</span>` : ''}
                                        ${cert.expiresAt ? `<span>Expires: ${formatTimestamp(cert.expiresAt)}</span>` : ''}
                                    </div>
                                    <div class="cert-meta">
                                        <span class="chip ${cert.source.toLowerCase()}">${formatCertSource(cert.source)}</span>
                                        <span class="chip ${cert.status.toLowerCase()}">${formatCertStatus(cert.status)}</span>
                                        ${cert.renewalNeeded ? '<span class="chip warning">Renewal Needed</span>' : ''}
                                    </div>
                                </div>
                                <md-icon-button class="expand-cert-btn" data-cert-id="${cert.id}">
                                    <span class="material-symbols-outlined">expand_more</span>
                                </md-icon-button>
                            </div>

                            ${renderCertDetailsPanel(cert)}

                            <div class="cert-status-info">
                                ${cert.status === 'PENDING' || cert.status === 'RENEWAL_PENDING' ? `
                                    <div class="cert-pending">
                                        ${cert.statusMessage?.includes('progress') || cert.status === 'RENEWAL_PENDING' ? `
                                            <md-circular-progress indeterminate style="--md-circular-progress-size: 20px;"></md-circular-progress>
                                        ` : `
                                            <span class="material-symbols-outlined">hourglass_empty</span>
                                        `}
                                        <span>${cert.statusMessage || 'Click expand to see details and start certificate request.'}</span>
                                    </div>
                                ` : cert.status === 'ERROR' ? `
                                    <div class="cert-error">
                                        <span class="material-symbols-outlined">error</span>
                                        <span>${cert.statusMessage || 'Certificate request failed.'} Click expand for details.</span>
                                    </div>
                                ` : cert.status === 'ACTIVE' && cert.expiresAt ? `
                                    <div class="cert-expiry ${cert.renewalNeeded ? 'warning' : ''}">
                                        <span class="material-symbols-outlined">
                                            ${cert.renewalNeeded ? 'warning' : 'verified'}
                                        </span>
                                        <span>
                                            ${cert.daysUntilExpiry <= 0
                                                ? 'Certificate expired!'
                                                : cert.daysUntilExpiry === 1
                                                    ? '1 day until expiry'
                                                    : cert.renewalNeeded
                                                        ? `${cert.daysUntilExpiry} days until expiry - renewal recommended`
                                                        : `Valid for ${cert.daysUntilExpiry} days`}
                                        </span>
                                    </div>
                                ` : ''}
                            </div>
                        </div>
                    `).join('')}
                </div>
            `}
        </div>
    `;
}

function renderCertDetailsPanel(cert) {
    const isLetsEncrypt = cert.source === 'LETS_ENCRYPT';
    const isUpload = cert.source === 'UPLOADED';
    const isExternal = cert.source === 'EXTERNAL';
    const isPending = cert.status === 'PENDING';
    const isError = cert.status === 'ERROR';
    const isActive = cert.status === 'ACTIVE';
    const isRenewalPending = cert.status === 'RENEWAL_PENDING';

    return `
        <div class="cert-details-panel hidden" id="cert-details-${cert.id}">
            <!-- Current Status -->
            <div class="cert-current-status">
                <h4><span class="material-symbols-outlined">info</span> Current Status</h4>
                <div class="status-message ${cert.status.toLowerCase()}">
                    <span class="material-symbols-outlined">${getStatusIcon(cert.status)}</span>
                    <span>${cert.statusMessage || getDefaultStatusMessage(cert)}</span>
                </div>
                ${cert.lastRenewalError ? `
                    <div class="error-details">
                        <strong>Last Error:</strong> ${cert.lastRenewalError}
                        ${cert.lastRenewalAttempt ? `<br><small>Attempted: ${formatTimestamp(cert.lastRenewalAttempt)}</small>` : ''}
                    </div>
                ` : ''}
            </div>

            ${isLetsEncrypt && (isPending || isError) ? `
                <div class="cert-requirements">
                    <h4><span class="material-symbols-outlined">checklist</span> Requirements for Let's Encrypt</h4>
                    <div class="requirement-list">
                        <div class="requirement-item">
                            <span class="material-symbols-outlined">dns</span>
                            <div>
                                <strong>DNS Configuration</strong>
                                <p>Create an A or AAAA record pointing <code>${cert.hostname}</code> to this server's public IP address.</p>
                            </div>
                        </div>
                        <div class="requirement-item">
                            <span class="material-symbols-outlined">lan</span>
                            <div>
                                <strong>Port 80 Accessible</strong>
                                <p>Port 80 must be open and accessible from the internet for HTTP-01 challenge verification.</p>
                            </div>
                        </div>
                    </div>
                </div>
            ` : ''}

            ${isActive && cert.expiresAt ? `
                <div class="cert-expiry-info">
                    <h4><span class="material-symbols-outlined">event</span> Certificate Details</h4>
                    <div class="cert-detail-row">
                        <span class="label">Issued:</span>
                        <span class="value">${formatTimestamp(cert.issuedAt)}</span>
                    </div>
                    <div class="cert-detail-row">
                        <span class="label">Expires:</span>
                        <span class="value ${cert.renewalNeeded ? 'warning' : ''}">${formatTimestamp(cert.expiresAt)} (${cert.daysUntilExpiry} days)</span>
                    </div>
                    ${cert.issuer ? `
                        <div class="cert-detail-row">
                            <span class="label">Issuer:</span>
                            <span class="value">${cert.issuer}</span>
                        </div>
                    ` : ''}
                </div>

                ${isLetsEncrypt ? `
                    <div class="cert-auto-renew">
                        <h4><span class="material-symbols-outlined">autorenew</span> Auto-Renewal</h4>
                        <label class="switch-label">
                            <md-switch id="auto-renew-${cert.id}" ${cert.autoRenew ? 'selected' : ''} data-cert-id="${cert.id}"></md-switch>
                            <span>Automatically renew when expiring (within 30 days)</span>
                        </label>
                    </div>
                ` : ''}
            ` : ''}

            ${isUpload && isPending ? `
                <div class="cert-requirements">
                    <h4><span class="material-symbols-outlined">upload_file</span> Upload Certificate</h4>
                    <p>Upload your SSL/TLS certificate and private key for <code>${cert.hostname}</code>.</p>
                </div>
            ` : ''}

            ${isExternal && isPending ? `
                <div class="cert-requirements">
                    <h4><span class="material-symbols-outlined">security</span> External Certificate</h4>
                    <p>Place your certificate files in the nginx container at:</p>
                    <code>/etc/nginx/certs/${cert.hostname}/fullchain.pem</code><br>
                    <code>/etc/nginx/certs/${cert.hostname}/privkey.pem</code>
                </div>
            ` : ''}

            <!-- Action Buttons -->
            <div class="cert-actions">
                ${isLetsEncrypt && isPending ? `
                    <md-filled-button class="request-cert-btn" data-cert-id="${cert.id}">
                        <span class="material-symbols-outlined" slot="icon">play_arrow</span>
                        Request Certificate
                    </md-filled-button>
                ` : ''}
                ${isLetsEncrypt && isError ? `
                    <md-filled-button class="retry-cert-btn" data-cert-id="${cert.id}">
                        <span class="material-symbols-outlined" slot="icon">refresh</span>
                        Retry Request
                    </md-filled-button>
                ` : ''}
                ${isLetsEncrypt && isActive ? `
                    <md-filled-button class="renew-cert-btn" data-cert-id="${cert.id}" ${cert.renewalNeeded ? '' : 'style="--md-filled-button-container-color: var(--md-sys-color-secondary-container); --md-filled-button-label-text-color: var(--md-sys-color-on-secondary-container);"'}>
                        <span class="material-symbols-outlined" slot="icon">autorenew</span>
                        ${cert.renewalNeeded ? 'Renew Now' : 'Renew Early'}
                    </md-filled-button>
                ` : ''}
                ${isUpload && isPending ? `
                    <md-filled-button class="upload-cert-btn" data-cert-id="${cert.id}">
                        <span class="material-symbols-outlined" slot="icon">upload</span>
                        Upload Certificate
                    </md-filled-button>
                ` : ''}
                <md-outlined-button class="check-dns-btn" data-hostname="${cert.hostname}">
                    <span class="material-symbols-outlined" slot="icon">search</span>
                    Check DNS
                </md-outlined-button>
                <md-text-button class="view-audit-btn" data-cert-id="${cert.id}">
                    <span class="material-symbols-outlined" slot="icon">history</span>
                    View Audit Log
                </md-text-button>
                <md-text-button class="delete-cert-btn" data-cert-id="${cert.id}" style="--md-text-button-label-text-color: var(--md-sys-color-error);">
                    <span class="material-symbols-outlined" slot="icon">delete</span>
                    Delete
                </md-text-button>
            </div>
        </div>
    `;
}

function getStatusIcon(status) {
    switch (status) {
        case 'ACTIVE': return 'check_circle';
        case 'PENDING': return 'hourglass_empty';
        case 'ERROR': return 'error';
        case 'RENEWAL_PENDING': return 'autorenew';
        case 'EXPIRED': return 'event_busy';
        default: return 'help';
    }
}

function getDefaultStatusMessage(cert) {
    switch (cert.status) {
        case 'ACTIVE': return 'Certificate is active and valid.';
        case 'PENDING': return cert.source === 'LETS_ENCRYPT'
            ? 'Ready to request certificate. Click "Request Certificate" to begin.'
            : 'Awaiting certificate configuration.';
        case 'ERROR': return 'Certificate request failed. See error details above.';
        case 'RENEWAL_PENDING': return 'Certificate renewal in progress...';
        case 'EXPIRED': return 'Certificate has expired!';
        default: return 'Unknown status.';
    }
}

function getCertStatusIcon(status) {
    switch (status) {
        case 'ACTIVE': return { icon: 'verified', class: 'success' };
        case 'PENDING': return { icon: 'hourglass_empty', class: 'pending' };
        case 'EXPIRED': return { icon: 'event_busy', class: 'error' };
        case 'ERROR': return { icon: 'error', class: 'error' };
        case 'RENEWAL_PENDING': return { icon: 'autorenew', class: 'pending' };
        default: return { icon: 'help', class: '' };
    }
}

function formatCertSource(source) {
    switch (source) {
        case 'LETS_ENCRYPT': return "Let's Encrypt";
        case 'UPLOADED': return 'Uploaded';
        case 'EXTERNAL': return 'External';
        default: return source;
    }
}

function formatCertStatus(status) {
    switch (status) {
        case 'ACTIVE': return 'Active';
        case 'PENDING': return 'Pending';
        case 'EXPIRED': return 'Expired';
        case 'ERROR': return 'Error';
        case 'RENEWAL_PENDING': return 'Renewing';
        default: return status;
    }
}

function renderNginxConfigTab() {
    return `
        <div class="nginx-config-tab">
            <div class="tab-header">
                <h3>Nginx Configuration</h3>
                <md-outlined-button id="refresh-config-btn">
                    <span class="material-symbols-outlined" slot="icon">refresh</span>
                    Refresh
                </md-outlined-button>
            </div>
            <div id="nginx-config-container" class="config-container">
                <div class="loading-container">
                    <md-circular-progress indeterminate></md-circular-progress>
                </div>
            </div>
        </div>
    `;
}

function renderAuditTab() {
    return `
        <div class="audit-tab">
            <div class="tab-header">
                <h3>Audit Log</h3>
            </div>

            ${auditLogs.length === 0 ? `
                <div class="empty-state small">
                    <span class="material-symbols-outlined">history</span>
                    <h4>No Audit Entries</h4>
                    <p>Actions will be logged here.</p>
                </div>
            ` : `
                <div class="audit-list">
                    ${auditLogs.map(log => `
                        <div class="audit-entry ${log.success ? '' : 'failed'}">
                            <div class="audit-icon">
                                <span class="material-symbols-outlined ${log.success ? 'success' : 'error'}">
                                    ${log.success ? 'check_circle' : 'error'}
                                </span>
                            </div>
                            <div class="audit-info">
                                <div class="audit-action">${formatAuditAction(log.action)}</div>
                                <div class="audit-details">${log.details || ''}</div>
                                ${log.errorMessage ? `<div class="audit-error">${log.errorMessage}</div>` : ''}
                            </div>
                            <div class="audit-meta">
                                <span class="audit-user">${log.username}</span>
                                <span class="audit-time">${formatTimestamp(log.timestamp)}</span>
                            </div>
                        </div>
                    `).join('')}
                </div>
            `}
        </div>
    `;
}

function setupEnabledEventListeners() {
    // Tab switching
    document.getElementById('ingress-tabs')?.addEventListener('click', (e) => {
        const tab = e.target.closest('md-primary-tab');
        if (tab) {
            currentTab = tab.dataset.tab;
            document.getElementById('tab-content').innerHTML = renderTabContent();
            setupTabEventListeners();
        }
    });

    // Header buttons
    document.getElementById('reload-nginx-btn')?.addEventListener('click', handleReloadNginx);
    document.getElementById('disable-ingress-btn')?.addEventListener('click', showDisableDialog);

    setupTabEventListeners();
}

function setupTabEventListeners() {
    // Routes tab
    document.getElementById('expose-app-btn')?.addEventListener('click', showExposeAppView);
    document.querySelectorAll('.delete-route-btn').forEach(btn => {
        btn.addEventListener('click', () => handleDeleteRoute(btn.dataset.routeId));
    });

    // Certificates tab
    document.querySelectorAll('.expand-cert-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            const certId = btn.dataset.certId;
            const panel = document.getElementById(`cert-details-${certId}`);
            const icon = btn.querySelector('.material-symbols-outlined');
            if (panel) {
                panel.classList.toggle('hidden');
                icon.textContent = panel.classList.contains('hidden') ? 'expand_more' : 'expand_less';
            }
        });
    });

    document.querySelectorAll('.check-dns-btn').forEach(btn => {
        btn.addEventListener('click', () => handleCheckDns(btn.dataset.hostname));
    });

    document.querySelectorAll('.request-cert-btn').forEach(btn => {
        btn.addEventListener('click', () => handleRequestCertificate(btn.dataset.certId));
    });

    document.querySelectorAll('.retry-cert-btn').forEach(btn => {
        btn.addEventListener('click', () => handleRetryCertificate(btn.dataset.certId));
    });

    document.querySelectorAll('.renew-cert-btn').forEach(btn => {
        btn.addEventListener('click', () => handleRenewCertificate(btn.dataset.certId));
    });

    document.querySelectorAll('.upload-cert-btn').forEach(btn => {
        btn.addEventListener('click', () => handleUploadCertificate(btn.dataset.certId));
    });

    document.querySelectorAll('.delete-cert-btn').forEach(btn => {
        btn.addEventListener('click', () => handleDeleteCertificate(btn.dataset.certId));
    });

    // Auto-renew toggle handlers
    document.querySelectorAll('md-switch[id^="auto-renew-"]').forEach(toggle => {
        toggle.addEventListener('change', () => handleAutoRenewToggle(toggle.dataset.certId, toggle.selected));
    });

    document.querySelectorAll('.view-audit-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            currentTab = 'audit';
            document.getElementById('tab-content').innerHTML = renderTabContent();
            setupTabEventListeners();
            // Update tab selection
            document.querySelectorAll('#ingress-tabs md-primary-tab').forEach(tab => {
                tab.removeAttribute('active');
                if (tab.dataset.tab === 'audit') {
                    tab.setAttribute('active', '');
                }
            });
        });
    });

    // Nginx config tab
    document.getElementById('refresh-config-btn')?.addEventListener('click', loadNginxConfig);
    if (currentTab === 'nginx-config') {
        loadNginxConfig();
    }
}

async function loadNginxConfig() {
    const container = document.getElementById('nginx-config-container');
    if (!container) return;

    try {
        const response = await ingressApi.getNginxConfig(state.currentHostId);
        container.innerHTML = `
            <pre class="nginx-config"><code>${escapeHtml(response.config || '# No configuration generated yet')}</code></pre>
            ${response.valid === false ? `
                <div class="config-errors">
                    <span class="material-symbols-outlined">error</span>
                    <span>Configuration has validation errors</span>
                </div>
            ` : ''}
        `;
    } catch (error) {
        container.innerHTML = `
            <div class="empty-state small">
                <span class="material-symbols-outlined">error</span>
                <p>Failed to load nginx config: ${error.message}</p>
            </div>
        `;
    }
}

async function showEnableDialog() {
    const hostId = state.currentHostId;

    try {
        // Preview what will be created
        const preview = await ingressApi.previewEnableIngress(hostId, {
            httpPort: 80,
            httpsPort: 443,
            acmeProxyPort: 8080,
            acmeEmail: null,
            useStaging: false,
        });

        const dialog = document.getElementById('app-dialog');
        dialog.querySelector('.dialog-title').textContent = 'Enable Managed Ingress';
        dialog.querySelector('.dialog-content').innerHTML = `
            <div class="enable-preview">
                <p>Enabling managed ingress will create the following resources:</p>

                <div class="preview-section">
                    <h4><span class="material-symbols-outlined">inventory_2</span> Docker Container</h4>
                    <ul>
                        <li>Image: <code>${preview.nginxImage}</code></li>
                        <li>Name: <code>${preview.containerName}</code></li>
                        <li>Ports: ${preview.httpPort} (HTTP), ${preview.httpsPort} (HTTPS)</li>
                    </ul>
                </div>

                <div class="preview-section">
                    <h4><span class="material-symbols-outlined">hub</span> Docker Network</h4>
                    <ul>
                        <li>Name: <code>${preview.networkName}</code></li>
                        <li>Driver: bridge</li>
                    </ul>
                </div>

                ${preview.warnings?.length > 0 ? `
                    <div class="preview-warnings">
                        <h4><span class="material-symbols-outlined">warning</span> Warnings</h4>
                        <ul>
                            ${preview.warnings.map(w => `<li>${w}</li>`).join('')}
                        </ul>
                    </div>
                ` : ''}

                <div class="preview-note">
                    <span class="material-symbols-outlined">info</span>
                    <span>No apps will be exposed automatically. You'll configure routes explicitly.</span>
                </div>

                <md-filled-text-field
                    id="acme-email"
                    label="ACME Email (optional)"
                    type="email"
                    placeholder="admin@example.com"
                    supporting-text="Required for Let's Encrypt certificates">
                </md-filled-text-field>

                <md-filled-text-field
                    id="acme-proxy-port"
                    label="App Port (for Docker deployments)"
                    type="number"
                    value="8080"
                    supporting-text="Port where this app is accessible on the host (e.g., 4001 if using 4001:8080 mapping)">
                </md-filled-text-field>
            </div>
        `;
        dialog.querySelector('.dialog-actions').innerHTML = `
            <md-text-button form="dialog" value="cancel">Cancel</md-text-button>
            <md-filled-button id="confirm-enable-btn">Enable Ingress</md-filled-button>
        `;

        dialog.show();

        document.getElementById('confirm-enable-btn')?.addEventListener('click', async () => {
            const acmeEmail = document.getElementById('acme-email')?.value || null;
            const acmeProxyPort = parseInt(document.getElementById('acme-proxy-port')?.value) || 8080;
            dialog.close();
            await handleEnableIngress(acmeEmail, acmeProxyPort);
        });
    } catch (error) {
        showToast(`Failed to preview: ${error.message}`, 'error');
    }
}

async function handleEnableIngress(acmeEmail, acmeProxyPort = 8080) {
    const hostId = state.currentHostId;

    try {
        showToast('Enabling managed ingress...', 'info');

        await ingressApi.enableIngress(hostId, {
            httpPort: 80,
            httpsPort: 443,
            acmeProxyPort: acmeProxyPort,
            acmeEmail: acmeEmail,
            useStaging: false,
        });

        showToast('Managed ingress enabled successfully!', 'success');
        await loadIngressStatus();
    } catch (error) {
        showToast(`Failed to enable ingress: ${error.message}`, 'error');
    }
}

async function showDisableDialog() {
    const hostId = state.currentHostId;

    try {
        const preview = await ingressApi.previewDisableIngress(hostId);

        const confirmed = await showConfirm({
            title: 'Disable Managed Ingress',
            message: 'This will stop all ingress routing and remove the nginx container.',
            confirmText: 'Disable Ingress',
            type: 'danger',
            details: `
                ${preview.affectedRoutes?.length > 0 ? `
                    <div class="affected-routes">
                        <h4>Routes that will stop working:</h4>
                        <ul>
                            ${preview.affectedRoutes.map(r => `
                                <li><code>${r.hostname}${r.pathPrefix}</code> -> ${r.targetContainerName}</li>
                            `).join('')}
                        </ul>
                    </div>
                ` : ''}
                <div class="preview-section">
                    <h4>Resources to be removed:</h4>
                    <ul>
                        <li>Container: <code>${preview.containerName || 'managed-ingress-proxy'}</code></li>
                        <li>Network: <code>${preview.networkName || 'managed-ingress-network'}</code></li>
                    </ul>
                </div>
                <p class="info-text">
                    <span class="material-symbols-outlined">info</span>
                    Route configurations will be preserved and can be restored if you re-enable ingress.
                </p>
            `,
        });

        if (confirmed) {
            await handleDisableIngress();
        }
    } catch (error) {
        showToast(`Failed to preview disable: ${error.message}`, 'error');
    }
}

async function handleDisableIngress() {
    const hostId = state.currentHostId;

    try {
        showToast('Disabling managed ingress...', 'info');

        await ingressApi.disableIngress(hostId);

        showToast('Managed ingress disabled successfully!', 'success');
        await loadIngressStatus();
    } catch (error) {
        showToast(`Failed to disable ingress: ${error.message}`, 'error');
    }
}

async function handleReloadNginx() {
    const hostId = state.currentHostId;

    try {
        await ingressApi.reloadNginx(hostId);
        showToast('Nginx configuration reloaded', 'success');
    } catch (error) {
        showToast(`Failed to reload nginx: ${error.message}`, 'error');
    }
}

async function showExposeAppView() {
    const hostId = state.currentHostId;

    try {
        // Load containers and certificates in parallel
        const [containerList, certList] = await Promise.all([
            listContainers(hostId, true),
            ingressApi.listCertificates(hostId)
        ]);

        containers = containerList.filter(c => c.state === 'running');
        availableCerts = certList || [];

        if (containers.length === 0) {
            showToast('No running containers to expose', 'warning');
            return;
        }

        currentView = 'expose';
        const content = document.getElementById('ingress-content');
        content.innerHTML = renderExposeAppView();
        setupExposeAppListeners();
    } catch (error) {
        showToast(`Failed to load containers: ${error.message}`, 'error');
    }
}

function renderExposeAppView() {
    return `
        <div class="expose-app-page">
            <div class="section-header">
                <div class="section-header-left">
                    <md-icon-button id="back-to-ingress-btn" title="Back to ingress">
                        <span class="material-symbols-outlined">arrow_back</span>
                    </md-icon-button>
                    <h2 class="section-title">Expose Application</h2>
                </div>
            </div>

            <div class="expose-app-content">
                <!-- Container Selection Card -->
                <div class="card">
                    <div class="card-header">
                        <span class="card-title">
                            <span class="material-symbols-outlined">deployed_code</span>
                            Target Container
                        </span>
                    </div>
                    <div class="card-content">
                        <div class="form-row two-col">
                            <div class="form-field">
                                <md-filled-select id="container-select" label="Container" required style="width: 100%;">
                                    ${containers.map(c => {
                                        const name = c.names?.[0]?.replace(/^\//, '') || c.id.substring(0, 12);
                                        const ports = c.ports?.map(p => p.privatePort).filter(Boolean).join(', ') || 'no ports';
                                        return `
                                            <md-select-option value="${c.id}">
                                                <span slot="headline">${name}</span>
                                                <span slot="supporting-text">Ports: ${ports}</span>
                                            </md-select-option>
                                        `;
                                    }).join('')}
                                </md-filled-select>
                                <span class="form-hint">Select the container to expose</span>
                            </div>
                            <div class="form-field">
                                <md-filled-text-field
                                    id="port-input"
                                    label="Container Port"
                                    type="number"
                                    value="80"
                                    style="width: 100%;"
                                    required>
                                </md-filled-text-field>
                                <span class="form-hint">The port your application listens on inside the container</span>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- Routing Configuration Card -->
                <div class="card">
                    <div class="card-header">
                        <span class="card-title">
                            <span class="material-symbols-outlined">route</span>
                            Routing Configuration
                        </span>
                    </div>
                    <div class="card-content">
                        <div class="form-row two-col">
                            <div class="form-field">
                                <md-filled-text-field
                                    id="hostname-input"
                                    label="Hostname"
                                    type="text"
                                    placeholder="app.example.com"
                                    style="width: 100%;"
                                    required>
                                </md-filled-text-field>
                                <span class="form-hint">The domain name to route to this container</span>
                            </div>
                            <div class="form-field">
                                <md-filled-text-field
                                    id="path-input"
                                    label="Path Prefix"
                                    type="text"
                                    value="/"
                                    placeholder="/"
                                    style="width: 100%;">
                                </md-filled-text-field>
                                <span class="form-hint">Route requests starting with this path (e.g., /api)</span>
                            </div>
                        </div>
                        <div class="form-row">
                            <div class="form-field">
                                <md-filled-select id="protocol-select" label="Backend Protocol" style="width: 100%;">
                                    <md-select-option value="HTTP" selected>
                                        <span slot="headline">HTTP</span>
                                        <span slot="supporting-text">Connect to container over HTTP</span>
                                    </md-select-option>
                                    <md-select-option value="HTTPS">
                                        <span slot="headline">HTTPS</span>
                                        <span slot="supporting-text">Connect to container over HTTPS (for apps with their own TLS)</span>
                                    </md-select-option>
                                </md-filled-select>
                                <span class="form-hint">Protocol used to communicate with the container</span>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- TLS Configuration Card -->
                <div class="card">
                    <div class="card-header">
                        <span class="card-title">
                            <span class="material-symbols-outlined">lock</span>
                            TLS / HTTPS
                        </span>
                    </div>
                    <div class="card-content">
                        <div class="form-row">
                            <div class="form-field">
                                <md-filled-select id="tls-mode-select" label="TLS Mode" style="width: 100%;">
                                    <md-select-option value="NONE" selected>
                                        <span slot="headline">None (HTTP only)</span>
                                        <span slot="supporting-text">No encryption - only use for local/testing</span>
                                    </md-select-option>
                                    <md-select-option value="LETS_ENCRYPT">
                                        <span slot="headline">Let's Encrypt (automatic)</span>
                                        <span slot="supporting-text">Free automatic certificates - requires valid domain pointing to this server</span>
                                    </md-select-option>
                                    <md-select-option value="CUSTOM_CERT">
                                        <span slot="headline">Custom Certificate</span>
                                        <span slot="supporting-text">Upload or select an existing certificate</span>
                                    </md-select-option>
                                    <md-select-option value="BRING_YOUR_OWN">
                                        <span slot="headline">Self-signed / External</span>
                                        <span slot="supporting-text">Generate self-signed or use externally managed cert</span>
                                    </md-select-option>
                                </md-filled-select>
                            </div>
                        </div>

                        <div id="tls-options" class="tls-options hidden">
                            <label class="switch-label">
                                <md-switch id="redirect-http"></md-switch>
                                <span>Redirect HTTP to HTTPS</span>
                            </label>
                        </div>

                        <div id="tls-info" class="tls-info hidden">
                            <div class="info-banner">
                                <span class="material-symbols-outlined">info</span>
                                <div>
                                    <strong>Let's Encrypt Requirements</strong>
                                    <p>Ensure DNS is configured and port 80 is accessible from the internet for certificate issuance.</p>
                                </div>
                            </div>
                        </div>

                        <div id="custom-cert-options" class="custom-cert-options hidden">
                            <div class="form-row">
                                <div class="form-field">
                                    <md-filled-select id="cert-source-select" label="Certificate Source" style="width: 100%;">
                                        <md-select-option value="upload" selected>
                                            <span slot="headline">Upload New Certificate</span>
                                        </md-select-option>
                                        ${availableCerts.filter(c => c.status === 'ACTIVE').map(c => `
                                            <md-select-option value="${c.id}">
                                                <span slot="headline">Use existing: ${c.hostname}</span>
                                                <span slot="supporting-text">Expires: ${new Date(c.expiresAt).toLocaleDateString()}</span>
                                            </md-select-option>
                                        `).join('')}
                                    </md-filled-select>
                                </div>
                            </div>

                            <div id="cert-upload-fields" class="cert-upload-fields">
                                <div class="info-banner" style="margin-bottom: var(--spacing-md);">
                                    <span class="material-symbols-outlined">info</span>
                                    <div>
                                        <strong>Certificate Upload</strong>
                                        <p>You can upload the certificate now or after creating the route.</p>
                                    </div>
                                </div>
                                <div class="form-row">
                                    <div class="form-field">
                                        <label class="form-label">Certificate (PEM format)</label>
                                        <textarea id="cert-pem-input" class="cert-textarea" placeholder="-----BEGIN CERTIFICATE-----&#10;...&#10;-----END CERTIFICATE-----"></textarea>
                                    </div>
                                </div>
                                <div class="form-row">
                                    <div class="form-field">
                                        <label class="form-label">Private Key (PEM format)</label>
                                        <textarea id="key-pem-input" class="cert-textarea" placeholder="-----BEGIN PRIVATE KEY-----&#10;...&#10;-----END PRIVATE KEY-----"></textarea>
                                    </div>
                                </div>
                                <div class="form-row">
                                    <div class="form-field">
                                        <label class="form-label">Certificate Chain (optional)</label>
                                        <textarea id="chain-pem-input" class="cert-textarea" placeholder="-----BEGIN CERTIFICATE-----&#10;...&#10;-----END CERTIFICATE-----"></textarea>
                                        <span class="form-hint">Include intermediate certificates if needed</span>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- Validation Errors -->
                <div id="validation-errors" class="validation-errors hidden"></div>

                <!-- Actions -->
                <div class="form-actions">
                    <md-outlined-button id="cancel-expose-btn">Cancel</md-outlined-button>
                    <md-filled-button id="create-route-btn">
                        <span class="material-symbols-outlined" slot="icon">check</span>
                        Expose Application
                    </md-filled-button>
                </div>
            </div>
        </div>
    `;
}

function setupExposeAppListeners() {
    // Back button
    document.getElementById('back-to-ingress-btn')?.addEventListener('click', backToIngressMain);
    document.getElementById('cancel-expose-btn')?.addEventListener('click', backToIngressMain);

    // Show/hide TLS options based on selection
    const tlsModeSelect = document.getElementById('tls-mode-select');
    const tlsOptions = document.getElementById('tls-options');
    const tlsInfo = document.getElementById('tls-info');
    const customCertOptions = document.getElementById('custom-cert-options');
    const certSourceSelect = document.getElementById('cert-source-select');
    const certUploadFields = document.getElementById('cert-upload-fields');

    tlsModeSelect?.addEventListener('change', () => {
        const mode = tlsModeSelect.value;
        if (mode !== 'NONE') {
            tlsOptions?.classList.remove('hidden');
        } else {
            tlsOptions?.classList.add('hidden');
        }
        if (mode === 'LETS_ENCRYPT') {
            tlsInfo?.classList.remove('hidden');
        } else {
            tlsInfo?.classList.add('hidden');
        }
        if (mode === 'CUSTOM_CERT') {
            customCertOptions?.classList.remove('hidden');
        } else {
            customCertOptions?.classList.add('hidden');
        }
    });

    // Show/hide cert upload fields based on cert source selection
    certSourceSelect?.addEventListener('change', () => {
        const source = certSourceSelect.value;
        if (source === 'upload') {
            certUploadFields?.classList.remove('hidden');
        } else {
            certUploadFields?.classList.add('hidden');
        }
    });

    // Create route button
    document.getElementById('create-route-btn')?.addEventListener('click', handleExposeAppSubmit);
}

async function handleExposeAppSubmit() {
    const containerId = document.getElementById('container-select')?.value;
    const hostname = document.getElementById('hostname-input')?.value?.trim();
    const port = parseInt(document.getElementById('port-input')?.value) || 80;
    const pathPrefix = document.getElementById('path-input')?.value?.trim() || '/';
    const protocol = document.getElementById('protocol-select')?.value || 'HTTP';
    const tlsMode = document.getElementById('tls-mode-select')?.value || 'NONE';
    const forceHttpsRedirect = document.getElementById('redirect-http')?.selected || false;
    const container = containers.find(c => c.id === containerId);

    // Get custom certificate options if CUSTOM_CERT mode
    let certificateId = null;
    let certUploadData = null;
    if (tlsMode === 'CUSTOM_CERT') {
        const certSource = document.getElementById('cert-source-select')?.value || 'upload';
        if (certSource === 'upload') {
            const certPem = document.getElementById('cert-pem-input')?.value?.trim();
            const keyPem = document.getElementById('key-pem-input')?.value?.trim();
            const chainPem = document.getElementById('chain-pem-input')?.value?.trim();
            if (certPem && keyPem) {
                certUploadData = { certPem, keyPem, chainPem };
            }
        } else {
            // Using existing certificate
            certificateId = certSource;
        }
    }

    // Validation
    const errors = [];
    if (!containerId) errors.push('Please select a container');
    if (!hostname) errors.push('Hostname is required');
    if (hostname && !/^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)*$/.test(hostname)) {
        errors.push('Invalid hostname format');
    }
    if (port < 1 || port > 65535) errors.push('Port must be between 1 and 65535');
    if (!pathPrefix.startsWith('/')) errors.push('Path prefix must start with /');

    if (errors.length > 0) {
        const errorsDiv = document.getElementById('validation-errors');
        errorsDiv.innerHTML = errors.map(e => `<div class="error-item">${e}</div>`).join('');
        errorsDiv.classList.remove('hidden');
        return;
    }

    // Get preview with warnings
    try {
        const preview = await ingressApi.previewExposeApp(state.currentHostId, {
            containerId,
            containerName: container?.names?.[0]?.replace(/^\//, '') || containerId.substring(0, 12),
            hostname,
            targetPort: port,
            pathPrefix,
            tlsMode,
            protocol,
            authEnabled: false,
            authType: null,
            authConfig: null,
            certificateId,
            forceHttpsRedirect,
        });

        // Show preview/confirmation if there are warnings
        if (preview.warnings?.length > 0 || tlsMode !== 'NONE') {
            const confirmed = await showRoutePreviewDialog(preview, { containerId, hostname, port, pathPrefix, protocol, tlsMode, forceHttpsRedirect, container, certificateId, certUploadData });
            if (confirmed) {
                await handleCreateRoute(containerId, hostname, port, pathPrefix, protocol, tlsMode, forceHttpsRedirect, certificateId, certUploadData);
                backToIngressMain();
            }
        } else {
            await handleCreateRoute(containerId, hostname, port, pathPrefix, protocol, tlsMode, forceHttpsRedirect, certificateId, certUploadData);
            backToIngressMain();
        }
    } catch (error) {
        const errorsDiv = document.getElementById('validation-errors');
        errorsDiv.innerHTML = `<div class="error-item">${error.message}</div>`;
        errorsDiv.classList.remove('hidden');
    }
}

async function backToIngressMain() {
    currentView = 'main';
    await loadIngressStatus();
}

async function showRoutePreviewDialog(preview, routeConfig) {
    return new Promise((resolve) => {
        const { hostname, port, tlsMode, container } = routeConfig;
        const containerName = container?.names?.[0]?.replace(/^\//, '') || 'container';

        const hasWarnings = preview.warnings?.length > 0;
        const hasTls = tlsMode !== 'NONE';

        const dialog = document.getElementById('confirm-dialog');
        dialog.querySelector('.confirm-title').textContent = 'Confirm Route Creation';
        dialog.querySelector('.confirm-content').innerHTML = `
            <div class="route-preview">
                <div class="preview-summary">
                    <p>Create route: <strong>${hostname}</strong> → <strong>${containerName}:${port}</strong></p>
                </div>

                ${hasTls ? `
                    <div class="preview-section tls-info">
                        <h4><span class="material-symbols-outlined">lock</span> TLS Certificate</h4>
                        <p><strong>Mode:</strong> ${formatTlsMode(tlsMode)}</p>
                        <p><strong>Status:</strong> ${preview.certificateStatus || 'Will be requested'}</p>
                    </div>
                ` : ''}

                ${hasWarnings ? `
                    <div class="preview-warnings">
                        <h4><span class="material-symbols-outlined">warning</span> Important Notes</h4>
                        <ul>
                            ${preview.warnings.map(w => `<li>${w}</li>`).join('')}
                        </ul>
                    </div>
                ` : ''}

                ${tlsMode === 'LETS_ENCRYPT' ? `
                    <div class="preview-section dns-info">
                        <h4><span class="material-symbols-outlined">dns</span> DNS Configuration Required</h4>
                        <p>Before the certificate can be issued, ensure:</p>
                        <ol>
                            <li>DNS A record for <code>${hostname}</code> points to this server's public IP</li>
                            <li>Port 80 is accessible from the internet (for HTTP-01 challenge)</li>
                            <li>No firewall blocking incoming connections on port 80</li>
                        </ol>
                    </div>
                ` : ''}
            </div>
        `;

        // Update button styles
        const cancelBtn = dialog.querySelector('.confirm-cancel');
        const okBtn = dialog.querySelector('.confirm-ok');

        const newCancelBtn = cancelBtn.cloneNode(true);
        const newOkBtn = okBtn.cloneNode(true);
        newOkBtn.textContent = 'Create Route';
        newCancelBtn.textContent = 'Cancel';

        cancelBtn.parentNode.replaceChild(newCancelBtn, cancelBtn);
        okBtn.parentNode.replaceChild(newOkBtn, okBtn);

        newCancelBtn.addEventListener('click', () => {
            dialog.close();
            resolve(false);
        });

        newOkBtn.addEventListener('click', () => {
            dialog.close();
            resolve(true);
        });

        dialog.addEventListener('close', () => {
            resolve(false);
        }, { once: true });

        dialog.show();
    });
}

function formatTlsMode(mode) {
    switch (mode) {
        case 'LETS_ENCRYPT': return "Let's Encrypt (automatic)";
        case 'CUSTOM_CERT': return 'Custom Certificate';
        case 'BRING_YOUR_OWN': return 'Self-signed / External';
        default: return mode;
    }
}

async function handleRequestCertificate(certId) {
    const cert = certificates.find(c => c.id === certId);
    if (!cert) return;

    const confirmed = await showConfirm({
        title: 'Request Certificate',
        message: `Start Let's Encrypt certificate request for ${cert.hostname}?`,
        details: `
            <div style="font-size: 13px; color: var(--md-sys-color-on-surface-variant);">
                <p><strong>Before proceeding, ensure:</strong></p>
                <ul style="margin: 8px 0; padding-left: 20px;">
                    <li>DNS A/AAAA record for <code>${cert.hostname}</code> points to this server</li>
                    <li>Port 80 is accessible from the internet</li>
                </ul>
                <p>The request process will run in the background. Check the Certificates tab for status updates.</p>
            </div>
        `,
        confirmText: 'Request Certificate',
        type: 'info',
    });

    if (!confirmed) return;

    try {
        showToast(`Starting certificate request for ${cert.hostname}...`, 'info');
        await ingressApi.requestCertificate(certId);
        showToast('Certificate request started. This may take a few minutes.', 'success');

        // Start polling for updates
        startCertificatePolling(certId);
    } catch (error) {
        showToast(`Failed to start certificate request: ${error.message}`, 'error');
    }
}

async function handleRetryCertificate(certId) {
    const cert = certificates.find(c => c.id === certId);
    if (!cert) return;

    const confirmed = await showConfirm({
        title: 'Retry Certificate Request',
        message: `Retry the certificate request for ${cert.hostname}?`,
        details: cert.lastRenewalError ? `
            <div style="font-size: 13px;">
                <p><strong>Previous error:</strong></p>
                <p style="color: var(--md-sys-color-error);">${cert.lastRenewalError}</p>
            </div>
        ` : null,
        confirmText: 'Retry',
        type: 'warning',
    });

    if (!confirmed) return;

    try {
        showToast(`Retrying certificate request for ${cert.hostname}...`, 'info');
        await ingressApi.retryCertificate(certId);
        showToast('Certificate request restarted. This may take a few minutes.', 'success');

        startCertificatePolling(certId);
    } catch (error) {
        showToast(`Failed to retry certificate request: ${error.message}`, 'error');
    }
}

async function handleRenewCertificate(certId) {
    const cert = certificates.find(c => c.id === certId);
    if (!cert) return;

    const confirmed = await showConfirm({
        title: 'Renew Certificate',
        message: `Renew the certificate for ${cert.hostname}?`,
        details: `
            <div style="font-size: 13px;">
                <p>Current certificate expires in <strong>${cert.daysUntilExpiry} days</strong>.</p>
                <p>The renewal will run in the background.</p>
            </div>
        `,
        confirmText: 'Renew Now',
        type: 'info',
    });

    if (!confirmed) return;

    try {
        showToast(`Starting certificate renewal for ${cert.hostname}...`, 'info');
        await ingressApi.renewCertificate(certId);
        showToast('Certificate renewal started. This may take a few minutes.', 'success');

        startCertificatePolling(certId);
    } catch (error) {
        showToast(`Failed to start certificate renewal: ${error.message}`, 'error');
    }
}

async function handleDeleteCertificate(certId) {
    const cert = certificates.find(c => c.id === certId);
    if (!cert) return;

    const confirmed = await showConfirm({
        title: 'Delete Certificate',
        message: `Are you sure you want to delete the certificate for ${cert.hostname}?`,
        details: cert.status === 'ACTIVE' ? `
            <div style="font-size: 13px; color: var(--md-sys-color-error);">
                <p><strong>Warning:</strong> This certificate is currently active. Deleting it will:</p>
                <ul style="margin: 8px 0; padding-left: 20px;">
                    <li>Remove the certificate files from nginx</li>
                    <li>Disable HTTPS for routes using this certificate</li>
                </ul>
            </div>
        ` : null,
        confirmText: 'Delete Certificate',
        type: 'danger',
    });

    if (!confirmed) return;

    try {
        showToast(`Deleting certificate for ${cert.hostname}...`, 'info');
        await ingressApi.deleteCertificate(certId);
        showToast('Certificate deleted successfully', 'success');
        await loadIngressStatus();
    } catch (error) {
        showToast(`Failed to delete certificate: ${error.message}`, 'error');
    }
}

async function handleAutoRenewToggle(certId, enabled) {
    const cert = certificates.find(c => c.id === certId);
    if (!cert) return;

    try {
        await ingressApi.setAutoRenew(certId, enabled);
        showToast(`Auto-renewal ${enabled ? 'enabled' : 'disabled'} for ${cert.hostname}`, 'success');

        // Update local state
        cert.autoRenew = enabled;
    } catch (error) {
        showToast(`Failed to update auto-renewal: ${error.message}`, 'error');

        // Revert the toggle
        const toggle = document.getElementById(`auto-renew-${certId}`);
        if (toggle) {
            toggle.selected = !enabled;
        }
    }
}

async function handleUploadCertificate(certId) {
    const cert = certificates.find(c => c.id === certId);
    if (!cert) return;

    const dialog = document.getElementById('app-dialog');
    dialog.querySelector('.dialog-title').textContent = `Upload Certificate for ${cert.hostname}`;
    dialog.querySelector('.dialog-content').innerHTML = `
        <form id="upload-cert-form" class="upload-cert-form">
            <div class="form-field">
                <label>Certificate (PEM format)</label>
                <textarea id="cert-pem" rows="6" placeholder="-----BEGIN CERTIFICATE-----
...
-----END CERTIFICATE-----" required></textarea>
            </div>
            <div class="form-field">
                <label>Private Key (PEM format)</label>
                <textarea id="key-pem" rows="6" placeholder="-----BEGIN PRIVATE KEY-----
...
-----END PRIVATE KEY-----" required></textarea>
            </div>
            <div class="form-field">
                <label>Chain (PEM format, optional)</label>
                <textarea id="chain-pem" rows="4" placeholder="-----BEGIN CERTIFICATE-----
...
-----END CERTIFICATE-----"></textarea>
            </div>
        </form>
    `;
    dialog.querySelector('.dialog-actions').innerHTML = `
        <md-text-button form="dialog" value="cancel">Cancel</md-text-button>
        <md-filled-button id="upload-cert-submit">Upload</md-filled-button>
    `;

    dialog.show();

    dialog.querySelector('#upload-cert-submit').addEventListener('click', async () => {
        const certPem = dialog.querySelector('#cert-pem').value;
        const keyPem = dialog.querySelector('#key-pem').value;
        const chainPem = dialog.querySelector('#chain-pem').value;

        if (!certPem || !keyPem) {
            showToast('Certificate and private key are required', 'error');
            return;
        }

        try {
            showToast('Uploading certificate...', 'info');
            await ingressApi.uploadCertificate(certId, certPem, keyPem, chainPem || null);
            showToast('Certificate uploaded successfully!', 'success');
            dialog.close();
            await loadIngressStatus();
        } catch (error) {
            showToast(`Failed to upload certificate: ${error.message}`, 'error');
        }
    });
}

let certificatePollingInterval = null;

function startCertificatePolling(certId) {
    // Clear any existing polling
    if (certificatePollingInterval) {
        clearInterval(certificatePollingInterval);
    }

    // Poll every 3 seconds for updates
    certificatePollingInterval = setInterval(async () => {
        try {
            const cert = await ingressApi.getCertificate(certId);

            // Update the certificate in our local array
            const index = certificates.findIndex(c => c.id === certId);
            if (index !== -1) {
                certificates[index] = cert;
            }

            // Re-render certificates tab if we're on it
            if (currentTab === 'certificates') {
                document.getElementById('tab-content').innerHTML = renderTabContent();
                setupTabEventListeners();

                // Re-expand the panel
                const panel = document.getElementById(`cert-details-${certId}`);
                if (panel) {
                    panel.classList.remove('hidden');
                    const btn = document.querySelector(`.expand-cert-btn[data-cert-id="${certId}"] .material-symbols-outlined`);
                    if (btn) btn.textContent = 'expand_less';
                }
            }

            // Stop polling if certificate is active or errored
            if (cert.status === 'ACTIVE' || cert.status === 'ERROR') {
                clearInterval(certificatePollingInterval);
                certificatePollingInterval = null;

                if (cert.status === 'ACTIVE') {
                    showToast(`Certificate for ${cert.hostname} is now active!`, 'success');
                } else {
                    showToast(`Certificate request for ${cert.hostname} failed. Check details.`, 'error');
                }
            }
        } catch (error) {
            console.error('Failed to poll certificate status:', error);
        }
    }, 3000);

    // Stop polling after 10 minutes max
    setTimeout(() => {
        if (certificatePollingInterval) {
            clearInterval(certificatePollingInterval);
            certificatePollingInterval = null;
        }
    }, 10 * 60 * 1000);
}

async function handleCheckDns(hostname) {
    showToast(`Checking DNS for ${hostname}...`, 'info');

    try {
        // Use a public DNS lookup API
        const response = await fetch(`https://dns.google/resolve?name=${encodeURIComponent(hostname)}&type=A`);
        const data = await response.json();

        const dialog = document.getElementById('app-dialog');
        dialog.querySelector('.dialog-title').textContent = `DNS Check: ${hostname}`;

        if (data.Answer && data.Answer.length > 0) {
            const records = data.Answer.filter(r => r.type === 1); // Type 1 = A record
            dialog.querySelector('.dialog-content').innerHTML = `
                <div class="dns-check-result success">
                    <div class="dns-status">
                        <span class="material-symbols-outlined success">check_circle</span>
                        <strong>DNS records found!</strong>
                    </div>
                    <div class="dns-records">
                        <h4>A Records:</h4>
                        <ul>
                            ${records.map(r => `<li><code>${r.data}</code> (TTL: ${r.TTL}s)</li>`).join('')}
                        </ul>
                    </div>
                    <div class="dns-next-steps">
                        <p><span class="material-symbols-outlined">info</span> Verify the IP address above matches your server's public IP. If correct, Let's Encrypt certificate issuance should proceed automatically.</p>
                    </div>
                </div>
            `;
        } else {
            dialog.querySelector('.dialog-content').innerHTML = `
                <div class="dns-check-result error">
                    <div class="dns-status">
                        <span class="material-symbols-outlined error">error</span>
                        <strong>No DNS records found</strong>
                    </div>
                    <div class="dns-help">
                        <p>The hostname <code>${hostname}</code> does not resolve to any IP address.</p>
                        <h4>To fix this:</h4>
                        <ol>
                            <li>Log in to your DNS provider (e.g., Cloudflare, Route53, GoDaddy)</li>
                            <li>Add an <strong>A record</strong> for <code>${hostname}</code></li>
                            <li>Point it to your server's public IP address</li>
                            <li>Wait for DNS propagation (can take up to 48 hours)</li>
                        </ol>
                    </div>
                </div>
            `;
        }

        dialog.querySelector('.dialog-actions').innerHTML = `
            <md-filled-button form="dialog" value="ok">OK</md-filled-button>
        `;

        dialog.show();
    } catch (error) {
        showToast(`DNS check failed: ${error.message}`, 'error');
    }
}

async function handleCreateRoute(containerId, hostname, port, pathPrefix, protocol, tlsMode, forceHttpsRedirect = false, certificateId = null, certUploadData = null) {
    const hostId = state.currentHostId;
    const container = containers.find(c => c.id === containerId);

    try {
        showToast('Creating route...', 'info');

        const result = await ingressApi.createRoute(hostId, {
            containerId: containerId,
            containerName: container?.names?.[0]?.replace(/^\//, '') || containerId.substring(0, 12),
            hostname: hostname,
            targetPort: port,
            pathPrefix: pathPrefix,
            tlsMode: tlsMode,
            protocol: protocol,
            authEnabled: false,
            forceHttpsRedirect: forceHttpsRedirect,
            certificateId: certificateId,
        });

        showToast('Route created successfully!', 'success');

        // If custom cert upload data was provided, upload the certificate now
        if (tlsMode === 'CUSTOM_CERT' && certUploadData && result?.certificateId) {
            try {
                showToast('Uploading certificate...', 'info');
                await ingressApi.uploadCertificate(
                    result.certificateId,
                    certUploadData.certPem,
                    certUploadData.keyPem,
                    certUploadData.chainPem || null
                );
                showToast('Certificate uploaded successfully!', 'success');
            } catch (certError) {
                showToast(`Route created but certificate upload failed: ${certError.message}`, 'warning');
            }
        } else if (tlsMode !== 'NONE' && result?.certificateId) {
            showToast(`Certificate requested for ${hostname}`, 'info');
        }

        await loadIngressStatus();
    } catch (error) {
        showToast(`Failed to create route: ${error.message}`, 'error');
    }
}

async function handleDeleteRoute(routeId) {
    const route = routes.find(r => r.id === routeId);
    if (!route) return;

    const confirmed = await showConfirm({
        title: 'Delete Route',
        message: `Are you sure you want to delete the route for ${route.hostname}${route.pathPrefix}?`,
        confirmText: 'Delete',
        type: 'danger',
    });

    if (confirmed) {
        try {
            await ingressApi.deleteRoute(routeId, false);
            showToast('Route deleted successfully', 'success');
            await loadIngressStatus();
        } catch (error) {
            showToast(`Failed to delete route: ${error.message}`, 'error');
        }
    }
}

// Utility functions
function formatAuditAction(action) {
    return action.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, l => l.toUpperCase());
}

function formatTimestamp(ts) {
    if (!ts) return '';
    const date = new Date(ts);
    return date.toLocaleString();
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
