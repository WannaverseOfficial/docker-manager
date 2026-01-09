// Image Policies API Module
import { apiCall } from './client.js';

const IMAGE_POLICIES_API = 'http://localhost:8080/api/image-policies';

// Get all policies
export async function listPolicies() {
    return apiCall(IMAGE_POLICIES_API);
}

// Get a specific policy with rules
export async function getPolicy(id) {
    return apiCall(`${IMAGE_POLICIES_API}/${id}`);
}

// Create a new policy
export async function createPolicy(policy) {
    return apiCall(IMAGE_POLICIES_API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(policy),
    });
}

// Update a policy
export async function updatePolicy(id, policy) {
    return apiCall(`${IMAGE_POLICIES_API}/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(policy),
    });
}

// Delete a policy
export async function deletePolicy(id) {
    return apiCall(`${IMAGE_POLICIES_API}/${id}`, {
        method: 'DELETE',
    });
}

// Add a rule to a policy
export async function addRule(policyId, rule) {
    return apiCall(`${IMAGE_POLICIES_API}/${policyId}/rules`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(rule),
    });
}

// Remove a rule from a policy
export async function removeRule(policyId, ruleId) {
    return apiCall(`${IMAGE_POLICIES_API}/${policyId}/rules/${ruleId}`, {
        method: 'DELETE',
    });
}

// Validate an image against policies
export async function validateImage(imageName) {
    return apiCall(`${IMAGE_POLICIES_API}/validate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ image: imageName }),
    });
}

// Quick check if image is allowed
export async function checkImage(imageName) {
    return apiCall(`${IMAGE_POLICIES_API}/check?image=${encodeURIComponent(imageName)}`);
}
