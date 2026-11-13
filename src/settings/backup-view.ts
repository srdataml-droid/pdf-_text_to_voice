import { Capacitor, registerPlugin } from '@capacitor/core';
import { createLibraryBackup, restoreLibraryBackup } from '../storage/library-backup';

const localFiles = registerPlugin<{ saveBackup(options: { data: string }): Promise<void> }>('LocalFiles');

export async function downloadBackup(): Promise<void> {
  const blob = await createLibraryBackup();
  if (Capacitor.getPlatform() === 'android') {
    const data = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result).split(',')[1]);
      reader.onerror = () => reject(new Error('Could not prepare the library backup.'));
      reader.readAsDataURL(blob);
    });
    await localFiles.saveBackup({ data });
    return;
  }
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'novaxis-reader-backup.zip';
  document.body.append(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export async function restoreBackup(file: File): Promise<number> {
  return restoreLibraryBackup(file);
}
