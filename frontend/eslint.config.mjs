import globals from 'globals';
import pluginJs from '@eslint/js';
import pluginVue from 'eslint-plugin-vue';
import tseslint from 'typescript-eslint';
import vueTsEslintConfig from '@vue/eslint-config-typescript';
import eslintConfigPrettier from 'eslint-config-prettier';
import prettierPlugin from 'eslint-plugin-prettier';

/**
 * @type {import('eslint').Linter.Config[]}
 *
 * ⚠️ flat config 是「后者覆盖前者」：任何按目录豁免规则的写法都必须放在数组末尾，
 * 否则会被后面的全局 rules 块重新打开。生成物一律走顶层 ignores（不解析、不格式化），
 * 与参考项目 crm-front 一致。
 */
export default [
  {
    ignores: [
      'dist/**',
      'coverage/**',
      'node_modules/**',
      'test-results/**',
      'playwright-report/**',
      // 契约生成产物：openapi.yaml 变更后由 pnpm gen:api 覆盖，不应人工修改，
      // 也不该为了让 formatter 满意去动它（diff 必须可追溯到上游）。
      'src/api/axios/**',
      '**/*.gen.ts',
      // unplugin-vue-router / vite 插件生成的声明文件
      'typed-router.d.ts',
      'auto-imports.d.ts',
      'components.d.ts',
    ],
  },
  { files: ['**/*.{js,mjs,cjs,ts,vue}'] },
  { languageOptions: { globals: { ...globals.browser, ...globals.node } } },
  pluginJs.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  ...vueTsEslintConfig(),
  eslintConfigPrettier,
  {
    plugins: {
      prettier: prettierPlugin,
    },
    rules: {
      'prettier/prettier': 'error',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/ban-ts-comment': [
        'error',
        {
          'ts-expect-error': 'allow-with-description',
          'ts-ignore': 'allow-with-description',
          'ts-nocheck': 'allow-with-description',
        },
      ],
      'no-var': 'error',
      'prefer-const': 'error',
      'no-console': ['warn', { allow: ['warn', 'error'] }],
      'vue/multi-word-component-names': 'off',
    },
  },
  // e2e 里跨进程读环境变量只能是 `process.env.X`（string | undefined），
  // 用例内用 expect(...).toBeTruthy() 先断言存在，因此放开 any 是务实选择。
  {
    files: ['e2e/**/*.ts'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
];
