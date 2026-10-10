package com.exam.score.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.clazz.entity.UserClass;
import com.exam.clazz.mapper.UserClassMapper;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.entity.ExamCandidate;
import com.exam.exam.mapper.ExamCandidateMapper;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.score.dto.LeaderboardItem;
import com.exam.score.dto.LeaderboardMyRow;
import com.exam.score.dto.ScoreLeaderboardResponse;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 班级匿名榜单服务（add-class-leaderboard，独立服务类）：
 * <ul>
 *   <li>只读聚合：榜单口径与 publishPreview 同源（STATUS_GRADED + total_score 非空 + orderByDesc + LIMIT 10）；</li>
 *   <li>列投影纪律：仅查询 student_id、total_score 标量列；</li>
 *   <li>准入门控：对齐 myScore 同 404 语义（未发布或本人无成绩记录统一 404「暂无本人成绩记录」）；非本班考生 403；</li>
 *   <li>脱敏与隐私：非本人行姓名保留姓氏 + "**"（如「张**」），绝不下发他人 studentId；本人行实名 + isMe=true；</li>
 *   <li>本人位置：本人在前 10 时在 top 内实名高亮，myRow 为 null；不在前 10 时 top 保持 10 行，myRow 返回本人名次与分数。</li>
 * </ul>
 */
@Slf4j
@Service
public class ScoreLeaderboardService {

    private final ExamMapper examMapper;
    private final GradingSubmissionMapper gradingSubmissionMapper;
    private final ExamSubmissionMapper submissionMapper;
    private final UserMapper userMapper;
    private final RankCalculator rankCalculator;
    private final UserClassMapper userClassMapper;
    private final ExamCandidateMapper examCandidateMapper;

    public ScoreLeaderboardService(ExamMapper examMapper,
                                   GradingSubmissionMapper gradingSubmissionMapper,
                                   ExamSubmissionMapper submissionMapper,
                                   UserMapper userMapper,
                                   RankCalculator rankCalculator,
                                   UserClassMapper userClassMapper,
                                   ExamCandidateMapper examCandidateMapper) {
        this.examMapper = examMapper;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
        this.submissionMapper = submissionMapper;
        this.userMapper = userMapper;
        this.rankCalculator = rankCalculator;
        this.userClassMapper = userClassMapper;
        this.examCandidateMapper = examCandidateMapper;
    }

    public ScoreLeaderboardResponse leaderboard(Long examId, Long currentStudentId) {
        if (currentStudentId == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }

        // 准入门控：非本场考试考生 403 拒绝
        assertExaminee(exam, currentStudentId);

        // 发布门控：未发布返回 404「暂无本人成绩记录」（不泄露榜单任何行）
        if (exam.getStatus() == null || exam.getStatus() != Exam.STATUS_PUBLISHED) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "暂无本人成绩记录");
        }

        // 本人成绩记录校验：未交卷/未汇总/状态非已批改返回 404
        GradingSubmission mySubmission = gradingSubmissionMapper.selectOne(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .select(GradingSubmission::getStudentId, GradingSubmission::getTotalScore, GradingSubmission::getStatus)
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStudentId, currentStudentId));

        if (mySubmission == null || mySubmission.getTotalScore() == null
                || !Objects.equals(mySubmission.getStatus(), ExamSubmission.STATUS_GRADED)) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "暂无本人成绩记录");
        }

        // 取数：与 publishPreview 同源（STATUS_GRADED + total_score 非空 + orderByDesc），LIMIT 10
        // 列投影纪律：仅投影 student_id, total_score 两列
        List<GradingSubmission> topSubmissions = gradingSubmissionMapper.selectList(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .select(GradingSubmission::getStudentId, GradingSubmission::getTotalScore)
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED)
                        .isNotNull(GradingSubmission::getTotalScore)
                        .orderByDesc(GradingSubmission::getTotalScore)
                        .last("LIMIT 10"));

        List<BigDecimal> topScores = topSubmissions.stream()
                .map(GradingSubmission::getTotalScore)
                .toList();
        int[] topRanks = rankCalculator.rank(topScores);

        // 批量载入学生姓名
        List<Long> studentIds = topSubmissions.stream()
                .map(GradingSubmission::getStudentId)
                .distinct()
                .toList();
        Map<Long, User> userMap = loadUsers(studentIds);

        boolean meInTop = false;
        List<LeaderboardItem> topItems = new ArrayList<>(topSubmissions.size());
        for (int i = 0; i < topSubmissions.size(); i++) {
            GradingSubmission sub = topSubmissions.get(i);
            boolean isMe = sub.getStudentId().equals(currentStudentId);
            if (isMe) {
                meInTop = true;
            }
            User user = userMap.get(sub.getStudentId());
            String rawName = (user != null && user.getName() != null) ? user.getName() : "未知学生";
            String displayName = isMe ? rawName : maskName(rawName);

            LeaderboardItem item = new LeaderboardItem(
                    topRanks[i],
                    displayName,
                    sub.getTotalScore(),
                    isMe
            );
            topItems.add(item);
        }

        LeaderboardMyRow myRow = null;
        if (!meInTop) {
            // 本人不在前 10 时，聚合计数计算本人名次（更高分人数 + 1），不混入前 10 截断
            Long higherCount = gradingSubmissionMapper.selectCount(
                    Wrappers.<GradingSubmission>lambdaQuery()
                            .eq(GradingSubmission::getExamId, examId)
                            .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED)
                            .isNotNull(GradingSubmission::getTotalScore)
                            .gt(GradingSubmission::getTotalScore, mySubmission.getTotalScore()));
            int myRank = (higherCount == null ? 0 : higherCount.intValue()) + 1;
            myRow = new LeaderboardMyRow(myRank, mySubmission.getTotalScore(), true);
        }

        ScoreLeaderboardResponse response = new ScoreLeaderboardResponse();
        response.setExamId(examId);
        response.setExamTitle(exam.getTitle());
        response.setMyRow(myRow);
        response.setTop(topItems);
        return response;
    }

    private void assertExaminee(Exam exam, Long studentId) {
        // 1. 已有答卷（含进行中/已交卷/已批改）确认为考生
        Long subCount = submissionMapper.selectCount(Wrappers.<ExamSubmission>lambdaQuery()
                .eq(ExamSubmission::getExamId, exam.getId())
                .eq(ExamSubmission::getStudentId, studentId));
        if (subCount != null && subCount > 0) {
            return;
        }
        // 2. 补考按名单准入
        if (exam.getParentExamId() != null) {
            Long candCount = examCandidateMapper.selectCount(Wrappers.<ExamCandidate>lambdaQuery()
                    .eq(ExamCandidate::getExamId, exam.getId())
                    .eq(ExamCandidate::getStudentId, studentId));
            if (candCount != null && candCount > 0) {
                return;
            }
        }
        // 3. 普通考试按班级归属准入
        if (exam.getClassId() != null) {
            Long classCount = userClassMapper.selectCount(Wrappers.<UserClass>lambdaQuery()
                    .eq(UserClass::getUserId, studentId)
                    .eq(UserClass::getClassId, exam.getClassId()));
            if (classCount != null && classCount > 0) {
                return;
            }
        }
        throw new BusinessException(ResponseCode.FORBIDDEN, "非本场考试考生，无权查看榜单");
    }

    private Map<Long, User> loadUsers(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<User> list = userMapper.selectBatchIds(ids);
        if (list == null) {
            return Collections.emptyMap();
        }
        return list.stream().collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
    }

    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "某**";
        }
        return name.substring(0, 1) + "**";
    }
}
