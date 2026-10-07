export type PlaybackActions = {
  toggle: () => void;
  previous: () => void;
  next: () => void;
  speed: (value: number) => void;
};

export function bindPlaybackControls(actions: PlaybackActions): void {
  document.querySelector<HTMLButtonElement>('#play')?.addEventListener('click', actions.toggle);
  document.querySelector<HTMLButtonElement>('#previous')?.addEventListener('click', actions.previous);
  document.querySelector<HTMLButtonElement>('#next')?.addEventListener('click', actions.next);
  document.querySelector<HTMLInputElement>('#speed')?.addEventListener('input', (event) => {
    actions.speed(Number((event.currentTarget as HTMLInputElement).value));
  });
}
