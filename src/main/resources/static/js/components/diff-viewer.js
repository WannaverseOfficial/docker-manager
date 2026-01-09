// Diff Viewer Component
// Renders unified and side-by-side diff views

export function renderDiffViewer(diffData, options = {}) {
    const { showStats = true, defaultView = 'unified' } = options;

    if (!diffData) {
        return '<div class="diff-empty">No diff data available</div>';
    }

    const stats = showStats ? `
        <div class="diff-stats">
            <span class="diff-stat additions">+${diffData.additions} additions</span>
            <span class="diff-stat deletions">-${diffData.deletions} deletions</span>
            ${diffData.modifications > 0 ? `<span class="diff-stat modifications">~${diffData.modifications} modifications</span>` : ''}
            ${diffData.identical ? '<span class="diff-stat identical">No changes</span>' : ''}
        </div>
    ` : '';

    return `
        <div class="diff-viewer" data-default-view="${defaultView}">
            <div class="diff-header">
                ${stats}
                <div class="diff-view-toggle">
                    <md-outlined-segmented-button-set>
                        <md-outlined-segmented-button data-view="unified" ${defaultView === 'unified' ? 'selected' : ''}>
                            <span slot="label">Unified</span>
                        </md-outlined-segmented-button>
                        <md-outlined-segmented-button data-view="side-by-side" ${defaultView === 'side-by-side' ? 'selected' : ''}>
                            <span slot="label">Side by Side</span>
                        </md-outlined-segmented-button>
                    </md-outlined-segmented-button-set>
                </div>
            </div>
            <div class="diff-content">
                <div class="diff-unified ${defaultView === 'unified' ? '' : 'hidden'}">
                    ${renderUnifiedDiff(diffData.unifiedDiff)}
                </div>
                <div class="diff-side-by-side ${defaultView === 'side-by-side' ? '' : 'hidden'}">
                    ${renderSideBySideDiff(diffData.sideBySideDiff)}
                </div>
            </div>
        </div>
    `;
}

function renderUnifiedDiff(unifiedDiff) {
    if (!unifiedDiff) return '<pre class="diff-code">No diff content</pre>';

    const lines = unifiedDiff.split('\n');
    const html = lines.map((line, index) => {
        let lineClass = 'diff-line';
        let prefix = ' ';

        if (line.startsWith('+') && !line.startsWith('+++')) {
            lineClass += ' diff-added';
            prefix = '+';
        } else if (line.startsWith('-') && !line.startsWith('---')) {
            lineClass += ' diff-removed';
            prefix = '-';
        } else if (line.startsWith('@@')) {
            lineClass += ' diff-hunk';
        } else if (line.startsWith('---') || line.startsWith('+++')) {
            lineClass += ' diff-file-header';
        }

        const escapedContent = escapeHtml(line.substring(prefix === ' ' ? 0 : 2));
        return `<div class="${lineClass}"><span class="diff-line-num">${index + 1}</span><span class="diff-prefix">${prefix}</span><span class="diff-text">${escapedContent}</span></div>`;
    }).join('');

    return `<pre class="diff-code">${html}</pre>`;
}

function renderSideBySideDiff(sideBySideDiff) {
    if (!sideBySideDiff || sideBySideDiff.length === 0) {
        return '<div class="diff-side-by-side-container">No diff content</div>';
    }

    const leftLines = [];
    const rightLines = [];

    sideBySideDiff.forEach(line => {
        const type = line.type.toLowerCase();
        const leftClass = type === 'removed' ? 'diff-removed' : type === 'unchanged' ? '' : 'diff-empty';
        const rightClass = type === 'added' ? 'diff-added' : type === 'unchanged' ? '' : 'diff-empty';

        leftLines.push(`
            <div class="diff-line ${leftClass}">
                <span class="diff-line-num">${line.leftLineNum || ''}</span>
                <span class="diff-text">${line.leftContent ? escapeHtml(line.leftContent) : ''}</span>
            </div>
        `);

        rightLines.push(`
            <div class="diff-line ${rightClass}">
                <span class="diff-line-num">${line.rightLineNum || ''}</span>
                <span class="diff-text">${line.rightContent ? escapeHtml(line.rightContent) : ''}</span>
            </div>
        `);
    });

    return `
        <div class="diff-side-by-side-container">
            <div class="diff-pane diff-left">
                <div class="diff-pane-header">Before</div>
                <pre class="diff-code">${leftLines.join('')}</pre>
            </div>
            <div class="diff-pane diff-right">
                <div class="diff-pane-header">After</div>
                <pre class="diff-code">${rightLines.join('')}</pre>
            </div>
        </div>
    `;
}

function escapeHtml(text) {
    if (!text) return '';
    return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

export function setupDiffViewerToggle(container) {
    const viewer = container.querySelector('.diff-viewer');
    if (!viewer) return;

    const buttons = viewer.querySelectorAll('[data-view]');
    const unifiedView = viewer.querySelector('.diff-unified');
    const sideBySideView = viewer.querySelector('.diff-side-by-side');

    buttons.forEach(btn => {
        btn.addEventListener('click', () => {
            const view = btn.dataset.view;

            buttons.forEach(b => b.removeAttribute('selected'));
            btn.setAttribute('selected', '');

            if (view === 'unified') {
                unifiedView?.classList.remove('hidden');
                sideBySideView?.classList.add('hidden');
            } else {
                unifiedView?.classList.add('hidden');
                sideBySideView?.classList.remove('hidden');
            }
        });
    });
}
