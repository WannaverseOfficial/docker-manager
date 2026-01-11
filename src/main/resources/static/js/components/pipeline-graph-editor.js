// Interactive Pipeline Graph Editor
// A visual, node-based editor with draggable stages and clear connections

export class PipelineGraphEditor {
    constructor(container, options = {}) {
        this.container = container;
        this.options = {
            onStageSelect: options.onStageSelect || (() => {}),
            onStepSelect: options.onStepSelect || (() => {}),
            onChange: options.onChange || (() => {}),
            readOnly: options.readOnly || false,
            ...options
        };

        this.stages = [];
        this.selectedStage = null;
        this.selectedStep = null;
        this.dragState = null;
        this.zoom = 1;
        this.pan = { x: 0, y: 0 };
        this.isPanning = false;
        this.lastMousePos = { x: 0, y: 0 };

        this.init();
    }

    init() {
        this.container.innerHTML = `
            <div class="pipeline-graph-editor">
                <div class="graph-toolbar">
                    <div class="toolbar-left">
                        <span class="toolbar-title">Pipeline Flow</span>
                        <div class="toolbar-hint">Drag stages to reposition. Double-click to add steps.</div>
                    </div>
                    <div class="toolbar-right">
                        <md-icon-button class="zoom-out-btn" title="Zoom Out">
                            <span class="material-symbols-outlined">remove</span>
                        </md-icon-button>
                        <span class="zoom-level">100%</span>
                        <md-icon-button class="zoom-in-btn" title="Zoom In">
                            <span class="material-symbols-outlined">add</span>
                        </md-icon-button>
                        <md-icon-button class="fit-btn" title="Fit to View">
                            <span class="material-symbols-outlined">fit_screen</span>
                        </md-icon-button>
                        <md-icon-button class="auto-layout-btn" title="Auto Layout">
                            <span class="material-symbols-outlined">auto_fix_high</span>
                        </md-icon-button>
                    </div>
                </div>
                <div class="graph-canvas-wrapper">
                    <svg class="graph-svg">
                        <defs>
                            <linearGradient id="connectionGradient" x1="0%" y1="0%" x2="100%" y2="0%">
                                <stop offset="0%" style="stop-color: var(--md-sys-color-primary); stop-opacity: 0.8"/>
                                <stop offset="100%" style="stop-color: var(--md-sys-color-tertiary); stop-opacity: 0.9"/>
                            </linearGradient>
                            <linearGradient id="parallelGradient" x1="0%" y1="0%" x2="100%" y2="0%">
                                <stop offset="0%" style="stop-color: var(--md-sys-color-tertiary); stop-opacity: 0.8"/>
                                <stop offset="100%" style="stop-color: var(--md-sys-color-secondary); stop-opacity: 0.9"/>
                            </linearGradient>
                            <marker id="arrowhead" markerWidth="12" markerHeight="8" refX="10" refY="4" orient="auto">
                                <path d="M 0 0 L 12 4 L 0 8 Z" fill="var(--md-sys-color-primary)" opacity="0.8"/>
                            </marker>
                            <marker id="arrowhead-parallel" markerWidth="12" markerHeight="8" refX="10" refY="4" orient="auto">
                                <path d="M 0 0 L 12 4 L 0 8 Z" fill="var(--md-sys-color-tertiary)" opacity="0.8"/>
                            </marker>
                            <filter id="glow">
                                <feGaussianBlur stdDeviation="3" result="coloredBlur"/>
                                <feMerge>
                                    <feMergeNode in="coloredBlur"/>
                                    <feMergeNode in="SourceGraphic"/>
                                </feMerge>
                            </filter>
                        </defs>
                        <g class="connections-layer"></g>
                        <g class="nodes-layer"></g>
                    </svg>
                    <div class="graph-add-stage" title="Add Stage">
                        <span class="material-symbols-outlined">add</span>
                        <span class="add-label">Add Stage</span>
                    </div>
                </div>
                <div class="stage-detail-panel hidden">
                    <div class="panel-header">
                        <span class="panel-title">Stage Details</span>
                        <md-icon-button class="close-panel-btn">
                            <span class="material-symbols-outlined">close</span>
                        </md-icon-button>
                    </div>
                    <div class="panel-content"></div>
                </div>
            </div>
        `;

        this.svg = this.container.querySelector('.graph-svg');
        this.connectionsLayer = this.svg.querySelector('.connections-layer');
        this.nodesLayer = this.svg.querySelector('.nodes-layer');
        this.detailPanel = this.container.querySelector('.stage-detail-panel');
        this.canvasWrapper = this.container.querySelector('.graph-canvas-wrapper');

        this.setupEventListeners();
        this.render();
    }

    setupEventListeners() {
        // Add stage button
        this.container.querySelector('.graph-add-stage')?.addEventListener('click', () => {
            this.addStage();
        });

        // Zoom controls
        this.container.querySelector('.zoom-in-btn')?.addEventListener('click', () => {
            this.setZoom(Math.min(2, this.zoom + 0.1));
        });

        this.container.querySelector('.zoom-out-btn')?.addEventListener('click', () => {
            this.setZoom(Math.max(0.3, this.zoom - 0.1));
        });

        this.container.querySelector('.fit-btn')?.addEventListener('click', () => {
            this.fitToView();
        });

        this.container.querySelector('.auto-layout-btn')?.addEventListener('click', () => {
            this.autoLayout();
        });

        // Close detail panel
        this.container.querySelector('.close-panel-btn')?.addEventListener('click', () => {
            this.closeDetailPanel();
        });

        // Canvas interactions
        this.canvasWrapper.addEventListener('mousedown', (e) => this.handleMouseDown(e));
        this.canvasWrapper.addEventListener('mousemove', (e) => this.handleMouseMove(e));
        this.canvasWrapper.addEventListener('mouseup', (e) => this.handleMouseUp(e));
        this.canvasWrapper.addEventListener('mouseleave', (e) => this.handleMouseUp(e));
        this.canvasWrapper.addEventListener('wheel', (e) => this.handleWheel(e));

        // Click on canvas to deselect
        this.svg.addEventListener('click', (e) => {
            if (e.target === this.svg || e.target.closest('.connections-layer')) {
                this.deselectAll();
            }
        });
    }

    handleMouseDown(e) {
        const nodeEl = e.target.closest('.graph-node');

        if (nodeEl) {
            // Start dragging a node
            const stageId = nodeEl.dataset.stageId;
            const stage = this.stages.find(s => s.id === stageId);
            if (stage) {
                this.dragState = {
                    type: 'node',
                    stageId,
                    startX: e.clientX,
                    startY: e.clientY,
                    originalX: stage.position?.x || 0,
                    originalY: stage.position?.y || 0
                };
                nodeEl.classList.add('dragging');
                e.preventDefault();
            }
        } else if (e.target === this.canvasWrapper || e.target === this.svg) {
            // Start panning
            this.isPanning = true;
            this.lastMousePos = { x: e.clientX, y: e.clientY };
            this.canvasWrapper.style.cursor = 'grabbing';
        }
    }

    handleMouseMove(e) {
        if (this.dragState?.type === 'node') {
            const dx = (e.clientX - this.dragState.startX) / this.zoom;
            const dy = (e.clientY - this.dragState.startY) / this.zoom;

            const stage = this.stages.find(s => s.id === this.dragState.stageId);
            if (stage) {
                stage.position = {
                    x: this.dragState.originalX + dx,
                    y: this.dragState.originalY + dy
                };
                this.render();
            }
        } else if (this.isPanning) {
            const dx = e.clientX - this.lastMousePos.x;
            const dy = e.clientY - this.lastMousePos.y;
            this.pan.x += dx;
            this.pan.y += dy;
            this.lastMousePos = { x: e.clientX, y: e.clientY };
            this.updateTransform();
        }
    }

    handleMouseUp(e) {
        if (this.dragState?.type === 'node') {
            const nodeEl = this.svg.querySelector(`[data-stage-id="${this.dragState.stageId}"]`);
            nodeEl?.classList.remove('dragging');
            this.options.onChange(this.getStages());
        }
        this.dragState = null;
        this.isPanning = false;
        this.canvasWrapper.style.cursor = '';
    }

    handleWheel(e) {
        if (e.ctrlKey || e.metaKey) {
            e.preventDefault();
            const delta = e.deltaY > 0 ? -0.1 : 0.1;
            this.setZoom(Math.max(0.3, Math.min(2, this.zoom + delta)));
        }
    }

    setZoom(level) {
        this.zoom = level;
        this.container.querySelector('.zoom-level').textContent = `${Math.round(this.zoom * 100)}%`;
        this.updateTransform();
    }

    updateTransform() {
        const transform = `translate(${this.pan.x}px, ${this.pan.y}px) scale(${this.zoom})`;
        this.svg.style.transform = transform;
    }

    fitToView() {
        if (this.stages.length === 0) {
            this.setZoom(1);
            this.pan = { x: 0, y: 0 };
            this.updateTransform();
            return;
        }

        // Calculate bounds
        let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
        this.stages.forEach(stage => {
            const x = stage.position?.x || 0;
            const y = stage.position?.y || 0;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + 200);
            maxY = Math.max(maxY, y + 150);
        });

        const padding = 100;
        const contentWidth = maxX - minX + padding * 2;
        const contentHeight = maxY - minY + padding * 2;

        const wrapperRect = this.canvasWrapper.getBoundingClientRect();
        const scaleX = wrapperRect.width / contentWidth;
        const scaleY = wrapperRect.height / contentHeight;
        const newZoom = Math.min(scaleX, scaleY, 1.5);

        this.setZoom(newZoom);
        this.pan = {
            x: (wrapperRect.width - contentWidth * newZoom) / 2 - minX * newZoom + padding * newZoom,
            y: (wrapperRect.height - contentHeight * newZoom) / 2 - minY * newZoom + padding * newZoom
        };
        this.updateTransform();
    }

    autoLayout() {
        const nodeWidth = 200;
        const nodeHeight = 150;
        const horizontalGap = 150; // Extra gap for parallel containers
        const verticalGap = 40;
        const startX = 100;
        const startY = 100;

        // Find parallel groups and organize by dependency layers
        const parallelGroups = this.findParallelGroups();
        const layers = this.buildDependencyLayers();

        let currentX = startX;

        for (const layer of layers) {
            // Check if this layer has parallel stages (same dependencies)
            const layerGroups = [];
            const processed = new Set();

            for (const stageId of layer) {
                if (processed.has(stageId)) continue;

                // Find if this stage is part of a parallel group
                const parallelGroup = parallelGroups.find(g => g.includes(stageId));
                if (parallelGroup && parallelGroup.length > 1) {
                    // Add all members of the parallel group that are in this layer
                    const groupInLayer = parallelGroup.filter(id => layer.includes(id));
                    layerGroups.push(groupInLayer);
                    groupInLayer.forEach(id => processed.add(id));
                } else {
                    layerGroups.push([stageId]);
                    processed.add(stageId);
                }
            }

            // Position each group in this layer
            for (const group of layerGroups) {
                if (group.length > 1) {
                    // Stack parallel stages vertically
                    let currentY = startY;
                    for (const stageId of group) {
                        const stage = this.stages.find(s => s.id === stageId);
                        if (stage) {
                            stage.position = { x: currentX, y: currentY };
                            currentY += nodeHeight + verticalGap;
                        }
                    }
                } else {
                    // Single stage - center it vertically relative to potential parallel groups
                    const stage = this.stages.find(s => s.id === group[0]);
                    if (stage) {
                        stage.position = { x: currentX, y: startY };
                    }
                }
                currentX += nodeWidth + horizontalGap;
            }
        }

        this.render();
        this.fitToView();
        this.options.onChange(this.getStages());
    }

    buildDependencyLayers() {
        // Organize stages into layers based on dependencies
        // Layer 0 = stages with no dependencies
        // Layer 1 = stages that depend only on layer 0, etc.
        const layers = [];
        const assigned = new Set();

        while (assigned.size < this.stages.length) {
            const layer = [];

            for (const stage of this.stages) {
                if (assigned.has(stage.id)) continue;

                const deps = stage.dependsOn || [];
                // Check if all dependencies are in previous layers
                const depsResolved = deps.every(depId => assigned.has(depId));

                if (depsResolved) {
                    layer.push(stage.id);
                }
            }

            if (layer.length === 0) {
                // Circular dependency or orphaned stages - add remaining
                for (const stage of this.stages) {
                    if (!assigned.has(stage.id)) {
                        layer.push(stage.id);
                    }
                }
            }

            layer.forEach(id => assigned.add(id));
            layers.push(layer);
        }

        return layers;
    }

    setStages(stages) {
        this.stages = stages.map((stage, index) => ({
            ...stage,
            id: stage.id || `stage-${index}`,
            orderIndex: stage.orderIndex ?? index,
            // Convert positionX/positionY to position object, or use existing position, or default
            position: stage.position || (stage.positionX !== undefined ? { x: stage.positionX, y: stage.positionY } : { x: 100 + index * 300, y: 150 }),
            dependsOn: stage.dependsOn || [],
            stopOnFailure: stage.stopOnFailure !== false, // Default true
            steps: (stage.steps || []).map((step, stepIndex) => ({
                ...step,
                id: step.id || `step-${index}-${stepIndex}`,
                orderIndex: step.orderIndex ?? stepIndex
            }))
        }));
        this.render();

        // Auto-layout if no positions were set
        const hasPosition = stages.length > 0 && (stages[0].position || stages[0].positionX !== undefined);
        if (stages.length > 0 && !hasPosition) {
            setTimeout(() => this.autoLayout(), 100);
        }
    }

    getStages() {
        return this.stages.map((stage, index) => ({
            id: stage.id,
            name: stage.name || `Stage ${index + 1}`,
            orderIndex: index,
            executionMode: stage.executionMode || 'SEQUENTIAL',
            positionX: Math.round(stage.position?.x || 0),
            positionY: Math.round(stage.position?.y || 0),
            dependsOn: stage.dependsOn || [],
            stopOnFailure: stage.stopOnFailure !== false,
            steps: (stage.steps || []).map((step, stepIndex) => ({
                id: step.id,
                name: step.name || `Step ${stepIndex + 1}`,
                orderIndex: stepIndex,
                stepType: step.stepType || 'SHELL',
                configuration: step.configuration || '{}',
                workingDirectory: step.workingDirectory || null,
                timeoutSeconds: step.timeoutSeconds || 3600,
                continueOnFailure: step.continueOnFailure || false,
                environmentVariables: step.environmentVariables || null,
                artifactInputPattern: step.artifactInputPattern || null,
                artifactOutputPattern: step.artifactOutputPattern || null,
                positionX: step.positionX || 0,
                positionY: step.positionY || 0
            }))
        }));
    }

    addStage(name = null) {
        const stageNum = this.stages.length + 1;
        const lastStage = this.stages[this.stages.length - 1];
        const newX = lastStage ? (lastStage.position?.x || 0) + 300 : 100;
        const newY = lastStage ? lastStage.position?.y || 150 : 150;

        const newStage = {
            id: `stage-${Date.now()}`,
            name: name || `Stage ${stageNum}`,
            executionMode: 'SEQUENTIAL',
            orderIndex: this.stages.length,
            position: { x: newX, y: newY },
            dependsOn: lastStage ? [lastStage.id] : [], // Auto-connect to previous stage
            stopOnFailure: true,
            steps: []
        };

        this.stages.push(newStage);
        this.render();
        this.selectStage(newStage.id);
        this.options.onChange(this.getStages());

        return newStage;
    }

    updateStage(stageId, updates) {
        const stage = this.stages.find(s => s.id === stageId);
        if (stage) {
            Object.assign(stage, updates);
            this.render();
            this.options.onChange(this.getStages());
        }
    }

    deleteStage(stageId) {
        this.stages = this.stages.filter(s => s.id !== stageId);
        this.deselectAll();
        this.render();
        this.options.onChange(this.getStages());
    }

    addStep(stageId, step = null) {
        const stage = this.stages.find(s => s.id === stageId);
        if (!stage) return null;

        const stepNum = stage.steps.length + 1;
        const newStep = step || {
            id: `step-${Date.now()}`,
            name: `Step ${stepNum}`,
            stepType: 'SHELL',
            configuration: '{}',
            timeoutSeconds: 3600,
            orderIndex: stage.steps.length
        };

        stage.steps.push(newStep);
        this.render();
        this.selectStage(stageId);
        this.options.onChange(this.getStages());

        return newStep;
    }

    updateStep(stageId, stepId, updates) {
        const stage = this.stages.find(s => s.id === stageId);
        if (stage) {
            const step = stage.steps.find(s => s.id === stepId);
            if (step) {
                Object.assign(step, updates);
                this.render();
                if (this.selectedStage === stageId) {
                    this.showStageDetails(stageId);
                }
                this.options.onChange(this.getStages());
            }
        }
    }

    deleteStep(stageId, stepId) {
        const stage = this.stages.find(s => s.id === stageId);
        if (stage) {
            stage.steps = stage.steps.filter(s => s.id !== stepId);
            this.render();
            if (this.selectedStage === stageId) {
                this.showStageDetails(stageId);
            }
            this.options.onChange(this.getStages());
        }
    }

    selectStage(stageId) {
        this.selectedStage = stageId;
        this.selectedStep = null;
        this.render();
        this.showStageDetails(stageId);
        this.options.onStageSelect(this.stages.find(s => s.id === stageId));
    }

    deselectAll() {
        this.selectedStage = null;
        this.selectedStep = null;
        this.render();
        this.closeDetailPanel();
    }

    showStageDetails(stageId) {
        const stage = this.stages.find(s => s.id === stageId);
        if (!stage) return;

        const panel = this.detailPanel;
        const content = panel.querySelector('.panel-content');

        // Get other stages for dependency selection
        const otherStages = this.stages.filter(s => s.id !== stageId);
        const dependsOn = stage.dependsOn || [];

        // Find parallel siblings (stages with same dependencies)
        const parallelGroups = this.findParallelGroups();
        const parallelSiblings = parallelGroups.find(g => g.includes(stageId) && g.length > 1);

        content.innerHTML = `
            <div class="stage-form">
                <div class="form-field">
                    <md-filled-text-field
                        id="stage-name-input"
                        label="Stage Name"
                        value="${stage.name}"
                        style="width: 100%;">
                    </md-filled-text-field>
                </div>

                <div class="form-field">
                    <label class="field-label">Dependencies</label>
                    <p class="field-hint">Select which stages must complete before this one starts. Stages with the same dependencies run in parallel.</p>
                    <div class="dependency-selector">
                        ${otherStages.length === 0 ? `
                            <div class="empty-deps">No other stages to depend on</div>
                        ` : otherStages.map(s => `
                            <label class="dep-option ${dependsOn.includes(s.id) ? 'selected' : ''}">
                                <md-checkbox data-dep-id="${s.id}" ${dependsOn.includes(s.id) ? 'checked' : ''}></md-checkbox>
                                <span class="dep-name">${s.name}</span>
                            </label>
                        `).join('')}
                    </div>
                    ${parallelSiblings ? `
                        <div class="parallel-info">
                            <span class="material-symbols-outlined">info</span>
                            <span>Runs in parallel with: ${parallelSiblings.filter(id => id !== stageId).map(id => this.stages.find(s => s.id === id)?.name).join(', ')}</span>
                        </div>
                    ` : ''}
                </div>

                <div class="form-field">
                    <label class="field-label">Step Execution Mode</label>
                    <div class="execution-mode-selector">
                        <button class="mode-option ${stage.executionMode !== 'PARALLEL' ? 'selected' : ''}" data-mode="SEQUENTIAL">
                            <span class="material-symbols-outlined">arrow_forward</span>
                            <span class="mode-label">Sequential</span>
                            <span class="mode-desc">Steps run one after another</span>
                        </button>
                        <button class="mode-option ${stage.executionMode === 'PARALLEL' ? 'selected' : ''}" data-mode="PARALLEL">
                            <span class="material-symbols-outlined">call_split</span>
                            <span class="mode-label">Parallel</span>
                            <span class="mode-desc">Steps run simultaneously</span>
                        </button>
                    </div>
                </div>

                <div class="form-field">
                    <label class="field-label">Failure Behavior</label>
                    <label class="failure-option">
                        <md-checkbox id="stop-on-failure" ${stage.stopOnFailure !== false ? 'checked' : ''}></md-checkbox>
                        <div class="failure-info">
                            <span class="failure-label">Stop pipeline on failure</span>
                            <span class="failure-desc">If unchecked, other parallel branches can continue</span>
                        </div>
                    </label>
                </div>
            </div>

            <div class="steps-section">
                <div class="steps-header">
                    <span class="steps-title">Steps (${stage.steps.length})</span>
                    <md-filled-tonal-button class="add-step-btn">
                        <span class="material-symbols-outlined" slot="icon">add</span>
                        Add Step
                    </md-filled-tonal-button>
                </div>
                <div class="steps-list">
                    ${stage.steps.length === 0 ? `
                        <div class="empty-steps-message">
                            <span class="material-symbols-outlined">info</span>
                            <span>No steps yet. Add a step to define what this stage does.</span>
                        </div>
                    ` : stage.steps.map((step, index) => `
                        <div class="step-item" data-step-id="${step.id}">
                            <div class="step-drag-handle">
                                <span class="material-symbols-outlined">drag_indicator</span>
                            </div>
                            <div class="step-icon ${step.stepType.toLowerCase()}">
                                <span class="material-symbols-outlined">${this.getStepIcon(step.stepType)}</span>
                            </div>
                            <div class="step-details">
                                <div class="step-name">${step.name}</div>
                                <div class="step-type">${this.getStepTypeLabel(step.stepType)}</div>
                            </div>
                            <div class="step-actions">
                                <md-icon-button class="edit-step-btn" data-step-id="${step.id}" title="Edit">
                                    <span class="material-symbols-outlined">edit</span>
                                </md-icon-button>
                                <md-icon-button class="delete-step-btn" data-step-id="${step.id}" title="Delete">
                                    <span class="material-symbols-outlined">delete</span>
                                </md-icon-button>
                            </div>
                        </div>
                    `).join('')}
                </div>
            </div>

            <div class="stage-actions">
                <md-outlined-button class="delete-stage-btn">
                    <span class="material-symbols-outlined" slot="icon">delete</span>
                    Delete Stage
                </md-outlined-button>
            </div>
        `;

        // Wire up event listeners
        content.querySelector('#stage-name-input')?.addEventListener('input', (e) => {
            this.updateStage(stageId, { name: e.target.value });
        });

        content.querySelectorAll('.mode-option').forEach(btn => {
            btn.addEventListener('click', () => {
                const mode = btn.dataset.mode;
                this.updateStage(stageId, { executionMode: mode });
                content.querySelectorAll('.mode-option').forEach(b => b.classList.remove('selected'));
                btn.classList.add('selected');
            });
        });

        content.querySelector('.add-step-btn')?.addEventListener('click', () => {
            this.openStepEditor(stageId);
        });

        content.querySelector('.delete-stage-btn')?.addEventListener('click', () => {
            if (confirm(`Delete stage "${stage.name}"?`)) {
                this.deleteStage(stageId);
            }
        });

        content.querySelectorAll('.edit-step-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const stepId = btn.dataset.stepId;
                const step = stage.steps.find(s => s.id === stepId);
                this.openStepEditor(stageId, step);
            });
        });

        content.querySelectorAll('.delete-step-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const stepId = btn.dataset.stepId;
                const step = stage.steps.find(s => s.id === stepId);
                if (confirm(`Delete step "${step?.name}"?`)) {
                    this.deleteStep(stageId, stepId);
                }
            });
        });

        // Dependency checkboxes
        content.querySelectorAll('[data-dep-id]').forEach(checkbox => {
            checkbox.addEventListener('change', (e) => {
                const depId = checkbox.dataset.depId;
                const currentDeps = [...(stage.dependsOn || [])];

                if (e.target.checked) {
                    if (!currentDeps.includes(depId)) {
                        currentDeps.push(depId);
                    }
                } else {
                    const idx = currentDeps.indexOf(depId);
                    if (idx > -1) {
                        currentDeps.splice(idx, 1);
                    }
                }

                this.updateStage(stageId, { dependsOn: currentDeps });

                // Update the visual selection state
                const label = checkbox.closest('.dep-option');
                label?.classList.toggle('selected', e.target.checked);

                // Refresh the panel to show updated parallel info
                setTimeout(() => this.showStageDetails(stageId), 100);
            });
        });

        // Stop on failure checkbox
        content.querySelector('#stop-on-failure')?.addEventListener('change', (e) => {
            this.updateStage(stageId, { stopOnFailure: e.target.checked });
        });

        panel.classList.remove('hidden');
    }

    closeDetailPanel() {
        this.detailPanel.classList.add('hidden');
    }

    openStepEditor(stageId, existingStep = null) {
        const isEdit = !!existingStep;
        const step = existingStep || {
            name: '',
            stepType: 'SHELL',
            configuration: '{}',
            timeoutSeconds: 3600
        };

        let config = {};
        try {
            config = JSON.parse(step.configuration || '{}');
        } catch (e) {
            config = {};
        }

        const overlay = document.createElement('div');
        overlay.className = 'step-editor-overlay';
        overlay.innerHTML = `
            <div class="step-editor-modal">
                <div class="step-editor-header">
                    <h3>${isEdit ? 'Edit Step' : 'Add Step'}</h3>
                    <md-icon-button class="close-btn">
                        <span class="material-symbols-outlined">close</span>
                    </md-icon-button>
                </div>
                <div class="step-editor-content">
                    <div class="step-type-selector">
                        <div class="type-option ${step.stepType === 'SHELL' ? 'selected' : ''}" data-type="SHELL">
                            <span class="material-symbols-outlined">terminal</span>
                            <span class="type-label">Shell Script</span>
                        </div>
                        <div class="type-option ${step.stepType === 'DOCKERFILE' ? 'selected' : ''}" data-type="DOCKERFILE">
                            <span class="material-symbols-outlined">deployed_code</span>
                            <span class="type-label">Dockerfile</span>
                        </div>
                        <div class="type-option ${step.stepType === 'DOCKER_COMPOSE' ? 'selected' : ''}" data-type="DOCKER_COMPOSE">
                            <span class="material-symbols-outlined">stacks</span>
                            <span class="type-label">Compose</span>
                        </div>
                        <div class="type-option ${step.stepType === 'CUSTOM_IMAGE' ? 'selected' : ''}" data-type="CUSTOM_IMAGE">
                            <span class="material-symbols-outlined">deployed_code_account</span>
                            <span class="type-label">Custom Image</span>
                        </div>
                    </div>

                    <div class="form-field">
                        <md-filled-text-field id="step-name" label="Step Name *" value="${step.name}" style="width: 100%;"></md-filled-text-field>
                    </div>

                    <div class="form-field config-field" data-for="SHELL">
                        <md-filled-text-field id="step-script" label="Shell Script" type="textarea" rows="6"
                            value="${config.script || ''}" style="width: 100%;"></md-filled-text-field>
                    </div>

                    <div class="form-field config-field hidden" data-for="DOCKERFILE">
                        <md-filled-text-field id="step-dockerfile" label="Dockerfile Path"
                            value="${config.dockerfilePath || 'Dockerfile'}" style="width: 100%;"></md-filled-text-field>
                        <md-filled-text-field id="step-image-name" label="Image Name"
                            value="${config.imageName || ''}" style="width: 100%; margin-top: 16px;"></md-filled-text-field>
                    </div>

                    <div class="form-field config-field hidden" data-for="DOCKER_COMPOSE">
                        <md-filled-text-field id="step-compose-file" label="Compose File"
                            value="${config.composeFile || 'docker-compose.yml'}" style="width: 100%;"></md-filled-text-field>
                        <md-filled-text-field id="step-compose-cmd" label="Command (up, down, etc.)"
                            value="${config.command || 'up -d'}" style="width: 100%; margin-top: 16px;"></md-filled-text-field>
                    </div>

                    <div class="form-field config-field hidden" data-for="CUSTOM_IMAGE">
                        <md-filled-text-field id="step-custom-image" label="Docker Image"
                            value="${config.image || ''}" style="width: 100%;"></md-filled-text-field>
                        <md-filled-text-field id="step-custom-cmd" label="Command"
                            value="${config.command || ''}" style="width: 100%; margin-top: 16px;"></md-filled-text-field>
                    </div>

                    <div class="form-row">
                        <div class="form-field">
                            <md-filled-text-field id="step-timeout" label="Timeout (seconds)" type="number"
                                value="${step.timeoutSeconds || 3600}" style="width: 100%;"></md-filled-text-field>
                        </div>
                    </div>

                    <div class="form-field">
                        <label class="checkbox-label">
                            <md-checkbox id="step-continue-on-failure" ${step.continueOnFailure ? 'checked' : ''}></md-checkbox>
                            <span>Continue on failure</span>
                        </label>
                    </div>
                </div>
                <div class="step-editor-actions">
                    <md-text-button class="cancel-btn">Cancel</md-text-button>
                    <md-filled-button class="save-btn">${isEdit ? 'Save Changes' : 'Add Step'}</md-filled-button>
                </div>
            </div>
        `;

        document.body.appendChild(overlay);
        requestAnimationFrame(() => overlay.classList.add('visible'));

        let selectedType = step.stepType;

        const showConfigFields = (type) => {
            overlay.querySelectorAll('.config-field').forEach(field => {
                field.classList.toggle('hidden', field.dataset.for !== type);
            });
        };
        showConfigFields(selectedType);

        overlay.querySelectorAll('.type-option').forEach(option => {
            option.addEventListener('click', () => {
                overlay.querySelectorAll('.type-option').forEach(o => o.classList.remove('selected'));
                option.classList.add('selected');
                selectedType = option.dataset.type;
                showConfigFields(selectedType);
            });
        });

        const close = () => {
            overlay.classList.remove('visible');
            setTimeout(() => overlay.remove(), 200);
        };

        overlay.querySelector('.close-btn').addEventListener('click', close);
        overlay.querySelector('.cancel-btn').addEventListener('click', close);
        overlay.addEventListener('click', (e) => {
            if (e.target === overlay) close();
        });

        overlay.querySelector('.save-btn').addEventListener('click', () => {
            const name = overlay.querySelector('#step-name').value;
            if (!name) {
                alert('Step name is required');
                return;
            }

            let configuration = {};
            switch (selectedType) {
                case 'SHELL':
                    configuration = { script: overlay.querySelector('#step-script').value };
                    break;
                case 'DOCKERFILE':
                    configuration = {
                        dockerfilePath: overlay.querySelector('#step-dockerfile').value,
                        imageName: overlay.querySelector('#step-image-name').value
                    };
                    break;
                case 'DOCKER_COMPOSE':
                    configuration = {
                        composeFile: overlay.querySelector('#step-compose-file').value,
                        command: overlay.querySelector('#step-compose-cmd').value
                    };
                    break;
                case 'CUSTOM_IMAGE':
                    configuration = {
                        image: overlay.querySelector('#step-custom-image').value,
                        command: overlay.querySelector('#step-custom-cmd').value
                    };
                    break;
            }

            const stepData = {
                id: existingStep?.id || `step-${Date.now()}`,
                name,
                stepType: selectedType,
                configuration: JSON.stringify(configuration),
                timeoutSeconds: parseInt(overlay.querySelector('#step-timeout').value) || 3600,
                continueOnFailure: overlay.querySelector('#step-continue-on-failure').checked
            };

            if (isEdit) {
                this.updateStep(stageId, existingStep.id, stepData);
            } else {
                this.addStep(stageId, stepData);
            }

            close();
        });
    }

    getStepIcon(stepType) {
        const icons = {
            'SHELL': 'terminal',
            'DOCKERFILE': 'deployed_code',
            'DOCKER_COMPOSE': 'stacks',
            'CUSTOM_IMAGE': 'deployed_code_account'
        };
        return icons[stepType] || 'code';
    }

    getStepTypeLabel(stepType) {
        const labels = {
            'SHELL': 'Shell Script',
            'DOCKERFILE': 'Dockerfile Build',
            'DOCKER_COMPOSE': 'Docker Compose',
            'CUSTOM_IMAGE': 'Custom Image'
        };
        return labels[stepType] || stepType;
    }

    render() {
        this.renderConnections();
        this.renderNodes();
    }

    renderConnections() {
        let paths = '';

        // Build a map for quick lookup
        const stageById = {};
        this.stages.forEach(s => stageById[s.id] = s);

        // Find parallel groups
        const parallelGroups = this.findParallelGroups();

        // Calculate bounds for each parallel group and draw containers
        const groupBounds = new Map();
        for (const group of parallelGroups) {
            if (group.length <= 1) continue;

            let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
            for (const stageId of group) {
                const stage = stageById[stageId];
                if (!stage) continue;
                const x = stage.position?.x || 0;
                const y = stage.position?.y || 0;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x + 200);
                maxY = Math.max(maxY, y + 150);
            }

            const padding = 20;
            const bounds = {
                x: minX - padding,
                y: minY - padding,
                width: maxX - minX + padding * 2,
                height: maxY - minY + padding * 2,
                centerY: (minY + maxY) / 2
            };
            groupBounds.set(group, bounds);

            // Draw parallel container
            paths += `
                <g class="parallel-container">
                    <rect class="parallel-container-bg"
                          x="${bounds.x}" y="${bounds.y}"
                          width="${bounds.width}" height="${bounds.height}"
                          rx="16"/>
                    <text class="parallel-container-label"
                          x="${bounds.x + bounds.width / 2}"
                          y="${bounds.y - 8}">
                        PARALLEL
                    </text>
                </g>
            `;
        }

        // Draw connections - simplified for parallel groups
        const drawnConnections = new Set();

        for (const stage of this.stages) {
            if (!stage.dependsOn || stage.dependsOn.length === 0) continue;

            // Check if this stage is in a parallel group
            const stageGroup = parallelGroups.find(g => g.includes(stage.id) && g.length > 1);

            for (const depId of stage.dependsOn) {
                const depStage = stageById[depId];
                if (!depStage) continue;

                // Check if dependency is in a parallel group
                const depGroup = parallelGroups.find(g => g.includes(depId) && g.length > 1);

                // Create a unique key to avoid duplicate connections between groups
                const connectionKey = stageGroup && depGroup
                    ? `group:${[...depGroup].sort().join(',')}->${[...stageGroup].sort().join(',')}`
                    : stageGroup
                        ? `${depId}->group:${[...stageGroup].sort().join(',')}`
                        : depGroup
                            ? `group:${[...depGroup].sort().join(',')}->${stage.id}`
                            : `${depId}->${stage.id}`;

                if (drawnConnections.has(connectionKey)) continue;
                drawnConnections.add(connectionKey);

                // Calculate connection points
                let x1, y1, x2, y2;

                if (depGroup && groupBounds.has(depGroup)) {
                    // Connect from the right edge of the parallel container
                    const bounds = groupBounds.get(depGroup);
                    x1 = bounds.x + bounds.width;
                    y1 = bounds.centerY;
                } else {
                    x1 = (depStage.position?.x || 0) + 200;
                    y1 = (depStage.position?.y || 0) + 75;
                }

                if (stageGroup && groupBounds.has(stageGroup)) {
                    // Connect to the left edge of the parallel container
                    const bounds = groupBounds.get(stageGroup);
                    x2 = bounds.x;
                    y2 = bounds.centerY;
                } else {
                    x2 = stage.position?.x || 0;
                    y2 = (stage.position?.y || 0) + 75;
                }

                const isParallelConnection = stageGroup || depGroup;
                const gradient = isParallelConnection ? 'url(#parallelGradient)' : 'url(#connectionGradient)';
                const marker = isParallelConnection ? 'url(#arrowhead-parallel)' : 'url(#arrowhead)';
                const strokeWidth = 3;

                // Calculate bezier curve
                const dx = x2 - x1;
                const controlOffset = Math.max(Math.abs(dx) / 3, 50);

                let path;
                if (dx > 0) {
                    path = `M ${x1} ${y1} C ${x1 + controlOffset} ${y1}, ${x2 - controlOffset} ${y2}, ${x2} ${y2}`;
                } else {
                    const loopHeight = 60;
                    path = `M ${x1} ${y1} C ${x1 + controlOffset} ${y1 - loopHeight}, ${x2 - controlOffset} ${y2 - loopHeight}, ${x2} ${y2}`;
                }

                paths += `
                    <path class="connection-path ${isParallelConnection ? 'parallel-branch' : ''}"
                          d="${path}"
                          stroke="${gradient}"
                          stroke-width="${strokeWidth}"
                          fill="none"
                          marker-end="${marker}"/>
                `;
            }
        }

        // Render connector circles for stages
        if (!this.options.readOnly) {
            for (const stage of this.stages) {
                const x = stage.position?.x || 0;
                const y = (stage.position?.y || 0) + 75;

                paths += `
                    <circle class="connection-dropzone input"
                            cx="${x}" cy="${y}" r="12"
                            data-stage-id="${stage.id}" data-side="input"
                            fill="transparent" stroke="transparent"/>
                    <circle class="connection-dropzone output"
                            cx="${x + 200}" cy="${y}" r="12"
                            data-stage-id="${stage.id}" data-side="output"
                            fill="transparent" stroke="transparent"/>
                `;
            }
        }

        this.connectionsLayer.innerHTML = paths;
    }

    findParallelGroups() {
        // Group stages by their dependencies - stages with identical deps can run in parallel
        const groups = [];
        const depsKey = stage => (stage.dependsOn || []).sort().join(',');

        const byDeps = {};
        for (const stage of this.stages) {
            const key = depsKey(stage);
            if (!byDeps[key]) byDeps[key] = [];
            byDeps[key].push(stage.id);
        }

        for (const key in byDeps) {
            if (byDeps[key].length > 1) {
                groups.push(byDeps[key]);
            }
        }

        return groups;
    }

    renderNodes() {
        const nodes = this.stages.map((stage, index) => {
            const isSelected = this.selectedStage === stage.id;
            const isParallel = stage.executionMode === 'PARALLEL';
            const x = stage.position?.x || 0;
            const y = stage.position?.y || 0;
            const stepCount = stage.steps.length;

            // Create step icons preview
            const stepsPreview = stage.steps.slice(0, 4).map(s =>
                `<div class="mini-step" title="${s.name}">
                    <span class="material-symbols-outlined">${this.getStepIcon(s.stepType)}</span>
                </div>`
            ).join('');
            const moreSteps = stepCount > 4 ? `<div class="mini-step more">+${stepCount - 4}</div>` : '';

            return `
                <g class="graph-node ${isSelected ? 'selected' : ''} ${isParallel ? 'parallel' : ''}"
                   data-stage-id="${stage.id}"
                   transform="translate(${x}, ${y})">

                    <!-- Node background with gradient -->
                    <rect class="node-bg" x="0" y="0" width="200" height="150" rx="12"/>

                    <!-- Header -->
                    <rect class="node-header" x="0" y="0" width="200" height="44" rx="12"/>
                    <rect class="node-header-bottom" x="0" y="32" width="200" height="12"/>

                    <!-- Stage number badge -->
                    <circle class="stage-badge" cx="24" cy="22" r="14"/>
                    <text class="stage-number" x="24" y="27" text-anchor="middle">${index + 1}</text>

                    <!-- Execution mode badge -->
                    <rect class="mode-badge ${isParallel ? 'parallel' : ''}"
                          x="${isParallel ? 110 : 120}" y="10"
                          width="${isParallel ? 80 : 70}" height="24" rx="12"/>
                    <text class="mode-text" x="${isParallel ? 150 : 155}" y="27" text-anchor="middle">
                        ${isParallel ? 'PARALLEL' : 'SEQUENTIAL'}
                    </text>

                    <!-- Stage name -->
                    <text class="node-title" x="16" y="70">${this.truncateText(stage.name, 22)}</text>

                    <!-- Steps preview -->
                    <foreignObject x="16" y="80" width="168" height="40">
                        <div xmlns="http://www.w3.org/1999/xhtml" class="steps-preview">
                            ${stepCount === 0 ?
                                '<span class="no-steps">Click to add steps</span>' :
                                `${stepsPreview}${moreSteps}`
                            }
                        </div>
                    </foreignObject>

                    <!-- Step count -->
                    <text class="step-count-text" x="16" y="140">${stepCount} step${stepCount !== 1 ? 's' : ''}</text>

                    <!-- Connection points -->
                    <circle class="connector input" cx="0" cy="75" r="8"/>
                    <circle class="connector output" cx="200" cy="75" r="8"/>

                    ${isParallel ? `
                        <!-- Parallel indicator icon -->
                        <g class="parallel-icon" transform="translate(170, 125)">
                            <path d="M 0 0 L 8 8 M 0 8 L 8 0 M 12 0 L 20 8 M 12 8 L 20 0"
                                  stroke="var(--md-sys-color-tertiary)" stroke-width="2" fill="none"/>
                        </g>
                    ` : ''}
                </g>
            `;
        }).join('');

        this.nodesLayer.innerHTML = nodes;

        // Wire up node click handlers
        this.nodesLayer.querySelectorAll('.graph-node').forEach(node => {
            node.addEventListener('click', (e) => {
                e.stopPropagation();
                const stageId = node.dataset.stageId;
                this.selectStage(stageId);
            });

            node.addEventListener('dblclick', (e) => {
                e.stopPropagation();
                const stageId = node.dataset.stageId;
                this.openStepEditor(stageId);
            });
        });
    }

    truncateText(text, maxLength) {
        if (text.length <= maxLength) return text;
        return text.substring(0, maxLength - 1) + '...';
    }
}

export default PipelineGraphEditor;
