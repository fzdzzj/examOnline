package com.exam.auth.security;

/**
 * 当前登录用户 ThreadLocal 上下文：AuthenticationInterceptor 写入、请求结束清除；
 * AOP 切面与 Service 层通过 {@link #getCurrentUser()} 取用。
 */
public final class SecurityUtil {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private SecurityUtil() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser getCurrentUser() {
        return HOLDER.get();
    }

    /** 当前用户 ID（未登录返回 null，调用方自行判空）。 */
    public static Long getUserId() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.getId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
