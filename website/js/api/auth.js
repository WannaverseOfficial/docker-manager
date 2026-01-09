// Authentication API Module
import { authApi } from './client.js';
import { setAuthTokens, setCurrentUser, clearState, state } from '../state.js';

// Login
export async function login(username, password) {
    const response = await authApi('/login', {
        method: 'POST',
        body: JSON.stringify({ username, password }),
    });

    // Store tokens
    setAuthTokens(response.accessToken, response.refreshToken);

    // Build user object from flat response
    const user = {
        id: response.userId,
        username: response.username,
        admin: response.admin,
        mustChangePassword: response.mustChangePassword,
    };
    setCurrentUser(user);

    // Return with user object for convenience
    return { ...response, user };
}

// Logout
export async function logout() {
    try {
        const refreshToken = state?.refreshToken;
        if (refreshToken) {
            await authApi('/logout', {
                method: 'POST',
                body: JSON.stringify({ refreshToken }),
            });
        }
    } catch (error) {
        console.error('Logout error:', error);
    } finally {
        clearState();
    }
}

// Logout from all devices
export async function logoutAll() {
    try {
        await authApi('/logout-all', {
            method: 'POST',
        });
    } catch (error) {
        console.error('Logout all error:', error);
    } finally {
        clearState();
    }
}

// Change password
export async function changePassword(currentPassword, newPassword) {
    const response = await authApi('/change-password', {
        method: 'POST',
        body: JSON.stringify({ currentPassword, newPassword }),
    });

    // Update tokens if returned
    if (response?.accessToken) {
        setAuthTokens(response.accessToken, response.refreshToken);
    }

    // Update user to reflect password change
    const currentUser = state?.currentUser;
    if (currentUser) {
        setCurrentUser({
            ...currentUser,
            mustChangePassword: false,
        });
    }

    return response;
}

// Get current user info
export async function getCurrentUser() {
    const user = await authApi('/me');
    setCurrentUser(user);
    return user;
}

// Refresh access token
export async function refreshToken() {
    const currentRefreshToken = state?.refreshToken;
    if (!currentRefreshToken) {
        throw new Error('No refresh token available');
    }

    const response = await authApi('/refresh', {
        method: 'POST',
        body: JSON.stringify({ refreshToken: currentRefreshToken }),
    });

    setAuthTokens(response.accessToken, response.refreshToken);
    return response;
}
