package com.exam.anticheat.ip;

import java.util.Arrays;
import java.util.Collection;

/**
 * IP 白名单匹配器（spec add-anti-cheat-restrictions「考试 IP 范围准入校验」需求）：
 * 纯函数、无状态、无外部依赖，支持三种白名单写法：
 *
 * <ol>
 *   <li><b>单 IP</b>：{@code 192.168.1.100}（IPv4）或 {@code ::1}（IPv6，含 {@code 0:0:0:0:0:0:0:1} 等价写法）；</li>
 *   <li><b>通配符</b>：{@code 192.168.1.*}——只认整段 {@code *}（{@code 192.168.1.1*} 这类半段通配按非法处理，
 *       不误放行也不误拦截）；</li>
 *   <li><b>CIDR</b>：{@code 192.168.1.0/24}、{@code 2001:db8::/32}——按位前缀比较，支持非 8 倍数掩码与 /0。</li>
 * </ol>
 *
 * <p>工程约定（行为采集与准入同为旁路，绝不允许把脏数据变成"静默放行"或"500"）：
 * 任何空值、非法 IP、非法模式一律返回 {@code false}（不命中），不抛异常；
 * 客户端 IP 为 IPv4-mapped IPv6（{@code ::ffff:192.168.1.7}）时归一化为 IPv4 再比较，
 * 因此反向代理以映射形态透传时不会误判为"网段外"。
 *
 * <p>不命中即由调用方抛 403（见 {@code ExamTakingService#assertAdmission}）：
 * 本类只负责"是不是"，不负责"怎么处置"。
 */
public final class IpMatcher {

    /** 通配符段：整段星号（唯一被承认的通配形态）。 */
    private static final String WILDCARD_SEGMENT = "*";

    private IpMatcher() {
    }

    /** 客户端 IP 命中白名单中任意一条即放行；空列表/空客户端 IP 一律不命中。 */
    public static boolean matchesAny(String clientIp, Collection<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (matches(clientIp, pattern)) {
                return true;
            }
        }
        return false;
    }

    /** 单条白名单模式匹配（单 IP / 通配符 / CIDR 自动识别）。 */
    public static boolean matches(String clientIp, String pattern) {
        byte[] client = parse(clientIp);
        if (client == null || pattern == null) {
            return false;
        }
        String candidate = pattern.trim();
        if (candidate.isEmpty()) {
            return false;
        }

        int slash = candidate.indexOf('/');
        if (slash >= 0) {
            byte[] network = parse(candidate.substring(0, slash));
            int prefix = parsePrefix(candidate.substring(slash + 1));
            return network != null && prefix >= 0
                    && sameFamily(client, network) && inCidr(client, network, prefix);
        }
        if (candidate.contains(WILDCARD_SEGMENT)) {
            return matchesWildcard(client, candidate);
        }
        byte[] exact = parse(candidate);
        return exact != null && Arrays.equals(client, exact);
    }

    /** 是否为可解析的 IPv4/IPv6 字面量（客户端 IP 提取的有效性判据）。 */
    public static boolean isValidIp(String candidate) {
        return parse(candidate) != null;
    }

    // ==================== 解析 ====================

    /** 解析为 4 字节（IPv4）或 16 字节（IPv6）地址；IPv4-mapped IPv6 归一化为 4 字节。 */
    private static byte[] parse(String raw) {
        if (raw == null) {
            return null;
        }
        String candidate = raw.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        // 作用域后缀（fe80::1%eth0）与匹配无关，先剥离
        int zone = candidate.indexOf('%');
        if (zone > 0) {
            candidate = candidate.substring(0, zone);
        }
        if (candidate.indexOf(':') >= 0) {
            byte[] ipv6 = parseIpv6(candidate);
            if (ipv6 == null) {
                return null;
            }
            byte[] mapped = toIpv4IfMapped(ipv6);
            return mapped != null ? mapped : ipv6;
        }
        return parseIpv4(candidate);
    }

    private static byte[] parseIpv4(String candidate) {
        String[] segments = candidate.split("\\.", -1);
        if (segments.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            int value = parseOctet(segments[i]);
            if (value < 0) {
                return null;
            }
            out[i] = (byte) value;
        }
        return out;
    }

    /** 单个 IPv4 段：纯数字、最多 3 位、0..255；非法返回 -1（不抛异常）。 */
    private static int parseOctet(String segment) {
        if (segment == null || segment.isEmpty() || segment.length() > 3) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value > 255 ? -1 : value;
    }

    private static byte[] parseIpv6(String candidate) {
        int doubleColon = candidate.indexOf("::");
        String headPart = doubleColon >= 0 ? candidate.substring(0, doubleColon) : candidate;
        String tailPart = doubleColon >= 0 ? candidate.substring(doubleColon + 2) : null;
        if (tailPart != null && tailPart.contains("::")) {
            return null;   // 多个 "::" 非法
        }

        int[] head = new int[8];
        int headCount = 0;
        int[] tail = new int[8];
        int tailCount = 0;
        // 内嵌 IPv4（::ffff:192.168.1.7）只可能出现在末段
        int headRead = readGroups(headPart, head, doubleColon < 0);
        if (headRead < 0) {
            return null;
        }
        headCount = headRead;
        if (tailPart != null) {
            int tailRead = readGroups(tailPart, tail, true);
            if (tailRead < 0) {
                return null;
            }
            tailCount = tailRead;
        }

        int total = headCount + tailCount;
        if (doubleColon < 0 ? total != 8 : total > 7) {
            return null;
        }

        byte[] out = new byte[16];
        int index = 0;
        for (int i = 0; i < headCount; i++) {
            index = writeGroup(out, index, head[i]);
        }
        index += (8 - total) * 2;   // "::" 压缩的零段
        for (int i = 0; i < tailCount; i++) {
            index = writeGroup(out, index, tail[i]);
        }
        return out;
    }

    /**
     * 读取冒号分隔的 16 位组，返回组数；非法返回 -1。
     *
     * @param allowEmbeddedIpv4 末段是否允许内嵌 IPv4 形态（如 {@code ::ffff:192.168.1.7}）
     */
    private static int readGroups(String part, int[] groups, boolean allowEmbeddedIpv4) {
        if (part.isEmpty()) {
            return 0;
        }
        String[] segments = part.split(":", -1);
        int count = 0;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                return -1;   // 空段只可能来自 "::"，已在上层剥离
            }
            if (allowEmbeddedIpv4 && i == segments.length - 1 && segment.indexOf('.') >= 0) {
                byte[] ipv4 = parseIpv4(segment);
                if (ipv4 == null || count > 6) {
                    return -1;
                }
                groups[count++] = ((ipv4[0] & 0xFF) << 8) | (ipv4[1] & 0xFF);
                groups[count++] = ((ipv4[2] & 0xFF) << 8) | (ipv4[3] & 0xFF);
                continue;
            }
            if (segment.length() > 4) {
                return -1;
            }
            int value = 0;
            for (int j = 0; j < segment.length(); j++) {
                int digit = Character.digit(segment.charAt(j), 16);
                if (digit < 0) {
                    return -1;
                }
                value = value * 16 + digit;
            }
            if (count >= 8) {
                return -1;
            }
            groups[count++] = value;
        }
        return count;
    }

    private static int writeGroup(byte[] out, int index, int value) {
        out[index] = (byte) (value >> 8);
        out[index + 1] = (byte) (value & 0xFF);
        return index + 2;
    }

    /** {@code ::ffff:a.b.c.d} 形态归一化为 IPv4；非映射形态返回 null。 */
    private static byte[] toIpv4IfMapped(byte[] ipv6) {
        for (int i = 0; i < 10; i++) {
            if (ipv6[i] != 0) {
                return null;
            }
        }
        if (ipv6[10] != (byte) 0xFF || ipv6[11] != (byte) 0xFF) {
            return null;
        }
        return new byte[] {ipv6[12], ipv6[13], ipv6[14], ipv6[15]};
    }

    // ==================== 匹配 ====================

    private static boolean sameFamily(byte[] left, byte[] right) {
        return left.length == right.length;
    }

    /** 按位前缀比较：整字节先比，剩余位用掩码比（支持 /25、/26 这类非 8 倍数掩码）。 */
    private static boolean inCidr(byte[] ip, byte[] network, int prefix) {
        if (prefix > ip.length * 8) {
            return false;
        }
        int fullBytes = prefix / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (ip[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefix % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits);
        return (ip[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    /** 通配符仅支持整段星号（{@code 192.168.1.*} / {@code 10.*.*.*} / {@code *}），IPv4 专属。 */
    private static boolean matchesWildcard(byte[] client, String pattern) {
        if (client.length != 4) {
            return false;
        }
        if (WILDCARD_SEGMENT.equals(pattern)) {
            return true;   // 全通配：放行任意 IPv4（IPv6 需显式声明网段）
        }
        String[] segments = pattern.split("\\.", -1);
        if (segments.length != 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (WILDCARD_SEGMENT.equals(segments[i])) {
                continue;
            }
            int expected = parseOctet(segments[i]);
            if (expected < 0 || (client[i] & 0xFF) != expected) {
                return false;
            }
        }
        return true;
    }

    /** CIDR 前缀长度：纯数字、0..128（超出按非法处理，由 {@link #inCidr} 兜住族位数差异）。 */
    private static int parsePrefix(String raw) {
        String candidate = raw.trim();
        if (candidate.isEmpty() || candidate.length() > 3) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value <= 128 ? value : -1;
    }
}
