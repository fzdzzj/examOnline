package com.exam.anticheat.ip;

import com.exam.anticheat.config.AntiCheatConfigs;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IP 准入匹配与配置解析单元测试（spec add-anti-cheat-restrictions
 * 「考试 IP 范围准入校验」需求）——纯内存测试，不启动 Spring：
 * <ul>
 *   <li>{@link IpMatcher}：单 IP / 通配符 / CIDR 三种白名单形态，IPv4 与 IPv6、脏输入不抛异常；</li>
 *   <li>{@link ClientIpResolver}：X-Forwarded-For 首项优先、无效或缺失回落 remoteAddr；</li>
 *   <li>{@link AntiCheatConfigs}：anti_cheat_config JSON 解析与 ipWhitelist 提取（脏数据不阻断）。</li>
 * </ul>
 */
class IpMatcherTest {

    // ==================== 单 IP 精确匹配 ====================

    @Test
    @DisplayName("单 IP：完全相同才命中，相邻 IP 不命中")
    void exactIp() {
        assertTrue(IpMatcher.matches("192.168.1.100", "192.168.1.100"));
        assertFalse(IpMatcher.matches("192.168.1.101", "192.168.1.100"));
        // 前后空白（配置里手写常见）不影响判定
        assertTrue(IpMatcher.matches(" 192.168.1.100 ", " 192.168.1.100 "));
    }

    @Test
    @DisplayName("单 IP：IPv6 回环的两种等价写法互认，且与 IPv4 回环不混同")
    void exactIpv6Loopback() {
        assertTrue(IpMatcher.matches("0:0:0:0:0:0:0:1", "0:0:0:0:0:0:0:1"));
        assertTrue(IpMatcher.matches("::1", "0:0:0:0:0:0:0:1"));
        assertTrue(IpMatcher.matches("0:0:0:0:0:0:0:1", "::1"));
        assertFalse(IpMatcher.matches("127.0.0.1", "::1"));
        assertFalse(IpMatcher.matches("::1", "127.0.0.1"));
    }

    @Test
    @DisplayName("IPv4-mapped IPv6 归一化后按同一地址比较")
    void ipv4MappedIpv6Normalized() {
        assertTrue(IpMatcher.matches("::ffff:192.168.1.7", "192.168.1.7"));
        assertTrue(IpMatcher.matches("192.168.1.7", "::ffff:192.168.1.7"));
    }

    // ==================== 通配符 ====================

    @Test
    @DisplayName("通配符：整段 * 匹配同段任意主机，跨段不匹配")
    void wildcard() {
        assertTrue(IpMatcher.matches("192.168.1.100", "192.168.1.*"));
        assertTrue(IpMatcher.matches("192.168.1.255", "192.168.1.*"));
        assertFalse(IpMatcher.matches("192.168.2.100", "192.168.1.*"));
        assertFalse(IpMatcher.matches("192.168.10.100", "192.168.1.*"));

        assertTrue(IpMatcher.matches("10.3.4.5", "10.*.*.*"));
        assertFalse(IpMatcher.matches("11.3.4.5", "10.*.*.*"));

        // 全通配：仅匹配 IPv4 客户端（IPv6 不因 "*" 被误放行）
        assertTrue(IpMatcher.matches("8.8.8.8", "*"));
        assertFalse(IpMatcher.matches("2001:db8::1", "*"));
    }

    @Test
    @DisplayName("通配符：非整段形态（如 192.168.1.1*）不当作通配符，不误放行")
    void wildcardOnlyFullOctet() {
        assertFalse(IpMatcher.matches("192.168.1.15", "192.168.1.1*"));
        assertFalse(IpMatcher.matches("192.168.1.15", "192.168.*.5"));
    }

    // ==================== CIDR ====================

    @Test
    @DisplayName("CIDR：/24 覆盖整段边界，隔壁网段不命中")
    void cidr24() {
        assertTrue(IpMatcher.matches("192.168.1.0", "192.168.1.0/24"));
        assertTrue(IpMatcher.matches("192.168.1.255", "192.168.1.0/24"));
        assertTrue(IpMatcher.matches("192.168.1.77", "192.168.1.0/24"));
        assertFalse(IpMatcher.matches("192.168.2.1", "192.168.1.0/24"));
    }

    @Test
    @DisplayName("CIDR：非 8 倍数掩码按位比较（/25 与 /26 边界）")
    void cidrPartialMask() {
        assertTrue(IpMatcher.matches("192.168.1.127", "192.168.1.0/25"));
        assertFalse(IpMatcher.matches("192.168.1.128", "192.168.1.0/25"));

        assertTrue(IpMatcher.matches("10.10.0.63", "10.10.0.0/26"));
        assertFalse(IpMatcher.matches("10.10.0.64", "10.10.0.0/26"));
    }

    @Test
    @DisplayName("CIDR：/0 覆盖全部 IPv4；越界前缀（/33）判为非法不命中")
    void cidrPrefixEdges() {
        assertTrue(IpMatcher.matches("203.0.113.9", "0.0.0.0/0"));
        assertFalse(IpMatcher.matches("2001:db8::1", "0.0.0.0/0"));
        assertFalse(IpMatcher.matches("192.168.1.1", "192.168.1.0/33"));
    }

    @Test
    @DisplayName("CIDR：IPv6 网段按 128 位前缀匹配")
    void cidrIpv6() {
        assertTrue(IpMatcher.matches("2001:db8:1::5", "2001:db8::/32"));
        assertFalse(IpMatcher.matches("2001:db9::1", "2001:db8::/32"));
        assertTrue(IpMatcher.matches("::1", "::1/128"));
        assertFalse(IpMatcher.matches("::2", "::1/128"));
    }

    // ==================== 脏输入与列表判定 ====================

    @Test
    @DisplayName("脏输入：空客户端 IP / 非法模式一律不命中且不抛异常")
    void dirtyInputsNeverThrow() {
        assertFalse(IpMatcher.matches(null, "192.168.1.*"));
        assertFalse(IpMatcher.matches("", "192.168.1.*"));
        assertFalse(IpMatcher.matches("   ", "192.168.1.*"));
        assertFalse(IpMatcher.matches("not-an-ip", "192.168.1.*"));
        assertFalse(IpMatcher.matches("192.168.1.1", null));
        assertFalse(IpMatcher.matches("192.168.1.1", ""));
        assertFalse(IpMatcher.matches("192.168.1.1", "abc"));
        assertFalse(IpMatcher.matches("192.168.1.1", "192.168.1"));
        assertFalse(IpMatcher.matches("192.168.1.1", "192.168.1.1/abc"));
        assertFalse(IpMatcher.matches("192.168.999.1", "192.168.999.1"));
        assertTrue(IpMatcher.isValidIp("192.168.1.1"));
        assertTrue(IpMatcher.isValidIp("::1"));
        assertFalse(IpMatcher.isValidIp("not-an-ip"));
        assertFalse(IpMatcher.isValidIp(null));
    }

    @Test
    @DisplayName("白名单列表：命中任一即放行；空列表不视为命中")
    void matchesAnyList() {
        List<String> whitelist = List.of("192.168.1.*", "10.0.0.0/8", "203.0.113.7");
        assertTrue(IpMatcher.matchesAny("192.168.1.9", whitelist));
        assertTrue(IpMatcher.matchesAny("10.9.9.9", whitelist));
        assertTrue(IpMatcher.matchesAny("203.0.113.7", whitelist));
        assertFalse(IpMatcher.matchesAny("203.0.113.8", whitelist));
        assertFalse(IpMatcher.matchesAny("192.168.1.9", List.of()));
        assertFalse(IpMatcher.matchesAny("192.168.1.9", null));
        // 列表里的空白项被忽略，不影响其他项判定
        assertTrue(IpMatcher.matchesAny("203.0.113.7", List.of("  ", "203.0.113.7")));
    }

    // ==================== 客户端真实 IP 提取 ====================

    @Test
    @DisplayName("客户端 IP：X-Forwarded-For 首项优先（多级代理取最左）")
    void clientIpPrefersForwardedFirstEntry() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", " 203.0.113.9 , 10.0.0.1");
        assertEquals("203.0.113.9", ClientIpResolver.resolve(request));
    }

    @Test
    @DisplayName("客户端 IP：XFF 首项非法或缺失时回落 remoteAddr")
    void clientIpFallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.5.5");
        assertEquals("192.168.5.5", ClientIpResolver.resolve(request));

        request.addHeader("X-Forwarded-For", "unknown, 203.0.113.9");
        assertEquals("192.168.5.5", ClientIpResolver.resolve(request));

        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "   ");
        assertEquals("192.168.5.5", ClientIpResolver.resolve(request));
    }

    @Test
    @DisplayName("客户端 IP：XFF 首项为 IPv6 时原样返回；请求缺失返回 null")
    void clientIpIpv6AndMissingRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "2001:db8::5");
        assertEquals("2001:db8::5", ClientIpResolver.resolve(request));
        assertNull(ClientIpResolver.resolve(null));
    }

    // ==================== 防作弊配置解析 ====================

    @Test
    @DisplayName("配置解析：空白/非法 JSON 返回 null，不抛异常")
    void parseConfigDirty() {
        assertNull(AntiCheatConfigs.parse(null));
        assertNull(AntiCheatConfigs.parse(""));
        assertNull(AntiCheatConfigs.parse("   "));
        assertNull(AntiCheatConfigs.parse("{不是 JSON"));
        JsonNode node = AntiCheatConfigs.parse("{\"forbidCopy\":true}");
        assertNotNull(node);
        assertTrue(node.get("forbidCopy").asBoolean());
    }

    @Test
    @DisplayName("ipWhitelist 提取：缺失/非数组/空数组一律视为未配置（全网放行）")
    void ipWhitelistAbsent() {
        assertTrue(AntiCheatConfigs.ipWhitelist(null).isEmpty());
        assertTrue(AntiCheatConfigs.ipWhitelist(AntiCheatConfigs.parse("{}")).isEmpty());
        assertTrue(AntiCheatConfigs.ipWhitelist(
                AntiCheatConfigs.parse("{\"ipWhitelist\":[]}")).isEmpty());
        assertTrue(AntiCheatConfigs.ipWhitelist(
                AntiCheatConfigs.parse("{\"ipWhitelist\":[]}")).isEmpty());
        assertTrue(AntiCheatConfigs.ipWhitelist(
                AntiCheatConfigs.parse("{\"ipWhitelist\":\"192.168.1.*\"}")).isEmpty(),
                "非数组形态视为未配置，不因脏配置把所有人挡在门外");
    }

    @Test
    @DisplayName("ipWhitelist 提取：去空白、去重、丢弃空白项，保持声明顺序")
    void ipWhitelistNormalized() {
        List<String> whitelist = AntiCheatConfigs.ipWhitelist(AntiCheatConfigs.parse(
                "{\"ipWhitelist\":[\" 192.168.1.* \",\"10.0.0.0/8\",\"192.168.1.*\",\"\",\"  \"]}"));
        assertEquals(List.of("192.168.1.*", "10.0.0.0/8"), whitelist);
    }
}
