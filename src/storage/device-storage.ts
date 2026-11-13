import { Capacitor, registerPlugin } from '@capacitor/core';

export type DeviceStorageInfo = {
  totalBytes: number;
  freeBytes: number;
  /** Estimate of private app data, excluding caches and packaged app assets. */
  appBytes: number;
  /** True when the bounded scan skipped files or reached its limit. */
  appBytesPartial: boolean;
  libraryLocation: string;
};

const localFiles = registerPlugin<{
  getStorageInfo(): Promise<DeviceStorageInfo>;
  openAppStorageSettings(): Promise<void>;
  setReadingActive(options: { active: boolean }): Promise<void>;
}>('LocalFiles');

function available(): boolean {
  return Capacitor.getPlatform() === 'android' && Capacitor.isPluginAvailable('LocalFiles');
}

export async function getDeviceStorageInfo(): Promise<DeviceStorageInfo | undefined> {
  if (!available()) return undefined;
  try {
    return await localFiles.getStorageInfo();
  } catch (error) {
    // An older APK can have the backup bridge without these newer methods.
    const code = (error as { code?: unknown } | null)?.code;
    if (code === 'UNIMPLEMENTED' || code === 'UNAVAILABLE') return undefined;
    throw error;
  }
}

export async function openAppStorageSettings(): Promise<void> {
  if (!available()) throw new Error('Android app storage settings are not available on this device.');
  await localFiles.openAppStorageSettings();
}

let readingActive = false;
export function keepReadingScreenAwake(active: boolean): void {
  if (!available() || active === readingActive) return;
  readingActive = active;
  void localFiles.setReadingActive({ active }).catch(() => {
    // Older app shells can still read; they do not have the screen-awake bridge.
    readingActive = false;
  });
}
