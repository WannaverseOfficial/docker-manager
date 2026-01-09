package com.wannaverse.security;

public final class SecurityContextHolder {
    private static final ThreadLocal<SecurityContext> contextHolder = new ThreadLocal<>();

    private SecurityContextHolder() {}

    public static void setContext(SecurityContext context) {
        contextHolder.set(context);
    }

    public static SecurityContext getContext() {
        return contextHolder.get();
    }

    public static void clearContext() {
        contextHolder.remove();
    }

    public static boolean isAuthenticated() {
        SecurityContext ctx = contextHolder.get();
        return ctx != null && ctx.isAuthenticated();
    }

    public static String getCurrentUserId() {
        SecurityContext ctx = contextHolder.get();
        return ctx != null ? ctx.getUserId() : null;
    }

    public static String getCurrentUsername() {
        SecurityContext ctx = contextHolder.get();
        return ctx != null ? ctx.getUsername() : null;
    }
}
