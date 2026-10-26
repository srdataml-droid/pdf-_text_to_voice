export type PlaybackActions = {
  toggle: () => void;
  previous: () => void;
  next: () => void;
  speed: (value: number) => void;
};

export function bindPlaybackControls(actions: PlaybackActions): void {
  for (const [selector, delta] of [['#speed-down', -.25], ['#speed-up', .25]] as const) {
    document.querySelector(selector)?.addEventListener('click', () => {
      const value = normalizedSpeed(Number(document.querySelector<HTMLInputElement>('#speed')?.value ?? 1) + delta);
      paintSpeed(value); actions.speed(value);
    });
  }
  document.querySelector<HTMLButtonElement>('#play')?.addEventListener('click', actions.toggle);
  document.querySelector<HTMLButtonElement>('#previous')?.addEventListener('click', actions.previous);
  document.querySelector<HTMLButtonElement>('#next')?.addEventListener('click', actions.next);
  document.querySelector<HTMLInputElement>('#speed')?.addEventListener('input', (event) => {
    const value = normalizedSpeed(Number((event.currentTarget as HTMLInputElement).value));
    paintSpeed(value); actions.speed(value);
  });
}

export function normalizedSpeed(value: number): number {
  return Math.max(.75, Math.min(2, Math.round((Number.isFinite(value) ? value : 1) * 4) / 4));
}
export function paintSpeed(value: number): void {
  const speed = normalizedSpeed(value);
  const input = document.querySelector<HTMLInputElement>('#speed');
  if (input) input.value = String(speed);
  const label = document.querySelector('#speed-value');
  if (label) label.textContent = speed.toFixed(2) + '×';
  const down = document.querySelector<HTMLButtonElement>('#speed-down');
  const up = document.querySelector<HTMLButtonElement>('#speed-up');
  if (down) down.disabled = speed <= .75;
  if (up) up.disabled = speed >= 2;
}
