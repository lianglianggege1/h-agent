package com.h.backend.common.context;

public final class LocalThread {

    private static final ThreadLocal<UserContext> USER_CONTEXT = new ThreadLocal<>();

    private LocalThread() {
    }

    public static void setUser(Long userId, String userName) {
        USER_CONTEXT.set(new UserContext(userId, userName));
    }

    public static Long getUserId() {
        UserContext context = USER_CONTEXT.get();
        return context == null ? null : context.userId();
    }

    public static String getUserName() {
        UserContext context = USER_CONTEXT.get();
        return context == null ? null : context.userName();
    }

    public static UserContext getUser() {
        return USER_CONTEXT.get();
    }

    public static void clear() {
        USER_CONTEXT.remove();
    }

    public record UserContext(Long userId, String userName) {
    }
}
