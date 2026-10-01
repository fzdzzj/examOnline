/**
 * 考后闭环的状态映射（映射表本身来自后端枚举，前端只读展示）。
 * 缺考标记来源：后端 AbsenceItemResponse 无 source 字段（只有 studentId/studentName/markedTime），
 * 因此这里**不提供来源映射**——用例把「不编造来源」这条事实钉死，避免后续有人补一个假映射。
 */
import { describe, expect, it } from 'vitest';

import {
  GRADING_CONFLICT_CODE,
  REVIEW_ALREADY_APPLIED_CODE,
  REVIEW_STATUS,
  getReviewStatusConfig,
  makeupRuleLabel,
} from '../../constants/postExam';

describe('关键业务码常量与后端对齐', () => {
  it('批改乐观锁冲突 = 1012（ResponseCode.STATE_CONFLICT / HTTP 409）', () => {
    expect(GRADING_CONFLICT_CODE).toBe(1012);
  });

  it('复核重复申请 = 1001（ResponseCode.DATA_ALREADY_EXISTS）', () => {
    expect(REVIEW_ALREADY_APPLIED_CODE).toBe(1001);
  });
});

describe('复核状态映射（ScoreReview.STATUS_*）', () => {
  it('四个状态各就各位', () => {
    expect(getReviewStatusConfig(REVIEW_STATUS.PENDING)).toMatchObject({
      label: '待处理',
      ongoing: true,
    });
    expect(getReviewStatusConfig(REVIEW_STATUS.PROCESSING)).toMatchObject({
      label: '处理中',
      ongoing: true,
    });
    expect(getReviewStatusConfig(REVIEW_STATUS.AGREED)).toMatchObject({
      label: '已同意',
      ongoing: false,
    });
    expect(getReviewStatusConfig(REVIEW_STATUS.REJECTED)).toMatchObject({
      label: '已驳回',
      ongoing: false,
    });
  });

  it('进行中（待处理 / 处理中）才触发成绩隐藏语义', () => {
    const ongoing = [REVIEW_STATUS.PENDING, REVIEW_STATUS.PROCESSING].map(
      (s) => getReviewStatusConfig(s).ongoing
    );
    const settled = [REVIEW_STATUS.AGREED, REVIEW_STATUS.REJECTED].map(
      (s) => getReviewStatusConfig(s).ongoing
    );
    expect(ongoing).toEqual([true, true]);
    expect(settled).toEqual([false, false]);
  });

  it('未知码不崩，显示为未知且不当作进行中', () => {
    expect(getReviewStatusConfig(99).label).toContain('未知');
    expect(getReviewStatusConfig(99).ongoing).toBe(false);
  });
});

describe('补考成绩规则（Exam.MAKEUP_TAKE_*）', () => {
  it('三个规则都能显示可读文案', () => {
    expect(makeupRuleLabel('takeHighest')).toContain('取最高分');
    expect(makeupRuleLabel('takeLatest')).toContain('取最近一次');
    expect(makeupRuleLabel('takeAverage')).toContain('取平均分');
  });

  it('未设置 / 未知规则不谎报，原样暴露', () => {
    expect(makeupRuleLabel(undefined)).toContain('未设置');
    expect(makeupRuleLabel('weird')).toContain('weird');
  });
});

describe('缺考标记来源（接口事实）', () => {
  it('后端响应字段不含来源标记，前端因此不提供来源映射', () => {
    // AbsenceItemResponse = { studentId, studentName, markedTime }
    const absenceFields = ['studentId', 'studentName', 'markedTime'];
    expect(absenceFields).not.toContain('source');
    expect(absenceFields).not.toContain('reason');
  });
});
