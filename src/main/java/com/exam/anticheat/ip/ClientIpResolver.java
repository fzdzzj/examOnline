package com.exam.anticheat.ip;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端真实 IP 提取（spec add-anti-cheat-restrictions「考试 IP 范围准入校验」需求）：
 * 部署形态是"反向代理 / 网关在前"，{@code request.getRemoteAddr()} 拿到的是代理地址，
 * 因此优先取 {@code X-Forwarded-For} 的<b>首项</b>（最靠近客户端的原始来源）：
 *
 * <ul>
 *   <li>首项是合法 IPv4/IPv6 → 采用（多级代理形如 {@code 203.0.113.9, 10.0.0.1}）；</li>
 *   <li>首项非法或头缺失 → 回退 {@code request.getRemoteAddr()}（本地直连/测试环境）；</li>
 *   <li>只认首项、不向后寻找合法项——XFF 是客户端可伪造的头，向后取用等于把伪造权交给请求方，
 *       宁可回退到不可伪造的 remoteAddr。</li>
 * </ul>
 *
 * <p>本类只做"取哪个字符串"，是否为白名单成员由 {@link IpMatcher} 判定；
 * 空请求/空地址返回 {@code null}，由准入方按"无法确认来源"处置。
 */
public final class ClientIpResolver {

    /** 反向代理透传的客户端来源链（首项为最靠近客户端的一跳）。 */
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
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

    private static String firstForwardedEntry(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        int comma = header.indexOf(',');
        String first = (comma >= 0 ? header.substring(0, comma) : header).trim();
        return IpMatcher.isValidIp(first) ? first : null;
    }
}
