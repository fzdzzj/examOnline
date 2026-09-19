/**
 * 学生成绩可见性三态（硬约定 3）：可见性完全由后端返回驱动。
 * 关键负向断言：**后端 reviewing=true 时即使响应里带着分数，前端也不得展示分数**——
 * 证明隐藏是后端裁决的结果，而非前端本地判断。
 */
import { describe, expect, it } from 'vitest';

import { ApiError } from '@/api/types';
import type { MyScoreResponse } from '@/api/axios';
import { isNotPublishedError, mapMyScoreToView } from '../scoreVisibility';

describe('成绩可见性映射', () => {
  it('已发布：展示分数明细与排名', () => {
    const response: MyScoreResponse = {
      examId: 3,
      examTitle: '期末考试',
      objectiveScore: 40,
      subjectiveScore: 35,
      totalScore: 75,
      rank: 2,
      reviewing: false,
    };
    const view = mapMyScoreToView(response);
    expect(view.kind).toBe('published');
    expect(view).toMatchObject({ totalScore: 75, rank: 2 });
  });

  it('复核中：只给 reviewing 态，不带任何分数字段（后端已置空，前端不回填）', () => {
    // 构造一个「后端本该置空但仍带着旧分数」的响应，验证前端只看 reviewing 标记
    const response: MyScoreResponse = {
      examId: 3,
      totalScore: 75,
      rank: 0,
      reviewing: true,
    };
    const view = mapMyScoreToView(response);
    expect(view.kind).toBe('reviewing');
    expect(view).not.toHaveProperty('totalScore');
  });

  it('未发布：后端 400「成绩待发布」→ not-published，不当异常弹给用户', () => {
    expect(isNotPublishedError(new ApiError(400, '成绩待发布', undefined, 400))).toBe(true);
    // 其它 400 不算「未发布」，不能被误吞
    expect(isNotPublishedError(new ApiError(400, '参数错误', undefined, 400))).toBe(false);
    expect(isNotPublishedError(new ApiError(1012, '冲突', undefined, 409))).toBe(false);
    expect(isNotPublishedError(new Error('网络错误'))).toBe(false);
  });

  it('响应为空（未发布 / 已撤回）→ not-published，不显示 0 分', () => {
    const view = mapMyScoreToView(undefined);
    expect(view.kind).toBe('not-published');
    expect(view).not.toHaveProperty('totalScore');
  });

  it('reviewing 字段缺失时按已发布渲染（不自行推断复核状态）', () => {
    const view = mapMyScoreToView({ examId: 1, totalScore: 60 });
    expect(view.kind).toBe('published');
  });
});
