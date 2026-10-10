import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

/**
 * UniApp（Vue3 语法）构建配置。
 *
 * 说明：本工程是 **CLI 版 uni-app**（不依赖 HBuilderX），
 * 用 `pnpm dev:mp-weixin` / `pnpm build:mp-weixin` 产出
 * `dist/dev/mp-weixin` 与 `dist/build/mp-weixin`，
 * 再用微信开发者工具打开该目录即可预览。
 * 如果团队习惯 HBuilderX，这个 src 目录也能直接被 HBuilderX 识别为 uni-app 项目。
 */
export default defineConfig({
  plugins: [uni()],
})
