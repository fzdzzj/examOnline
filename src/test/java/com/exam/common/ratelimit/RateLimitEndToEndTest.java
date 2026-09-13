package com.exam.common.ratelimit;

import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流端到端测试（add-slow-sql-and-rate-limit task4）：走完整链路
 * MockMvc → 鉴权拦截器 → 限流拦截器 → RedisTokenBucket(Lua) → 全局异常处理器，
 * 验证核心接口（抽题）超限时真实返回 429（code=1008）。
 *
 * <p>做法：注册登录拿合法 Token 通过鉴权；用与拦截器相同的 key（exam:ratelimit:random-draw）与
 * 同参数 (capacity=200, qps=50) 把抽题桶排空到拒绝状态；再发起一次真实请求——它在限流拦截器处被拒、返回 429，
 * 业务逻辑不执行。排空后把桶时间戳（ts）人为推到未来 2s：使随后的请求 elapsed 为负、不触发"按时间补令牌"，
 * 从而消除测试内真实时间流逝带来的随机补令牌（保证确定性），这仍是真实走完整受限流栈而非跳过判定。
 */
class RateLimitEndToEndTest extends IntegrationTestBase {

    @Autowired
    private RedisTokenBucket tokenBucket;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("端到端：核心接口（抽题）令牌耗尽后返回 429")
    void returns429WhenRateLimited() throws Exception {
        // 任意合法账号即可通过鉴权（权限校验在控制器内、晚于限流拦截器，故 429 先于权限检查返回）
        String token = registerStudent();
        String url = "/api/papers/1/questions/random";
        String key = RedisTokenBucket.key("random-draw"); // 与 commitRandomDraw 的 @RateLimit(key="random-draw") 同桶

        // 用与注解一致的 (capacity=200, qps=50) 快速排空：容量耗尽即返回 false（reject）
        boolean everRejected = false;
        for (int i = 0; i < 1000 && !everRejected; i++) {
            if (!tokenBucket.tryAcquire(key, 200, 50)) {
                everRejected = true;
            }
        }
        assertTrue(everRejected, "抽题桶应已被排空到拒绝状态");

        // 把桶时间戳推到未来 2s：请求的 elapsed = now - ts < 0，脚本跳过补令牌分支 → 桶持续为空 → 必然 429
        double future = (System.currentTimeMillis() + 2000) / 1000.0;
        redis.opsForHash().put(key, "ts", String.valueOf(future));

        JsonNode body = perform(postJson(url, token), 429);
        assertEquals(1008, body.get("code").asInt(), "超限应返回业务码 1008（TOO_MANY_REQUESTS）");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postJson(String url, String token) {
        return jsonPost(url, token, "{}");
    }
}