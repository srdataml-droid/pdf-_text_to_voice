import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.novaxis.localreader',
  appName: 'Novaxis Reader',
  webDir: 'dist',
  // Native narration plans contain book text; never log bridge arguments.
  loggingBehavior: 'none',
  android: {
    allowMixedContent: false,
  },
};

export default config;
