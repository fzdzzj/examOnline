package com.exam.question.support;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.question.entity.QuestionType;

import java.util.Set;
import java.util.TreeSet;

/**
 * 题目答案归一化工具（spec「答案归一化」需求）：
 * 答案在入库前统一格式，为后续判分提供唯一存储口径——
 * <ul>
 *   <li>单选：单个大写选项字母，如 {@code A}；</li>
 *   <li>多选：去重升序的选项字母列表（逗号分隔），如 {@code A,B,C}，消除录入顺序歧义；</li>
 *   <li>判断：只存 {@code T}/{@code F}（"正确/对/A/是/√" 归一为 T，"错误/错/B/否/×" 归一为 F）；</li>
 *   <li>简答：参考答案原文，仅去首尾空白。</li>
 * </ul>
 */
public final class AnswerNormalizer {

    /** 判断题"真"的常见录入别名（含选项式 A、数字式 1） */
    private static final Set<String> TRUE_ALIASES =
            Set.of("T", "TRUE", "正确", "对", "对的", "是", "YES", "Y", "√", "A", "1");

    /** 判断题"假"的常见录入别名（含选项式 B、数字式 0） */
    private static final Set<String> FALSE_ALIASES =
            Set.of("F", "FALSE", "错误", "错", "不对", "否", "NO", "N", "×", "X", "B", "0");

    private AnswerNormalizer() {
    }

    /**
     * 归一化答案。
     *
     * @param type        题型
     * @param rawAnswer   教师录入的原始答案
     * @param choiceCount 客观题选项数量（判断/简答传 0）
     * @return 归一化后的存储格式
     * @throws BusinessException 题型非法、答案为空或超出选项范围
     */
    public static String normalize(QuestionType type, String rawAnswer, int choiceCount) {
        if (type == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "不支持的题目类型");
        }
        if (rawAnswer == null || rawAnswer.isBlank()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "正确答案不能为空");
        }
        return switch (type) {
            case SINGLE -> normalizeSingle(rawAnswer.trim(), choiceCount);
            case MULTIPLE -> normalizeMultiple(rawAnswer, choiceCount);
            case JUDGE -> normalizeJudge(rawAnswer.trim());
            case SHORT_ANSWER -> rawAnswer.trim();
        };
    }

    /** 单选：一个大写选项字母，且必须在选项范围内。 */
    private static String normalizeSingle(String raw, int choiceCount) {
        if (choiceCount <= 0) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "客观题必须提供选项");
        }
        String upper = raw.toUpperCase();
        if (upper.length() != 1 || upper.charAt(0) < 'A' || upper.charAt(0) >= 'A' + choiceCount) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "单选题答案必须是选项字母（A-" + (char) ('A' + choiceCount - 1) + "）");
        }
        return upper;
    }

    /**
     * 多选：逐字符解析选项字母（分隔符跳过，故 "A,B"、"A、B"、"AB" 均可录入），
     * 去重后升序输出，消除顺序歧义。
     */
    private static String normalizeMultiple(String raw, int choiceCount) {
        if (choiceCount <= 0) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "客观题必须提供选项");
        }
        TreeSet<Character> letters = new TreeSet<>();
        for (char c : raw.toUpperCase().toCharArray()) {
            if (c >= 'A' && c < 'A' + choiceCount) {
                letters.add(c);
            } else if (!isSeparator(c)) {
                throw new BusinessException(ResponseCode.BAD_REQUEST, "多选题答案包含无效选项: " + c);
            }
        }
        if (letters.isEmpty()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "多选题答案不能为空");
        }
        StringBuilder sb = new StringBuilder();
        for (char c : letters) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 判断：统一归一化为 T/F。 */
    private static String normalizeJudge(String raw) {
        String upper = raw.toUpperCase();
        if (TRUE_ALIASES.contains(upper)) {
            return "T";
        }
        if (FALSE_ALIASES.contains(upper)) {
            return "F";
        }
        throw new BusinessException(ResponseCode.BAD_REQUEST, "判断题答案仅支持 正确/错误、对/错、T/F 等");
    }

    /** 多选答案的分隔符：中英文逗号、顿号、分号与空白（选项字母之外的其他字符报错）。 */
    private static boolean isSeparator(char c) {
        return c == ',' || c == '，' || c == '、' || c == ';' || c == '；' || Character.isWhitespace(c);
    }
}
