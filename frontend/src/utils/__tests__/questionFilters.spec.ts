import { describe, expect, it } from 'vitest';

import { buildQuestionPageQuery } from '@/utils/questionFilters';

describe('题库列表筛选参数构造', () => {
  it('全量筛选：题型 + 难度 + 标签 + 关键词 + 分页逐一下发', () => {
    expect(
      buildQuestionPageQuery({
        page: 2,
        size: 20,
        type: 3,
        difficulty: 1,
        tagId: 7,
        keyword: '导数',
      })
    ).toEqual({ page: 2, size: 20, type: 3, difficulty: 1, tagId: 7, keyword: '导数' });
  });

  it('无筛选：仅下发默认分页参数 page=1 size=10', () => {
    expect(buildQuestionPageQuery({})).toEqual({ page: 1, size: 10 });
  });

  it('空值筛选不下发（后端按 null 跳过条件）', () => {
    const query = buildQuestionPageQuery({
      type: null,
      difficulty: null,
      tagId: null,
      keyword: null,
    });
    expect(query).toEqual({ page: 1, size: 10 });
    expect('type' in query).toBe(false);
    expect('keyword' in query).toBe(false);
  });

  it('关键词去首尾空白，纯空白视作未筛选', () => {
    expect(buildQuestionPageQuery({ keyword: '  函数  ' }).keyword).toBe('函数');
    expect('keyword' in buildQuestionPageQuery({ keyword: '   ' })).toBe(false);
  });

  it('分页参数夹取：page 最小 1，size 限制在 1–100（对齐后端 Math.min(size, 100)）', () => {
    expect(buildQuestionPageQuery({ page: 0, size: 0 })).toEqual({ page: 1, size: 1 });
    expect(buildQuestionPageQuery({ page: -3, size: 500 })).toEqual({ page: 1, size: 100 });
    expect(buildQuestionPageQuery({ page: 1.8, size: 10.6 })).toEqual({ page: 1, size: 10 });
  });
});
