// SMTP Settings Page (Admin Only)
import { isAdmin } from '../state.js';
import { getSmtpConfig, updateSmtpConfig, deleteSmtpConfig, sendTestEmail, getSmtpStatus } from '../api/notifications.js';
import { showToast } from '../components/toast.js';
import { confirmDelete } from '../components/confirm-dialog.js';

let currentConfig = null;
let isLoading = false;

// Render page
export function render() {
    if (!isAdmin()) {
        return `
            <div class="empty-state">
                <span class="material-symbols-outlined">lock</span>
                <h3>Access Denied</h3>
                <p>You need admin privileges to access this page.</p>
            </div>
        `;
    }

    return `
        <div class="section-header">
            <h2 class="section-title">SMTP Settings</h2>
            <div class="section-actions">
                <md-text-button id="delete-smtp-btn" disabled>
                    <span class="material-symbols-outlined" slot="icon">delete</span>
                    Delete Config
                </md-text-button>
            </div>
        </div>

        <div class="grid-2">
            <div class="card">
                <div class="card-header">
                    <span class="material-symbols-outlined">mail</span>
                    <h3>SMTP Configuration</h3>
                </div>
                <div class="card-content">
                    <form id="smtp-form" class="form-grid">
                        <md-filled-text-field
                            id="smtp-host"
                            label="SMTP Host"
                            placeholder="smtp.example.com"
                            required
                        ></md-filled-text-field>

                        <md-filled-text-field
                            id="smtp-port"
                            label="Port"
                            type="number"
                            value="587"
                            min="1"
                            max="65535"
                            required
                        ></md-filled-text-field>

                        <md-filled-select id="smtp-security" label="Security" required>
                            <md-select-option value="NONE">None</md-select-option>
                            <md-select-option value="STARTTLS" selected>STARTTLS</md-select-option>
                            <md-select-option value="SSL_TLS">SSL/TLS</md-select-option>
                        </md-filled-select>

                        <md-filled-text-field
                            id="smtp-username"
                            label="Username (optional)"
                            placeholder="your-username"
                        ></md-filled-text-field>

                        <md-filled-text-field
                            id="smtp-password"
                            label="Password"
                            type="password"
                            placeholder="Enter to change"
                        ></md-filled-text-field>

                        <md-filled-text-field
                            id="smtp-from-address"
                            label="From Address"
                            type="email"
                            placeholder="noreply@example.com"
                            required
                        ></md-filled-text-field>

                        <md-filled-text-field
                            id="smtp-from-name"
                            label="From Name (optional)"
                            placeholder="Docker Manager"
                        ></md-filled-text-field>

                        <div class="form-row">
                            <label class="switch-label">
                                <md-switch id="smtp-enabled"></md-switch>
                                <span>Enable SMTP</span>
                            </label>
                        </div>

                        <div class="form-actions">
                            <md-filled-button id="save-smtp-btn" type="button">
                                <span class="material-symbols-outlined" slot="icon">save</span>
                                Save Configuration
                            </md-filled-button>
                        </div>
                    </form>
                </div>
            </div>

            <div class="card">
                <div class="card-header">
                    <span class="material-symbols-outlined">send</span>
                    <h3>Test Email</h3>
                </div>
                <div class="card-content">
                    <p class="text-secondary" style="margin-bottom: 16px;">
                        Send a test email to verify your SMTP configuration is working correctly.
                    </p>
                    <div class="form-grid">
                        <md-filled-text-field
                            id="test-email"
                            label="Recipient Email"
                            type="email"
                            placeholder="test@example.com"
                        ></md-filled-text-field>
                        <md-filled-button id="send-test-btn" type="button" disabled>
                            <span class="material-symbols-outlined" slot="icon">send</span>
                            Send Test Email
                        </md-filled-button>
                    </div>
                    <div id="test-result" class="alert hidden"></div>
                </div>
            </div>
        </div>

        <div class="card" style="margin-top: 16px;">
            <div class="card-header">
                <span class="material-symbols-outlined">info</span>
                <h3>Common SMTP Settings</h3>
            </div>
            <div class="card-content">
                <div class="info-table">
                    <table>
                        <thead>
                            <tr>
                                <th>Provider</th>
                                <th>Host</th>
                                <th>Port</th>
                                <th>Security</th>
                            </tr>
                        </thead>
                        <tbody>
                            <tr>
                                <td>Gmail</td>
                                <td><code>smtp.gmail.com</code></td>
                                <td>587</td>
                                <td>STARTTLS</td>
                            </tr>
                            <tr>
                                <td>Outlook/O365</td>
                                <td><code>smtp.office365.com</code></td>
                                <td>587</td>
                                <td>STARTTLS</td>
                            </tr>
                            <tr>
                                <td>SendGrid</td>
                                <td><code>smtp.sendgrid.net</code></td>
                                <td>587</td>
                                <td>STARTTLS</td>
                            </tr>
                            <tr>
                                <td>Mailgun</td>
                                <td><code>smtp.mailgun.org</code></td>
                                <td>587</td>
                                <td>STARTTLS</td>
                            </tr>
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
    `;
}

// Initialize
export async function init() {
    if (!isAdmin()) return;

    document.getElementById('save-smtp-btn')?.addEventListener('click', handleSave);
    document.getElementById('delete-smtp-btn')?.addEventListener('click', handleDelete);
    document.getElementById('send-test-btn')?.addEventListener('click', handleSendTest);

    await loadConfig();
}

// Load current configuration
async function loadConfig() {
    try {
        currentConfig = await getSmtpConfig();

        if (currentConfig) {
            document.getElementById('smtp-host').value = currentConfig.host || '';
            document.getElementById('smtp-port').value = currentConfig.port || 587;
            document.getElementById('smtp-security').value = currentConfig.securityType || 'STARTTLS';
            document.getElementById('smtp-username').value = currentConfig.username || '';
            document.getElementById('smtp-from-address').value = currentConfig.fromAddress || '';
            document.getElementById('smtp-from-name').value = currentConfig.fromName || '';
            document.getElementById('smtp-enabled').selected = currentConfig.enabled;

            // Show placeholder if password exists
            if (currentConfig.hasPassword) {
                document.getElementById('smtp-password').placeholder = '********** (leave blank to keep)';
            }

            document.getElementById('delete-smtp-btn').disabled = false;
            document.getElementById('send-test-btn').disabled = !currentConfig.enabled;
        }
    } catch (error) {
        // No config exists yet, that's fine
        console.log('No SMTP configuration found');
    }
}

// Save configuration
async function handleSave() {
    const host = document.getElementById('smtp-host').value.trim();
    const port = parseInt(document.getElementById('smtp-port').value) || 587;
    const securityType = document.getElementById('smtp-security').value;
    const username = document.getElementById('smtp-username').value.trim();
    const password = document.getElementById('smtp-password').value;
    const fromAddress = document.getElementById('smtp-from-address').value.trim();
    const fromName = document.getElementById('smtp-from-name').value.trim();
    const enabled = document.getElementById('smtp-enabled').selected;

    if (!host || !fromAddress) {
        showToast('Host and From Address are required', 'error');
        return;
    }

    if (port < 1 || port > 65535) {
        showToast('Port must be between 1 and 65535', 'error');
        return;
    }

    const config = {
        host,
        port,
        securityType,
        username: username || null,
        fromAddress,
        fromName: fromName || null,
        enabled,
    };

    // Only include password if it was entered
    if (password) {
        config.password = password;
    }

    try {
        isLoading = true;
        document.getElementById('save-smtp-btn').disabled = true;

        currentConfig = await updateSmtpConfig(config);
        showToast('SMTP configuration saved', 'success');

        document.getElementById('delete-smtp-btn').disabled = false;
        document.getElementById('send-test-btn').disabled = !currentConfig.enabled;

        // Clear password field after save
        document.getElementById('smtp-password').value = '';
        if (currentConfig.hasPassword) {
            document.getElementById('smtp-password').placeholder = '********** (leave blank to keep)';
        }

    } catch (error) {
        showToast(error.message || 'Failed to save configuration', 'error');
    } finally {
        isLoading = false;
        document.getElementById('save-smtp-btn').disabled = false;
    }
}

// Delete configuration
async function handleDelete() {
    const confirmed = await confirmDelete(
        'Delete SMTP Configuration',
        'Are you sure you want to delete the SMTP configuration? Email notifications will be disabled.'
    );

    if (!confirmed) return;

    try {
        await deleteSmtpConfig();
        showToast('SMTP configuration deleted', 'success');

        currentConfig = null;
        document.getElementById('smtp-host').value = '';
        document.getElementById('smtp-port').value = '587';
        document.getElementById('smtp-security').value = 'STARTTLS';
        document.getElementById('smtp-username').value = '';
        document.getElementById('smtp-password').value = '';
        document.getElementById('smtp-password').placeholder = 'Enter to change';
        document.getElementById('smtp-from-address').value = '';
        document.getElementById('smtp-from-name').value = '';
        document.getElementById('smtp-enabled').selected = false;
        document.getElementById('delete-smtp-btn').disabled = true;
        document.getElementById('send-test-btn').disabled = true;

    } catch (error) {
        showToast(error.message || 'Failed to delete configuration', 'error');
    }
}

// Send test email
async function handleSendTest() {
    const testEmail = document.getElementById('test-email').value.trim();
    const resultDiv = document.getElementById('test-result');

    if (!testEmail) {
        showToast('Please enter a recipient email address', 'error');
        return;
    }

    try {
        document.getElementById('send-test-btn').disabled = true;
        resultDiv.classList.add('hidden');

        const result = await sendTestEmail(testEmail);

        resultDiv.classList.remove('hidden', 'alert-success', 'alert-error');

        if (result.success) {
            resultDiv.classList.add('alert-success');
            resultDiv.innerHTML = `
                <span class="material-symbols-outlined">check_circle</span>
                <span>${result.message || 'Test email sent successfully!'}</span>
            `;
            showToast('Test email sent', 'success');
        } else {
            resultDiv.classList.add('alert-error');
            resultDiv.innerHTML = `
                <span class="material-symbols-outlined">error</span>
                <span>${result.error || 'Failed to send test email'}</span>
            `;
        }

    } catch (error) {
        resultDiv.classList.remove('hidden', 'alert-success');
        resultDiv.classList.add('alert-error');
        resultDiv.innerHTML = `
            <span class="material-symbols-outlined">error</span>
            <span>${error.message || 'Failed to send test email'}</span>
        `;
    } finally {
        document.getElementById('send-test-btn').disabled = !currentConfig?.enabled;
    }
}

export function cleanup() {
    currentConfig = null;
    isLoading = false;
}
