/**
 * 角色守卫规则（三种角色 + 越权）。
 *
 * ⚠️ 这些用例锁的是「前端体验层的取舍」，不是安全边界（硬约定 4）：
 * 越权在这里被甩到 /403 只是省一次无谓渲染，真正的 403 由后端 @RequireRole 给出。
 * 用例刻意包含 fail-closed 分支——未登记路径默认谁都不许进，宁可多跳一次 403，
 * 也不能出现「新页面忘了登记 → 学生看得见教师页」这种乐观放行。
 */
import { describe, expect, it } from 'vitest';

import { highestRoleOf, isAuthenticatedOf, type RoleName } from '@/store';
import { allowedRolesFor, canAccess, decideNavigation, isPublicPath } from '../access';

const asInput = (
  path: string,
  loggedIn: boolean,
  role: RoleName | null,
  mustChangePassword = false
) => ({
  path,
  loggedIn,
  role,
  mustChangePassword,
});

describe('highestRoleOf / isAuthenticatedOf', () => {
  it('多角色取最高（ADMIN > TEACHER > STUDENT）', () => {
    expect(highestRoleOf({ id: 1, roles: ['STUDENT', 'ADMIN'] })).toBe('ADMIN');
    expect(highestRoleOf({ id: 1, roles: ['student', 'teacher'] })).toBe('TEACHER');
    expect(highestRoleOf({ id: 1, roles: ['STUDENT'] })).toBe('STUDENT');
  });

  it('无角色 / 未知角色 / 无用户：一律 null（fail-closed）', () => {
    expect(highestRoleOf(null)).toBeNull();
    expect(highestRoleOf({ id: 1 })).toBeNull();
    expect(highestRoleOf({ id: 1, roles: [] })).toBeNull();
    expect(highestRoleOf({ id: 1, roles: ['OBSERVER'] })).toBeNull();
  });

  it('已登录判据看后端资料里的 id，本地有令牌不算（硬约定 6）', () => {
    expect(isAuthenticatedOf({ id: 9, username: 't01' })).toBe(true);
    expect(isAuthenticatedOf({ username: 't01' })).toBe(false);
    expect(isAuthenticatedOf(null)).toBe(false);
  });
});

describe('canAccess', () => {
  it('公共页面：登录后任意角色可进，未登录不可进', () => {
    expect(canAccess('STUDENT', '/')).toBe(true);
    expect(canAccess('TEACHER', '/change-password')).toBe(true);
    expect(canAccess(null, '/')).toBe(false);
  });

  it('角色分区：ADMIN 通吃，教师/学生各进各的', () => {
    expect(canAccess('ADMIN', '/admin/users')).toBe(true);
    expect(canAccess('TEACHER', '/teacher/exams')).toBe(true);
    expect(canAccess('STUDENT', '/student/papers')).toBe(true);
    expect(canAccess('ADMIN', '/teacher/exams')).toBe(true);
    expect(canAccess('ADMIN', '/student/papers')).toBe(true);
  });

  it('越权与跨区访问全部拒绝', () => {
    expect(canAccess('STUDENT', '/admin/users')).toBe(false);
    expect(canAccess('STUDENT', '/teacher/exams')).toBe(false);
    expect(canAccess('TEACHER', '/admin/users')).toBe(false);
    expect(canAccess('TEACHER', '/student/papers')).toBe(false);
    expect(canAccess(null, '/admin/users')).toBe(false);
  });

  it('前缀匹配按路径段收口，/teacherx 不算 /teacher 分区', () => {
    expect(allowedRolesFor('/teacher/exams')).toEqual(['ADMIN', 'TEACHER']);
    expect(allowedRolesFor('/teacherx')).toEqual([]);
    expect(allowedRolesFor('/admin')).toEqual(['ADMIN']);
  });

  it('未登记路径不给任何角色（宁可 403 也不乐观放行）', () => {
    expect(allowedRolesFor('/exams/1')).toEqual([]);
    expect(canAccess('ADMIN', '/exams/1')).toBe(false);
  });
});

describe('decideNavigation', () => {
  it('公开页：未登录也直接允许', () => {
    for (const path of ['/login', '/register', '/forgot-password', '/403']) {
      expect(isPublicPath(path)).toBe(true);
      expect(decideNavigation(asInput(path, false, null))).toEqual({ action: 'allow' });
    }
  });

  it('已登录再访问登录页：回首页，避免登录态下重复登录', () => {
    expect(decideNavigation(asInput('/login', true, 'STUDENT'))).toEqual({
      action: 'redirect',
      to: '/',
    });
  });

  it('未登录访问受限页：跳登录并带上 redirect 回跳参数', () => {
    expect(decideNavigation(asInput('/change-password', false, null))).toEqual({
      action: 'redirect',
      to: '/login',
      query: { redirect: '/change-password' },
    });
    expect(decideNavigation(asInput('/admin/users', false, null))).toEqual({
      action: 'redirect',
      to: '/login',
      query: { redirect: '/admin/users' },
    });
  });

  it('学生登录后可进首页与修改密码', () => {
    expect(decideNavigation(asInput('/', true, 'STUDENT'))).toEqual({ action: 'allow' });
    expect(decideNavigation(asInput('/change-password', true, 'STUDENT'))).toEqual({
      action: 'allow',
    });
  });

  it('三种角色各自的分区页都放行', () => {
    expect(decideNavigation(asInput('/admin/users', true, 'ADMIN'))).toEqual({ action: 'allow' });
    expect(decideNavigation(asInput('/teacher/exams', true, 'TEACHER'))).toEqual({
      action: 'allow',
    });
    expect(decideNavigation(asInput('/student/papers', true, 'STUDENT'))).toEqual({
      action: 'allow',
    });
  });

  it('越权访问：留在站点内并跳 403，不静默重定向到别处', () => {
    expect(decideNavigation(asInput('/admin/users', true, 'STUDENT'))).toEqual({
      action: 'forbid',
      to: '/403',
    });
    expect(decideNavigation(asInput('/teacher/exams', true, 'STUDENT'))).toEqual({
      action: 'forbid',
      to: '/403',
    });
    expect(decideNavigation(asInput('/admin/users', true, 'TEACHER'))).toEqual({
      action: 'forbid',
      to: '/403',
    });
  });

  it('后端角色为空但已登录：受限页一律 403（不猜角色）', () => {
    expect(decideNavigation(asInput('/teacher/exams', true, null))).toEqual({
      action: 'forbid',
      to: '/403',
    });
  });

  it('404 页豁免角色判定：点错链接不该被甩去登录页', () => {
    expect(
      decideNavigation({ path: '/nope', loggedIn: false, role: null, notFound: true })
    ).toEqual({ action: 'allow' });
    // 同一条路径没有 notFound 标记时仍按 fail-closed 处理
    expect(decideNavigation(asInput('/nope', true, 'ADMIN')).action).toBe('forbid');
  });

  it('未登录：不受 mustChangePassword 影响，仍走登录重定向', () => {
    expect(decideNavigation(asInput('/admin/users', false, null, true))).toEqual({
      action: 'redirect',
      to: '/login',
      query: { redirect: '/admin/users' },
    });
  });

  it('必须改密：业务页被重定向到改密页，不渲染业务功能', () => {
    expect(decideNavigation(asInput('/admin/users', true, 'ADMIN', true))).toEqual({
      action: 'redirect',
      to: '/change-password',
    });
    expect(decideNavigation(asInput('/teacher/exams', true, 'TEACHER', true))).toEqual({
      action: 'redirect',
      to: '/change-password',
    });
    expect(decideNavigation(asInput('/student/papers', true, 'STUDENT', true))).toEqual({
      action: 'redirect',
      to: '/change-password',
    });
  });

  it('必须改密：仅放行改密页本身', () => {
    expect(decideNavigation(asInput('/change-password', true, 'ADMIN', true))).toEqual({
      action: 'allow',
    });
  });

  it('必须改密：公开页也被拦（登录态下不回 login/register），一律收口到改密页', () => {
    expect(decideNavigation(asInput('/login', true, 'ADMIN', true))).toEqual({
      action: 'redirect',
      to: '/change-password',
    });
    expect(decideNavigation(asInput('/register', true, 'ADMIN', true))).toEqual({
      action: 'redirect',
      to: '/change-password',
    });
  });

  it('mustChangePassword=false：读到 false 不误拦，正常授权放行', () => {
    expect(decideNavigation(asInput('/admin/users', true, 'ADMIN', false))).toEqual({
      action: 'allow',
    });
    expect(decideNavigation(asInput('/student/papers', true, 'STUDENT', false))).toEqual({
      action: 'allow',
    });
  });
});
