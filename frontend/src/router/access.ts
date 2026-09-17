/**
 * 前端访问控制规则（纯函数，便于 vitest 直接单测）。
 *
 * ⚠️⚠️ 取舍声明（硬约定 4）：**前端守卫不是安全边界**。
 * 这里的所有判断只是为了「不给用户看不该看的菜单/页面」的体验问题，
 * 真正的授权由后端 `@RequireRole` 注解与 `assertTeacherOwns*` 系列断言裁决。
 * 任何人绕过前端直接调 `/api/**`，仍会被后端 401/403 拒绝。
 * 因此本文件**禁止**出现「前端认为可以操作就放行」的乐观逻辑，
 * 也禁止在这里做「本地记住角色」的缓存——角色一律来自后端 `/api/auth/me`。
 */
import type { RoleName } from '@/store';

/** 公开页面（认证四页中除「修改密码」外的三页 + 403）。 */
export const PUBLIC_PATHS: readonly string[] = ['/login', '/register', '/forgot-password', '/403'];

/** 登录后任何角色都能进的页面。 */
const COMMON_PATHS: readonly string[] = ['/', '/change-password'];

/**
 * 按前缀预留的角色分区。本阶段（骨架）还没有业务页面，
 * 这三条规则是阶段 20–23 页面的落位约定，同时让「越权 → 403」可被单测覆盖。
 */
const PREFIX_RULES: ReadonlyArray<{ prefix: string; roles: readonly RoleName[] }> = [
  { prefix: '/admin', roles: ['ADMIN'] },
  { prefix: '/teacher', roles: ['ADMIN', 'TEACHER'] },
  { prefix: '/student', roles: ['ADMIN', 'STUDENT'] },
];

export function isPublicPath(path: string): boolean {
  return PUBLIC_PATHS.includes(path);
}

/** 路径允许哪些角色进入；返回 null 表示「登录后任意角色皆可」。 */
export function allowedRolesFor(path: string): readonly RoleName[] | null {
  for (const rule of PREFIX_RULES) {
    if (path === rule.prefix || path.startsWith(`${rule.prefix}/`)) {
      return rule.roles;
    }
  }
  return COMMON_PATHS.includes(path) ? null : [];
}

export function canAccess(role: RoleName | null, path: string): boolean {
  const roles = allowedRolesFor(path);
  if (roles === null) return role !== null;
  if (role === null) return false;
  return roles.includes(role);
}

/** 守卫可能跳转到的目标（字面量联合，配合 unplugin-vue-router 的类型化路由）。 */
export type KnownPath =
  '/' | '/login' | '/register' | '/forgot-password' | '/change-password' | '/403';

export type NavigationDecision =
  | { action: 'allow' }
  | { action: 'redirect'; to: KnownPath; query?: Record<string, string> }
  | { action: 'forbid'; to: KnownPath };

export interface NavigationInput {
  path: string;
  /** 后端 /api/auth/me 是否确认过登录态（调用前必须已解析，见 guard.ts）。 */
  loggedIn: boolean;
  /** 后端返回的最高角色；无角色时为 null（fail-closed，不给进任何受限页）。 */
  role: RoleName | null;
  /**
   * 命中了 catch-all 的 404 页。404 页本身不渲染任何业务数据，
   * 所以不参与角色判定——否则未登录用户访问错误链接会被甩到登录页，体验很差。
   */
  notFound?: boolean;
}

/**
 * 守卫决策入口——**唯一**的收敛点。
 *
 * 后续 `add-auth-must-change-password` 立项后要加「强制改密前置」，
 * 只需在这里插一条规则（以及在 pages 里加一个页面），不必改 guard.ts 的调用形状。
 */
export function decideNavigation(input: NavigationInput): NavigationDecision {
  const { path, loggedIn, role } = input;

  if (input.notFound) {
    return { action: 'allow' };
  }

  if (path === '/login' && loggedIn) {
    return { action: 'redirect', to: '/' };
  }

  if (isPublicPath(path)) {
    return { action: 'allow' };
  }

  if (!loggedIn) {
    return { action: 'redirect', to: '/login', query: { redirect: path } };
  }

  if (!canAccess(role, path)) {
    return { action: 'forbid', to: '/403' };
  }

  return { action: 'allow' };
}
