// Global Application State
export const state = {
    // Authentication
    accessToken: null,
    refreshToken: null,
    currentUser: null,

    // Docker
    currentHostId: null,
    hosts: [],

    // UI State
    currentPage: 'dashboard',
    sidebarOpen: false,
};

// LocalStorage Keys
const STORAGE_KEYS = {
    ACCESS_TOKEN: 'dockerManager_accessToken',
    REFRESH_TOKEN: 'dockerManager_refreshToken',
    CURRENT_USER: 'dockerManager_user',
    CURRENT_HOST_ID: 'dockerManager_hostId',
};

// Load state from localStorage
export function loadState() {
    try {
        state.accessToken = localStorage.getItem(STORAGE_KEYS.ACCESS_TOKEN);
        state.refreshToken = localStorage.getItem(STORAGE_KEYS.REFRESH_TOKEN);
        state.currentHostId = localStorage.getItem(STORAGE_KEYS.CURRENT_HOST_ID);

        const userJson = localStorage.getItem(STORAGE_KEYS.CURRENT_USER);
        if (userJson) {
            state.currentUser = JSON.parse(userJson);
        }
    } catch (error) {
        console.error('Failed to load state:', error);
        clearState();
    }
}

// Save state to localStorage
export function saveState() {
    try {
        if (state.accessToken) {
            localStorage.setItem(STORAGE_KEYS.ACCESS_TOKEN, state.accessToken);
        } else {
            localStorage.removeItem(STORAGE_KEYS.ACCESS_TOKEN);
        }

        if (state.refreshToken) {
            localStorage.setItem(STORAGE_KEYS.REFRESH_TOKEN, state.refreshToken);
        } else {
            localStorage.removeItem(STORAGE_KEYS.REFRESH_TOKEN);
        }

        if (state.currentUser) {
            localStorage.setItem(STORAGE_KEYS.CURRENT_USER, JSON.stringify(state.currentUser));
        } else {
            localStorage.removeItem(STORAGE_KEYS.CURRENT_USER);
        }

        if (state.currentHostId) {
            localStorage.setItem(STORAGE_KEYS.CURRENT_HOST_ID, state.currentHostId);
        } else {
            localStorage.removeItem(STORAGE_KEYS.CURRENT_HOST_ID);
        }
    } catch (error) {
        console.error('Failed to save state:', error);
    }
}

// Clear all state
export function clearState() {
    state.accessToken = null;
    state.refreshToken = null;
    state.currentUser = null;
    state.currentHostId = null;
    state.hosts = [];

    Object.values(STORAGE_KEYS).forEach(key => {
        localStorage.removeItem(key);
    });
}

// Set state with auto-save and event dispatch
export function setState(key, value) {
    const oldValue = state[key];
    state[key] = value;

    // Auto-save persistent keys
    if (['accessToken', 'refreshToken', 'currentUser', 'currentHostId'].includes(key)) {
        saveState();
    }

    // Dispatch custom event for reactive updates
    window.dispatchEvent(new CustomEvent('state-change', {
        detail: { key, value, oldValue }
    }));
}

// Subscribe to state changes
export function onStateChange(key, callback) {
    window.addEventListener('state-change', (event) => {
        if (event.detail.key === key) {
            callback(event.detail.value, event.detail.oldValue);
        }
    });
}

// Get auth tokens
export function getAuthTokens() {
    return {
        accessToken: state.accessToken,
        refreshToken: state.refreshToken,
    };
}

// Set auth tokens
export function setAuthTokens(accessToken, refreshToken) {
    setState('accessToken', accessToken);
    setState('refreshToken', refreshToken);
}

// Set current user
export function setCurrentUser(user) {
    setState('currentUser', user);
}

// Check if user is admin
export function isAdmin() {
    return state.currentUser?.admin === true;
}
