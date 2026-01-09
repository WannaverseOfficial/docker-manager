// Data Table Component

// Render a data table
export function renderTable(options) {
    const {
        columns,
        data,
        emptyMessage = 'No data available',
        emptyIcon = 'inbox',
        actions = null, // Function that returns action buttons HTML for each row
        rowClass = null, // Function that returns additional class for row
        getId = (row) => row.id, // Function to get row ID
    } = options;

    if (!data || data.length === 0) {
        return `
            <div class="empty-state">
                <span class="material-symbols-outlined">${emptyIcon}</span>
                <h3>${emptyMessage}</h3>
            </div>
        `;
    }

    const headerHtml = columns.map(col =>
        `<th style="${col.width ? `width: ${col.width}` : ''}">${col.label}</th>`
    ).join('');

    const rowsHtml = data.map(row => {
        const rowId = getId(row);
        const extraClass = rowClass ? rowClass(row) : '';

        const cellsHtml = columns.map(col => {
            let value = col.key ? row[col.key] : '';

            // Apply renderer if provided
            if (col.render) {
                value = col.render(value, row);
            }

            const style = col.truncate ? 'max-width: 200px;' : '';
            const className = [
                col.truncate ? 'truncate' : '',
                col.mono ? 'mono' : '',
                col.class || '',
            ].filter(Boolean).join(' ');

            return `<td class="${className}" style="${style}" title="${escapeHtml(String(value))}">${value}</td>`;
        }).join('');

        const actionsHtml = actions ? `<td class="actions">${actions(row)}</td>` : '';

        return `<tr data-id="${rowId}" class="${extraClass}">${cellsHtml}${actionsHtml}</tr>`;
    }).join('');

    const actionsHeader = actions ? '<th style="width: 120px;">Actions</th>' : '';

    return `
        <table class="data-table">
            <thead>
                <tr>${headerHtml}${actionsHeader}</tr>
            </thead>
            <tbody>
                ${rowsHtml}
            </tbody>
        </table>
    `;
}

// Render action buttons
export function renderActions(buttons) {
    return buttons.map(btn => {
        if (btn.hidden) return '';

        const colorStyle = btn.color ? `color: ${btn.color};` : '';
        const disabled = btn.disabled ? 'disabled' : '';

        return `
            <md-icon-button
                class="action-btn ${btn.class || ''}"
                title="${btn.title}"
                data-action="${btn.action}"
                ${disabled}
                style="${colorStyle}"
            >
                <span class="material-symbols-outlined">${btn.icon}</span>
            </md-icon-button>
        `;
    }).join('');
}

// Setup action handlers for a table
export function setupTableActions(containerId, handlers) {
    const container = document.getElementById(containerId);
    if (!container) return;

    container.addEventListener('click', (e) => {
        const btn = e.target.closest('.action-btn, [data-action]');
        if (!btn) return;

        const action = btn.dataset.action;
        const row = btn.closest('tr');
        const id = row?.dataset.id;

        if (action && handlers[action] && id) {
            handlers[action](id, row);
        }
    });
}

// Escape HTML
function escapeHtml(text) {
    if (text == null) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// Format bytes helper
export function formatBytes(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

// Format date helper
export function formatDate(timestamp) {
    if (!timestamp) return 'N/A';
    return new Date(timestamp).toLocaleString();
}

// Truncate string helper
export function truncate(str, len = 12) {
    if (!str) return '';
    if (str.length <= len) return str;
    return str.substring(0, len) + '...';
}
