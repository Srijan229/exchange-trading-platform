import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: { proxy: {
    '/api': { target: 'http://localhost:8080', changeOrigin: true },
    '/demo-api': { target: 'http://localhost:8081', changeOrigin: true, rewrite: path => path.replace(/^\/demo-api/, '/api') },
  } },
});
