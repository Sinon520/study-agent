import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue()],

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },

  server: {
    // 固定端口，方便 Nginx / 文档里写死地址
    port: 5173,
    host: '127.0.0.1',
    strictPort: true,
    proxy: {
      // 开发期用 Vite 代理解决跨域，生产环境交给 Nginx 做同样的转发。
      // 这样前端代码里的 baseURL 始终是 /api，两种环境都不用改。
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },

  build: {
    // 生产构建关掉 sourcemap 减小体积；排查线上问题时可临时打开
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        // 手动分包：Element Plus 与 ECharts 体积大，单独切出来有利于浏览器缓存。
        //
        // 注意：Vite 8 的底层打包器是 rolldown，它只接受**函数**形式的 manualChunks，
        // 传对象（webpack/Rollup 4 的写法）会直接报
        // "Invalid type: Expected Function but received Object" 并中断构建。
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('element-plus') || id.includes('@element-plus')) return 'element'
          if (id.includes('echarts') || id.includes('zrender')) return 'charts'
          if (id.includes('markdown-it') || id.includes('markdown-it-')) return 'markdown'
          return 'vendor'
        },
      },
    },
  },
})
