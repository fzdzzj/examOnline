package com.exam.submission.service;

import com.exam.submission.entity.ExamDlqMessage;
import com.exam.submission.mapper.ExamDlqMessageMapper;
import com.exam.taking.config.RabbitMqConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 交卷死信有界重投（add-dlq-observability-and-replay）：
 *
 * <p><b>顺序就是设计</b>：{@code receive} → <b>立刻</b>落档 {@code exam_dlq_messages} → 再决定重投或 PARKED。
 * {@link RabbitTemplate#receive(String, long)} 是 autoAck 取走，取出与落档之间存在毫秒级崩溃窗口，
 * 此窗口可接受：死信重投是加速手段，答卷正确性仍由 ExamSweepService 补发对账兜底。
 *
 * <p><b>禁止</b>为消除该窗口而挂常驻 {@code @RabbitListener} 消费 DLQ——否则要么
 * {@code basicNack(requeue=true)} 死循环空转，要么超限丢弃，反而把 DLQ 变成丢数据的地方。
 *
 * <p><b>两层计数不得混用</b>：
 * <ul>
 *   <li>{@code x-retry-count}：同一队列内消费者重试（默认最多 3 次，见 exam.taking.mq.retry-max）；</li>
 *   <li>{@code x-replay-count}：跨重投轮次（默认最多 3 轮，见 exam.mq.dlq.max-replay）。</li>
 * </ul>
 * 上限 = 3 轮 × (1+3) 次尝试 = 12 次后 PARKED，避免「重投→再失败→再进死信→再重投」无限循环。
 *
 * <p>死信稳态深度应为 0；持续非 0 必须查因而非清空。
 */
@Slf4j
@Service
public class DlqReplayService {

    /** 跨重投轮次计数头：与消费者使用的 x-retry-count 互不覆盖 */
    public static final String REPLAY_HEADER = "x-replay-count";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectProvider<RabbitAdmin> rabbitAdmin;
    private final ExamDlqMessageMapper dlqMessageMapper;
    private final ObjectMapper objectMapper;
    private final int maxReplay;
    private final int replayMax;
    private final long receiveTimeoutMs;

    public DlqReplayService(RabbitTemplate rabbitTemplate,
                            ObjectProvider<RabbitAdmin> rabbitAdmin,
                            ExamDlqMessageMapper dlqMessageMapper,
                            ObjectMapper objectMapper,
                            @Value("${exam.mq.dlq.max-replay:3}") int maxReplay,
                            @Value("${exam.mq.dlq.replay-max:100}") int replayMax,
                            @Value("${exam.mq.dlq.receive-timeout-ms:2000}") long receiveTimeoutMs) {
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitAdmin = rabbitAdmin;
        this.dlqMessageMapper = dlqMessageMapper;
        this.objectMapper = objectMapper;
        this.maxReplay = maxReplay;
        this.replayMax = replayMax;
        this.receiveTimeoutMs = receiveTimeoutMs;
    }

    /**
     * 有界重投：一次最多处理 {@code min(max, replay-max)} 条。
     *
     * @return replayed / parked / remaining（remaining 取自 DLQ 当前深度）
     */
    @Transactional(rollbackFor = Exception.class)
    public ReplayReport replayOnce(int max) {
        int limit = Math.max(0, Math.min(max, replayMax));
        int replayed = 0;
        int parked = 0;
        for (int i = 0; i < limit; i++) {
            Message message = rabbitTemplate.receive(RabbitMqConfig.SUBMIT_DLQ, receiveTimeoutMs);
            if (message == null) {
                break;
            }
            // receive 之后立刻落档：中间只做不会阻塞/外呼的本地字段组装
            int retryCount = headerInt(message, RabbitMqConfig.RETRY_HEADER);
            int replayCount = headerInt(message, REPLAY_HEADER);
            boolean overLimit = replayCount >= maxReplay;
            String status = overLimit ? ExamDlqMessage.STATUS_PARKED : ExamDlqMessage.STATUS_REPLAYED;
            archive(message, retryCount, replayCount, status);
            if (overLimit) {
                parked++;
                log.warn("死信重投超限，PARKED 不重投: replayCount={} maxReplay={}", replayCount, maxReplay);
                continue;
            }
            MessageProperties props = message.getMessageProperties();
            props.setHeader(RabbitMqConfig.RETRY_HEADER, 0);
            props.setHeader(REPLAY_HEADER, replayCount + 1);
            // x-request-id 原样保留（不主动清除）
            rabbitTemplate.send(RabbitMqConfig.SUBMIT_EXCHANGE, RabbitMqConfig.SUBMIT_ROUTING_KEY, message);
            replayed++;
        }
        return new ReplayReport(replayed, parked, remainingDepth());
    }

    private void archive(Message message, int retryCount, int replayCount, String status) {
        ExamDlqMessage row = new ExamDlqMessage();
        row.setQueue(RabbitMqConfig.SUBMIT_DLQ);
        row.setPayload(new String(message.getBody(), StandardCharsets.UTF_8));
        row.setHeadersJson(headersJsonSafe(message));
        row.setRetryCount(retryCount);
        row.setReplayCount(replayCount);
        row.setStatus(status);
        dlqMessageMapper.insert(row);
    }

    private String headersJsonSafe(Message message) {
        try {
            Map<String, Object> headers = message.getMessageProperties().getHeaders();
            Map<String, Object> copy = new LinkedHashMap<>();
            if (headers != null) {
                for (Map.Entry<String, Object> e : headers.entrySet()) {
                    Object v = e.getValue();
                    copy.put(e.getKey(), v == null || v instanceof Number || v instanceof Boolean || v instanceof String
                            ? v : String.valueOf(v));
                }
            }
            return objectMapper.writeValueAsString(copy);
        } catch (Exception e) {
            return "{\"serializeError\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    private int remainingDepth() {
        RabbitAdmin admin = rabbitAdmin.getIfAvailable();
        if (admin == null) {
            return 0;
        }
        try {
            var info = admin.getQueueInfo(RabbitMqConfig.SUBMIT_DLQ);
            return info == null ? 0 : info.getMessageCount();
        } catch (Exception e) {
            return 0;
        }
    }

    private static int headerInt(Message message, String name) {
        Object v = message.getMessageProperties().getHeader(name);
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** 一次重投结果：replayed=已重投，parked=超限留置，remaining=DLQ 剩余深度。 */
    public record ReplayReport(int replayed, int parked, int remaining) {
    }
}
