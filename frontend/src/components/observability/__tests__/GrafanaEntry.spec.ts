/**
 * 观测入口用例（阶段 21 缺口 5）。
 *
 * 这条缺口最容易"做假"：写死一个端口、或把"打不开"渲染成一个空面板，
 * 都能让人一眼以为"接上了"。所以断言集中在三件事——
 * ① 地址只来自 `VITE_GRAFANA_BASE_URL`（不传就当没配，不给默认值）；
 * ② 只做只读跳转（不内嵌 iframe、不画图表）；
 * ③ `unreachable` 的文案是"打不开"，且**绝不出现"无数据"那类措辞**。
 */
import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';

import GrafanaEntry from '@/components/observability/GrafanaEntry.vue';
import {
  grafanaBaseUrl,
  grafanaDashboardUrl,
  grafanaHealthUrl,
  probeGrafana,
} from '@/utils/grafana';

const BASE = 'http://panel.invalid';

describe('地址取值', () => {
  it('未配置环境变量时返回 null——不回落成任何带端口的默认地址', () => {
    expect(grafanaBaseUrl({} as ImportMetaEnv)).toBeNull();
    expect(grafanaBaseUrl({ VITE_GRAFANA_BASE_URL: '   ' } as ImportMetaEnv)).toBeNull();
  });

  it('配置了就去掉尾部斜杠，健康址与面板址都从它派生（不再出现第二处地址）', () => {
    const base = grafanaBaseUrl({ VITE_GRAFANA_BASE_URL: `${BASE}/grafana//` } as ImportMetaEnv);
    expect(base).toBe(`${BASE}/grafana`);
    expect(grafanaHealthUrl(base as string)).toBe(`${BASE}/grafana/api/health`);
    expect(grafanaDashboardUrl(base as string)).toBe(`${BASE}/grafana/`);
  });
});

describe('连通性探测', () => {
  it('fetch 有回应（含被 CORS 挡下的不透明响应）即判可达', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal('fetch', fetchMock);

    await expect(probeGrafana(BASE)).resolves.toBe(true);
    // 只探健康端点，且 no-cors：不读面板内容，也就无从"伪造面板数据"
    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE}/api/health`);
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ mode: 'no-cors' });

    vi.unstubAllGlobals();
  });

  it('fetch 抛错（连不上）判不可达', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('fetch failed')));

    await expect(probeGrafana(BASE)).resolves.toBe(false);

    vi.unstubAllGlobals();
  });
});

describe('GrafanaEntry 三态渲染', () => {
  it('未配置：给出可读的降级说明与变量名，且完全不渲染链接', () => {
    const probe = vi.fn();
    const wrapper = mount(GrafanaEntry, { props: { baseUrl: '', probe } });

    expect(wrapper.attributes('data-state')).toBe('unconfigured');
    expect(wrapper.text()).toContain('VITE_GRAFANA_BASE_URL');
    expect(wrapper.find('a').exists()).toBe(false);
    // 没配地址就不该发探测请求
    expect(probe).not.toHaveBeenCalled();
  });

  it('已配置且可连通：只读外链（新标签 + noreferrer），不内嵌 iframe', async () => {
    const wrapper = mount(GrafanaEntry, {
      props: { baseUrl: BASE, probe: vi.fn().mockResolvedValue(true) },
    });
    await vi.waitFor(() => expect(wrapper.attributes('data-state')).toBe('reachable'));

    const link = wrapper.find('a');
    expect(link.attributes('href')).toBe(`${BASE}/`);
    expect(link.attributes('target')).toBe('_blank');
    expect(link.attributes('rel')).toContain('noopener');
    expect(wrapper.find('iframe').exists()).toBe(false);
  });

  it('已配置但连不通：说"打不开"并给出编排文件指针，绝不写成"无数据"', async () => {
    const wrapper = mount(GrafanaEntry, {
      props: { baseUrl: `${BASE}/`, probe: vi.fn().mockResolvedValue(false) },
    });
    await vi.waitFor(() => expect(wrapper.attributes('data-state')).toBe('unreachable'));

    const text = wrapper.text();
    expect(text).toContain('打不开');
    expect(text).toContain('不等于');
    expect(text).toContain('docker/observability/docker-compose.observability.yml');
    expect(text).not.toMatch(/无数据|暂无数据|没有数据/);
    // 仍然保留手动可点的外链：状态判定不改链接本身
    expect(wrapper.find('a').attributes('href')).toBe(`${BASE}/`);
  });

  it('不在前端写死宿主端口：三态下界面里都不该出现 "localhost:数字" 形态的地址', async () => {
    const cases: Array<{ props: Record<string, unknown>; state: string }> = [
      { props: { baseUrl: '', probe: vi.fn() }, state: 'unconfigured' },
      {
        props: { baseUrl: BASE, probe: vi.fn().mockResolvedValue(true) },
        state: 'reachable',
      },
      {
        props: { baseUrl: BASE, probe: vi.fn().mockResolvedValue(false) },
        state: 'unreachable',
      },
    ];

    for (const item of cases) {
      const wrapper = mount(GrafanaEntry, { props: item.props });
      await vi.waitFor(() => expect(wrapper.attributes('data-state')).toBe(item.state));
      expect(wrapper.text()).not.toMatch(/localhost:\d+/);
      expect(wrapper.text()).not.toMatch(/127\.0\.0\.1:\d+/);
    }
  });
});
