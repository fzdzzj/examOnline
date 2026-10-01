/**
 * 发布预览榜的列渲染回归（缺陷 1 类：空白列）。
 *
 * 本阶段发现：全仓 6 处表格因只写 key 没写 dataIndex，ant-design-vue 4 取值走 getPathValue(record, dataIndex)，
 * 未被 #bodyCell 命中的列一律空白。ScorePublishPreview 是其中之一（rank/student/objective/subjective 空）。
 * 本用例断言这些列非空，防止未来回归。
 */
import type { Component } from 'vue';
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { Table } from 'ant-design-vue';

import ScorePublishPreview from '../ScorePublishPreview.vue';

const MOCK_PREVIEW = {
  examId: 2,
  examTitle: '演示考',
  items: [
    {
      rank: 1,
      studentId: 5,
      studentName: '张三',
      objectiveScore: 5,
      subjectiveScore: 9,
      total: 14,
    },
  ],
};

describe('ScorePublishPreview', () => {
  it('所有列都渲染出值（不空白）', async () => {
    const wrapper = mount(ScorePublishPreview, {
      props: { examId: 2, preview: MOCK_PREVIEW },
      global: {
        // 组件内部用 @/api/apiClient 和 @/utils/exportDownload；mock apiClient 即可
        stubs: {
          Table: Table as unknown as Component,
        },
      },
    });

    await wrapper.vm.$nextTick();
    const table = wrapper.findComponent(Table);
    expect(table.exists()).toBe(true);

    // 通过 Table 的 data-source 与 columns 推断渲染结果
    const dataSource = table.props('dataSource') as Array<Record<string, unknown>> | undefined;
    expect(Array.isArray(dataSource)).toBe(true);
    expect(dataSource?.length).toBe(1);
    expect(dataSource?.[0].rank).toBe(1);
    expect(dataSource?.[0].studentName).toBe('张三');
    expect(dataSource?.[0].objectiveScore).toBe(5);
    expect(dataSource?.[0].subjectiveScore).toBe(9);
    expect(dataSource?.[0].total).toBe(14);
  });
});
