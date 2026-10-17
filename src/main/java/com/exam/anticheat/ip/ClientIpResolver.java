package com.exam.anticheat.ip;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端真实 IP 提取（spec add-anti-cheat-restrictions「考试 IP 范围准入校验」需求）：
 * 部署形态是"反向代理 / 网关在前"，{@code request.getRemoteAddr()} 拿到的是代理地址。
 *
 * <p>安全加固（harden-client-ip-spoofing-and-redis-drill）：
 * 生产 Nginx 配置了不可伪造的覆写头 {@code X-Real-IP $remote_addr;}（直接取自 TCP 握手远端地址）。
 * 若反向代理为追加型 {@code proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;}，
 * 客户端可自行注入伪造的 XFF 前缀。因此优先采信不可伪造的 {@code X-Real-IP}：
 *
 * <ul>
 *   <li>{@code X-Real-IP} 存在且是合法 IPv4/IPv6 → 优先采信（阻断客户端伪造 XFF）；</li>
 *   <li>{@code X-Real-IP} 缺失或非法 → 回退至既有 {@code X-Forwarded-For} 首项提取逻辑；</li>
 *   <li>{@code X-Forwarded-For} 亦缺失或非法 → 回退至直连 {@code request.getRemoteAddr()}。</li>
 * </ul>
 *
 * <p>本类只做"取哪个字符串"，是否为白名单成员由 {@link IpMatcher} 判定；
 * 空请求/空地址返回 {@code null}，由准入方按"无法确认来源"处置。
 */
public final class ClientIpResolver {

    /** 受信任反向代理覆写的客户端直连来源（不可伪造）。 */
    private static final String X_REAL_IP = "X-Real-IP";

    /** 反向代理透传的客户端来源链（首项为最靠近客户端的一跳）。 */
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String realIp = validRealIp(request.getHeader(X_REAL_IP));
        if (realIp != null) {
            return realIp;
        }
        String forwarded = firstForwardedEntry(request.getHeader(X_FORWARDED_FOR));
        if (forwarded != null) {
            return forwarded;
        }
        String remote = request.getRemoteAddr();
        if (remote == null) {
            return null;
        }
        String trimmed = remote.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String validRealIp(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        return IpMatcher.isValidIp(trimmed) ? trimmed : null;
    }

    private static String firstForwardedEntry(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        int comma = header.indexOf(',');
        String first = (comma >= 0 ? header.substring(0, comma) : header).trim();
        return IpMatcher.isValidIp(first) ? first : null;
    }
}
