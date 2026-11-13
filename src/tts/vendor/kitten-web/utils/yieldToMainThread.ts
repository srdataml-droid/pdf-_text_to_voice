/**
 * Give browser UI work a chance to paint before continuing expensive setup or
 * synthesis steps. In Node.js this is just a short event-loop yield.
 */
export async function yieldToMainThread(): Promise<void> {
  const scheduler = (globalThis as {
    scheduler?: { yield?: () => Promise<void> };
  }).scheduler;

  if (typeof scheduler?.yield === 'function') {
    await scheduler.yield();
    return;
  }

  const requestFrame = (globalThis as {
    requestAnimationFrame?: typeof requestAnimationFrame;
  }).requestAnimationFrame;

  if (typeof requestFrame === 'function') {
    await new Promise<void>((resolve) => {
      requestFrame(() => {
        setTimeout(resolve, 0);
      });
    });
    return;
  }

  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}
