/**
 * 草稿同步状态展示单测（阶段 22 第 2 片）。
 *
 * 重点不在配色，而在**措辞纪律**（spec「不声称离线考试」场景）：
 * 断线状态的文案只能说「答案已保存在本机 / 恢复后同步」，
 * 任何状态都不得出现「离线考试 / 离线作答」的表述。
 */
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';

import DraftSyncBadge from '@/components/student/DraftSyncBadge.vue';
import type { DraftSyncStatus } from '@/hooks/useAutoSaveDraft';

function badge(status: DraftSyncStatus, pendingSync = false, lastSavedAt: string | null = null) {
  return mount(DraftSyncBadge, {
    props: { status, pendingSync, lastSavedAt },
  });
}

function statusText(wrapper: ReturnType<typeof badge>): string {
  return wrapper.find('[data-test="draft-sync-status"]').text();
}

describe('DraftSyncBadge 状态文案', () => {
  it('synced：显示已自动保存与服务器确认时刻', () => {
    const wrapper = badge('synced', false, '2026-09-21T10:00:00');
    expect(statusText(wrapper)).toBe('已自动保存');
    expect(wrapper.find('[data-test="draft-saved-at"]').text()).toContain('2026-09-21T10:00:00');
  });

  it('pending / saving / idle：普通状态不渲染任何警告', () => {
    for (const status of ['pending', 'saving', 'idle'] as const) {
      const wrapper = badge(status);
      expect(wrapper.find('[data-test="draft-unsynced-alert"]').exists()).toBe(false);
      expect(wrapper.find('[data-test="draft-conflict-alert"]').exists()).toBe(false);
      expect(wrapper.find('[data-test="draft-pending-sync-alert"]').exists()).toBe(false);
    }
  });

  it('unsynced：明示「答案已保存在本机」+「恢复网络后自动同步」', () => {
    const wrapper = badge('unsynced');
    const alert = wrapper.find('[data-test="draft-unsynced-alert"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('答案已保存在本机');
    expect(alert.text()).toContain('恢复网络后会自动同步');
  });

  it('conflict：明示服务器存在更新的草稿，不替学生做覆盖决定', () => {
    const wrapper = badge('conflict');
    const alert = wrapper.find('[data-test="draft-conflict-alert"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('服务器存在更新的草稿');
  });

  it('pendingSync（归零待同步）：说明锁定 + 后端兜底收卷，且不宣称前端判定超时', () => {
    const wrapper = badge('unsynced', true);
    const alert = wrapper.find('[data-test="draft-pending-sync-alert"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('作答已锁定，答案待同步');
    expect(alert.text()).toContain('由后端判定');
    expect(statusText(wrapper)).toBe('待同步');
  });

  it('措辞护栏：任何状态都不出现「离线考试 / 离线作答」表述', () => {
    for (const status of ['idle', 'pending', 'saving', 'synced', 'unsynced', 'conflict'] as const) {
      const wrapper = badge(status, true);
      expect(wrapper.text()).not.toContain('离线考试');
      expect(wrapper.text()).not.toContain('离线作答');
    }
  });
});
