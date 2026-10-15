package com.exam.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.SecurityUtil;
import com.exam.clazz.entity.UserClass;
import com.exam.clazz.mapper.UserClassMapper;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.entity.ExamReservation;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.mapper.ExamReservationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 考试预约服务（add-exam-reservation，独立 service 先例）：
 * 学生预约制考试（exams.reservation_required=1）的预约 / 取消 / 状态查询。
 *
 * <p><b>可约窗口</b>：已发布且未开始（published=1 AND status=0）；开考瞬间（状态机 → 进行中）
 * 预约名单即锁定——开考后不可预约、不可取消。
 *
 * <p><b>预约资格与准入口径同源</b>：学生预约时即做与进入准入（ExamTakingService.assertAdmission
 * 班级分支）同源的班级归属校验——防止"约了进不去"的死约污染应考名单（缺席统计不会把外班预约者
 * 算作应考）。不另造第二套资格口径；本服务不做补考资格处理（补考白名单制与预约正交）。
 *
 * <p><b>重约幂等</b>：取消预约 = 物理删除，重新预约 = 重新插入，
 * uk_reservation_exam_student 唯一索引天然幂等——一人一场至多一条有效预约。
 */
@Slf4j
@Service
public class ExamReservationService {

    private final ExamMapper examMapper;
    private final ExamReservationMapper reservationMapper;
    private final UserClassMapper userClassMapper;

    public ExamReservationService(ExamMapper examMapper,
                                  ExamReservationMapper reservationMapper,
                                  UserClassMapper userClassMapper) {
        this.examMapper = examMapper;
        this.reservationMapper = reservationMapper;
        this.userClassMapper = userClassMapper;
    }

    /**
     * 学生预约考试：已发布且未开始 + 资格同源校验（班级归属）。幂等：重复预约 / 取消后重约均成功。
     */
    @Transactional(rollbackFor = Exception.class)
    public void reserve(Long examId) {
        Long studentId = requireStudent();
        Exam exam = requireReservable(examId);
        assertEligible(exam, studentId);

        ExamReservation reservation = new ExamReservation();
        reservation.setExamId(exam.getId());
        reservation.setStudentId(studentId);
        try {
            reservationMapper.insert(reservation);
            log.info("学生 {} 预约考试 {}", studentId, exam.getId());
        } catch (DuplicateKeyException e) {
            // uk_reservation_exam_student 撞唯一索引：本人已有一场一条有效预约，幂等视为成功（不重插）
            log.info("学生 {} 重复预约考试 {}（已预约，幂等跳过）", studentId, exam.getId());
        }
    }

    /**
     * 学生取消预约：仅开考前可取消（物理删除 = 重约可重新插入）。开考后名单锁定，不可取消。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long examId) {
        Long studentId = requireStudent();
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        if (exam.getReservationRequired() == null || exam.getReservationRequired() != 1) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "该考试非预约制考试，无需取消预约");
        }
        if (exam.getStatus() != null && exam.getStatus() != Exam.STATUS_NOT_STARTED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "开考后不可取消预约（名单已锁定）");
        }
        reservationMapper.delete(Wrappers.<ExamReservation>lambdaQuery()
                .eq(ExamReservation::getExamId, examId)
                .eq(ExamReservation::getStudentId, studentId));
        log.info("学生 {} 取消预约考试 {}", studentId, examId);
    }

    /** 本人在指定考试是否有效预约（学生考试列表 reserved 字段来源）。 */
    public boolean isReserved(Long examId, Long studentId) {
        Long count = reservationMapper.selectCount(Wrappers.<ExamReservation>lambdaQuery()
                .eq(ExamReservation::getExamId, examId)
                .eq(ExamReservation::getStudentId, studentId));
        return count != null && count > 0;
    }

    /**
     * 按考试取有效预约学生名单（add-exam-reservation「预约即应考」名单来源，
     * 供 AbsenceService / ExamAnalysisReportService 分流消费）。
     */
    public List<Long> listReservedStudentIds(Long examId) {
        return reservationMapper.selectList(Wrappers.<ExamReservation>lambdaQuery()
                        .select(ExamReservation::getStudentId)
                        .eq(ExamReservation::getExamId, examId))
                .stream().map(ExamReservation::getStudentId).filter(Objects::nonNull).toList();
    }

    // ==================== 校验 ====================

    /** 可约窗口：存在且已发布且未开始（开考即锁定，不可再约）。 */
    private Exam requireReservable(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        if (exam.getReservationRequired() == null || exam.getReservationRequired() != 1) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "该考试非预约制考试，无需预约");
        }
        if (exam.getPublished() == null || exam.getPublished() != 1) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "考试未发布，不可预约");
        }
        if (exam.getStatus() == null || exam.getStatus() != Exam.STATUS_NOT_STARTED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "开考后不可预约（预约名单已锁定）");
        }
        return exam;
    }

    /**
     * 预约资格校验（与进入准入同源，承接 assertAdmission 班级分支语义）：
     * 补考不适用预约；考试须绑定班级；学生当前须属于该班级。
     * 外班学生拒绝预约，避免"约了进不去"的死约污染应考名单。
     */
    private void assertEligible(Exam exam, Long studentId) {
        if (exam.getParentExamId() != null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "补考不适用预约");
        }
        if (exam.getClassId() == null) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "该考试未指派班级，无法预约");
        }
        Long inClass = userClassMapper.selectCount(Wrappers.<UserClass>lambdaQuery()
                .eq(UserClass::getUserId, studentId)
                .eq(UserClass::getClassId, exam.getClassId()));
        if (inClass == null || inClass == 0) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "您不属于该考试指定的班级，无法预约");
        }
    }

    private Long requireStudent() {
        Long userId = SecurityUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        return userId;
    }
}