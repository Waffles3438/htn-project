import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev: Vite serves the UI and proxies /api to the Python server (python server.py).
// The proxy rewrites Origin to match the Host it forwards so the server's same-origin
// guard still accepts requests made from the browser at localhost:5173.
// Build: static output in dist/, which server.py serves directly when present.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8000',
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            if (proxyReq.getHeader('origin')) {
              proxyReq.setHeader('origin', `http://${proxyReq.getHeader('host')}`)
            }
          })
        },
      },
    },
  },
})
