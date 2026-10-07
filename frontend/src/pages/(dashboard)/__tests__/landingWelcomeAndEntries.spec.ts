/**
 * 仪表盘落地页欢迎卡与常用入口用例（fix-frontend-makeup-time-and-landing，U-5）。
 *
 * 断言口径：
 * 1. 彻底清除阶段 19 开发验证卡（「这一页验证了什么」及双 Token / 续期清单不存在）；
 * 2. 欢迎卡：消费既有 auth store 渲染用户显示名与角色，零新增网络请求；
 * 3. 常用入口：对齐侧边栏既有路由和角色过滤逻辑（学生/教师/管理员自适应展示对应入口），零新造路由；
 * 4. 词法护栏：源码不含「这一页验证了什么」与旧开发验证文案。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';

import LandingPage from '@/pages/(dashboard)/index.page.vue';
import landingSource from '@/pages/(dashboard)/index.page.vue?raw';
import * as api from '@/api/axios';

const mockPush = vi.fn();
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mockPush }),
  useRoute: () => ({ path: '/' }),
  RouterLink: {
    name: 'RouterLink',
    props: ['to'],
    template: '<a :href="to" @click.prevent="$emit(\'click\')"><slot /></a>',
  },
}));

const mockUserState = ref<{
  user: {
    id?: number;
    username?: string;
    name?: string;
    email?: string;
    roles?: string[];
    permissions?: string[];
  } | null;
}>({
  user: null,
});

vi.mock('vuex', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vuex')>();
  return {
    ...actual,
    useStore: () => ({
      state: mockUserState.value,
    }),
  };
});

beforeEach(() => {
  vi.clearAllMocks();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('仪表盘落地页（U-5 收官）', () => {
  it('验证卡清除：页面不含「这一页验证了什么」及阶段 19 开发清单', async () => {
    mockUserState.value = {
      user: {
        id: 1,
        username: 'student1',
        name: '张三同学',
        roles: ['STUDENT'],
      },
    };

    const wrapper = mount(LandingPage);
    await flushPromises();

    expect(wrapper.text()).not.toContain('这一页验证了什么');
    expect(wrapper.text()).not.toContain('后端签发双 Token');
    expect(wrapper.text()).not.toContain('业务模块（题库 / 考试 / 判分 / 统计）在阶段 20–23 落地');
  });

  it('学生角色：欢迎卡渲染姓名与角色，常用入口展示我的考试与我的成绩，无教师/管理端入口', async () => {
    mockUserState.value = {
      user: {
        id: 10,
        username: 'student_zhang',
        name: '张三',
        roles: ['STUDENT'],
      },
    };

    const wrapper = mount(LandingPage);
    await flushPromises();

    // 欢迎信息
    expect(wrapper.text()).toContain('张三');
    expect(wrapper.text()).toContain('学生');

    // 学生常用入口
    expect(wrapper.text()).toContain('我的考试');
    expect(wrapper.text()).toContain('我的成绩与复核');

    // 绝不展示教师端/管理端专属入口
    expect(wrapper.text()).not.toContain('考试管理');
    expect(wrapper.text()).not.toContain('批改工作台');
    expect(wrapper.text()).not.toContain('邀请码管理');
  });

  it('教师角色：常用入口渲染考试/批改/组卷等入口，无学生端/管理端专属入口', async () => {
    mockUserState.value = {
      user: {
        id: 20,
        username: 'teacher_wang',
        name: '王老师',
        roles: ['TEACHER'],
      },
    };

    const wrapper = mount(LandingPage);
    await flushPromises();

    expect(wrapper.text()).toContain('王老师');
    expect(wrapper.text()).toContain('教师');

    expect(wrapper.text()).toContain('考试管理');
    expect(wrapper.text()).toContain('批改工作台');
    expect(wrapper.text()).toContain('组卷管理');
    expect(wrapper.text()).toContain('题库管理');

    expect(wrapper.text()).not.toContain('我的考试');
    expect(wrapper.text()).not.toContain('邀请码管理');
  });

  it('管理员角色：常用入口包含邀请码管理等管理入口', async () => {
    mockUserState.value = {
      user: {
        id: 1,
        username: 'admin',
        name: '系统管理员',
        roles: ['ADMIN'],
      },
    };

    const wrapper = mount(LandingPage);
    await flushPromises();

    expect(wrapper.text()).toContain('系统管理员');
    expect(wrapper.text()).toContain('管理员');

    expect(wrapper.text()).toContain('邀请码管理');
  });

  it('零新增数据请求：落地页纯消费 auth store，挂载期间无任何 API 契约调用', async () => {
    const apiSpies = Object.values(api).filter((fn) => typeof fn === 'function');
    for (const spy of apiSpies) {
      if (vi.isMockFunction(spy)) {
        expect(spy).not.toHaveBeenCalled();
      }
    }
  });

  it('词法护栏：源码彻底移除验证卡文案与清单，包含欢迎卡与常用入口语义', () => {
    expect(landingSource).not.toContain('这一页验证了什么');
    expect(landingSource).not.toContain('后端签发双 Token');
    expect(landingSource).toMatch(/欢迎/);
    expect(landingSource).toMatch(/入口/);
  });
});
