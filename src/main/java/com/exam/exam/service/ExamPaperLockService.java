package com.exam.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.paper.entity.PaperQuestion;
import com.exam.paper.mapper.PaperQuestionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 考试进行中的试卷锁定（spec「试卷锁定」需求，§4.1）：
 * 考试进入进行中后，其绑定的试卷被锁定——禁止修改/删除试卷与题目、禁止改动分值；
 * 未开始与已结束的考试不锁定（修改只影响后续场次）。
 *
 * <p>独立成组件的原因：PaperService 依赖本组件做锁定校验，而本组件只依赖 Mapper
 * 不反向依赖任何 Service，避免 PaperService ↔ ExamService 循环注入。
 */
@Slf4j
@Service
public class ExamPaperLockService {

    /** 与 spec 场景一致的错误口径：考试进行中，试卷已锁定 */
    private static final String LOCK_MESSAGE = "考试进行中，试卷已锁定，不允许修改";

    private final ExamMapper examMapper;
    private final PaperQuestionMapper paperQuestionMapper;

    public ExamPaperLockService(ExamMapper examMapper, PaperQuestionMapper paperQuestionMapper) {
        this.examMapper = examMapper;
        this.paperQuestionMapper = paperQuestionMapper;
    }

    /** 试卷级锁定校验：存在进行中的考试绑定该试卷即拒绝（组卷编辑/删除/再生成快照均调用）。 */
    public void assertPaperEditable(Long paperId) {
        Long count = examMapper.selectCount(Wrappers.<Exam>lambdaQuery()
                .eq(Exam::getPaperId, paperId)
                .eq(Exam::getStatus, Exam.STATUS_IN_PROGRESS));
        if (count > 0) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, LOCK_MESSAGE);
        }
    }

    /**
     * 题目级锁定校验：题目被任一"进行中考试"的试卷引用即拒绝修改/软删——
     * 即使内容变化不影响已生成的考试快照（副本隔离），进行中场次的题库数据也必须稳定，
     * 题目有误走错题补偿流程（§5.2）而非现场改题。
     */
    public void assertQuestionEditable(Long questionId) {
        List<Exam> ongoingExams = examMapper.selectList(Wrappers.<Exam>lambdaQuery()
                .select(Exam::getId, Exam::getPaperId)
                .eq(Exam::getStatus, Exam.STATUS_IN_PROGRESS));
        if (ongoingExams.isEmpty()) {
            return;
        }
        List<Long> ongoingPaperIds = ongoingExams.stream().map(Exam::getPaperId).distinct().toList();
        Long count = paperQuestionMapper.selectCount(Wrappers.<PaperQuestion>lambdaQuery()
                .in(PaperQuestion::getPaperId, ongoingPaperIds)
                .eq(PaperQuestion::getQuestionId, questionId));
        if (count > 0) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, LOCK_MESSAGE);
        }
    }
}
