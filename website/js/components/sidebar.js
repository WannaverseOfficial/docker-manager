// Sidebar Component
import { state, isAdmin } from '../state.js';
import { navigateTo } from '../router.js';

// Initialize sidebar
export function initSidebar() {
    setupNavigation();
    updateAdminItems();
    setupMobileMenu();
}

// Setup navigation click handlers
function setupNavigation() {
    document.querySelectorAll('.nav-item').forEach(item => {
        item.addEventListener('click', () => {
            const page = item.dataset.page;
            if (page) {
                navigateTo(page);
                closeSidebar();
            }
        });
    });
}

// Update admin-only menu items visibility
export function updateAdminItems() {
    const adminItems = document.querySelectorAll('.admin-only');
    const show = isAdmin();

    adminItems.forEach(item => {
        if (show) {
            item.classList.remove('hidden');
        } else {
            item.classList.add('hidden');
        }
    });
}

// Setup mobile menu toggle
function setupMobileMenu() {
    const menuToggle = document.getElementById('menu-toggle');
    const sidebar = document.getElementById('sidebar');

    if (menuToggle) {
        menuToggle.addEventListener('click', () => {
            sidebar.classList.toggle('open');
        });
    }

    // Close sidebar when clicking outside on mobile
    document.addEventListener('click', (e) => {
        if (window.innerWidth <= 768) {
            const sidebar = document.getElementById('sidebar');
            const menuToggle = document.getElementById('menu-toggle');

            if (!sidebar.contains(e.target) && !menuToggle.contains(e.target)) {
                sidebar.classList.remove('open');
            }
        }
    });
}

// Open sidebar (mobile)
export function openSidebar() {
    const sidebar = document.getElementById('sidebar');
    sidebar.classList.add('open');
}

// Close sidebar (mobile)
export function closeSidebar() {
    const sidebar = document.getElementById('sidebar');
    sidebar.classList.remove('open');
}

// Set active navigation item
export function setActiveNavItem(page) {
    document.querySelectorAll('.nav-item').forEach(item => {
        if (item.dataset.page === page) {
            item.classList.add('active');
        } else {
            item.classList.remove('active');
        }
    });
}
