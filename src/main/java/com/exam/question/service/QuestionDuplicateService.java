package com.exam.question.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.question.dto.QuestionDuplicateItemResponse;
import com.exam.question.entity.Question;
import com.exam.question.mapper.QuestionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 题库查重服务（add-question-duplicate-detection，零新依赖）：
 * 基于字符双字切分（Bigram Jaccard）与编辑距离（Normalized Levenshtein）的纯 Java
 * 混合相似度算法，检索 is_deleted=0（逻辑删除自动过滤）且归属当前教师题库的题目
 * （ADMIN 查全量），支持 excludeId 排除自身与 threshold/limit 过滤。
 *
 * <p>算法口径（供 {@code QuestionDuplicateServiceAlgorithmTest} 直接验证的 static 方法）：
 * <pre>{@code
 * computeSimilarity(a,b) = 0.6 * bigramJaccard(a,b) + 0.4 * normalizedEditSimilarity(a,b)
 * }</pre>
 * 预处理：全角→半角、大小写归一、剔除标点与空白（Unicode 标点族 \p{P} 与空白 \p{IsWhite_Space}）。
 */
@Slf4j
@Service
public class QuestionDuplicateService {

    /** 默认相似度阈值（60%）。 */
    public static final double DEFAULT_THRESHOLD = 0.6;
    /** 默认返回条数。 */
    public static final int DEFAULT_LIMIT = 5;
    /** 返回条数上限。 */
    public static final int MAX_LIMIT = 10;

    private final QuestionMapper questionMapper;

    public QuestionDuplicateService(QuestionMapper questionMapper) {
        this.questionMapper = questionMapper;
    }

    /**
     * 对题库执行查重预检：返回相似度 ≥ threshold 的前 limit 条命中，按相似度降序。
     * stem 已由 Controller 校验非空；此处不再重复，但先剔除无效输入做防御。
     *
     * @param stem      待检测题干（必填，≥2 有效字符）
     * @param excludeId 排除的题目自身 id（编辑题目场景），可为 null
     * @param type      可选：仅检指定题型
     * @param threshold 相似度阈值（默认 0.6）
     * @param limit     返回条数（默认 5，上限 10）
     */
    public List<QuestionDuplicateItemResponse> checkDuplicate(String stem, Long excludeId,
                                                              Integer type, Double threshold,
                                                              Integer limit) {
        LoginUser operator = requireLogin();
        double thr = threshold == null ? DEFAULT_THRESHOLD : threshold;
        int n = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);

        // is_deleted=0 由 @TableLogic 自动过滤；非 ADMIN 强制限定归属当前教师，防跨教师读取
        List<Question> pool = questionMapper.selectList(Wrappers.<Question>lambdaQuery()
                .eq(operator.getRoleLevel() < RoleHierarchy.levelOf(RoleHierarchy.ADMIN),
                        Question::getCreatedBy, operator.getId())
                .eq(type != null, Question::getType, type)
                .select(Question::getId, Question::getType, Question::getContent,
                        Question::getScore, Question::getCreatedBy, Question::getCreatedTime));

        List<QuestionDuplicateItemResponse> hits = new ArrayList<>();
        for (Question q : pool) {
            if (q.getId().equals(excludeId)) {
                continue;
            }
            double similarity = computeSimilarity(stem, q.getContent());
            if (similarity < thr) {
                continue;
            }
            hits.add(new QuestionDuplicateItemResponse(
                    q.getId(), q.getType(), q.getContent(), q.getScore(),
                    similarity, q.getCreatedBy(), q.getCreatedTime()));
        }
        hits.sort(Comparator.comparingDouble(
                (QuestionDuplicateItemResponse r) -> r.getSimilarity()).reversed());
        return hits.subList(0, Math.min(n, hits.size()));
    }

    // ==================== 相似度算法（static，纯 Java 单测可直测） ====================

    /**
     * 混合相似度：0.6 * Bigram Jaccard + 0.4 * 归一化编辑距离相似度，取值 0.0~1.0。
     * 任一侧预处理后为空 → 0.0（无共享信息）。
     */
    public static double computeSimilarity(String left, String right) {
        String a = preprocess(left);
        String b = preprocess(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        return 0.6 * bigramJaccard(a, b) + 0.4 * normalizedEditSimilarity(a, b);
    }

    /**
     * 预处理：全角→半角、英文字母大小写归一、剔除 Unicode 标点与空白。
     */
    public static String preprocess(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c == '\u3000') { // 全角空格
                sb.append(' ');
            } else if (c >= '\uFF01' && c <= '\uFF5E') { // 全角可打印符号区间 → 半角
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\s]+", "");
    }

    /**
     * 字符双字切分的 Jaccard 相似度（按字符集合）。空序列归零，避免 0/0。
     */
    public static double bigramJaccard(String a, String b) {
        Set<String> ga = bigramSet(a);
        Set<String> gb = bigramSet(b);
        if (ga.isEmpty() && gb.isEmpty()) {
            return 0.0;
        }
        Set<String> union = new HashSet<>(ga);
        union.addAll(gb);
        Set<String> inter = new HashSet<>(ga);
        inter.retainAll(gb);
        return (double) inter.size() / union.size();
    }

    /** 取长度为 2 的相邻字符（含中文按字符计）；单字符文本以自身为 gram。 */
    private static Set<String> bigramSet(String s) {
        Set<String> grams = new HashSet<>();
        if (s.isEmpty()) {
            return grams;
        }
        if (s.length() == 1) {
            grams.add(s);
            return grams;
        }
        for (int i = 0; i < s.length() - 1; i++) {
            grams.add(s.substring(i, i + 2));
        }
        return grams;
    }

    /**
     * 归一化编辑距离相似度：1 - levenshtein/maxLen，取值 0.0~1.0。双侧为空归零。
     */
    public static double normalizedEditSimilarity(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0.0;
        }
        int maxLen = Math.max(a.length(), b.length());
        return 1.0 - (double) levenshtein(a, b) / maxLen;
    }

    /** 经典 Levenshtein 编辑距离（滚动数组，O(m*n) 时间 / O(n) 空间）。 */
    private static int levenshtein(String a, String b) {
        int m = a.length();
        int n = b.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            System.arraycopy(curr, 0, prev, 0, n + 1);
        }
        return prev[n];
    }

    private LoginUser requireLogin() {
        LoginUser operator = SecurityUtil.getCurrentUser();
        if (operator == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        return operator;
    }
}