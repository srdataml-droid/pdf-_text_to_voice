import { defineConfig } from 'vite';

export default defineConfig({
  base: './',
  publicDir: 'public',
  build: {
    target: 'es2022',
    assetsInlineLimit: 0,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('pdfjs-dist')) return 'pdf-reader';
          if (id.includes('epubjs')) return 'epub-reader';
          if (id.includes('kokoro-js')) return 'kokoro-engine';
          if (id.includes('piper-tts-web')) return 'piper-engine';
        },
      },
    },
  },
  optimizeDeps: {
    exclude: ['pdfjs-dist'],
  },
});
