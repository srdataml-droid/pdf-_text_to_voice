import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.novaxis.localreader',
  appName: 'Novaxis Reader',
  webDir: 'dist',
  android: {
    allowMixedContent: false,
  },
};

export default config;
