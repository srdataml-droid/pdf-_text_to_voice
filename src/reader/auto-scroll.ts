/** One small animation loop, suspended on interaction, hidden views, and dialogs. */
export class AutoScroll {
  private frame = 0;
  private last = 0;
  private fraction = 0;
  private edgeTime = 0;
  private advancing = false;
  private generation = 0;
  active = false;
  speed = 18;

  constructor(private surface: () => HTMLElement | null, private advance: () => Promise<boolean>,
    private changed: (active: boolean) => void) {}

  start(): void {
    if (this.active) return;
    this.active = true; this.generation++; this.last = 0; this.fraction = 0; this.edgeTime = 0;
    this.changed(true); this.frame = requestAnimationFrame(time => this.tick(time));
  }
  stop(): void {
    if (!this.active) return;
    this.active = false; this.generation++; cancelAnimationFrame(this.frame); this.changed(false);
  }
  private tick(time: number): void {
    if (!this.active) return;
    const surface = this.surface();
    if (!surface || document.hidden || document.querySelector('dialog[open]')) { this.stop(); return; }
    const seconds = this.last ? Math.min(.1, (time - this.last) / 1000) : 0;
    this.last = time;
    if (!this.advancing) {
      const maximum = Math.max(0, surface.scrollHeight - surface.clientHeight);
      if (surface.scrollTop < maximum - 1) {
        this.edgeTime = 0; this.fraction += seconds * this.speed;
        const movement = Math.floor(this.fraction); this.fraction -= movement;
        surface.scrollTop += movement;
      } else {
        this.edgeTime += seconds;
        // A whole page that already fits needs reading time before advancing.
        const dwell = maximum < 2 ? Math.max(2, surface.clientHeight / this.speed) : 2;
        if (this.edgeTime >= dwell) {
          this.advancing = true; const generation = this.generation;
          void this.advance().then(more => { if (generation === this.generation && !more) this.stop(); })
            .catch(() => { if (generation === this.generation) this.stop(); })
            .finally(() => { this.advancing = false; this.edgeTime = 0; this.last = 0; });
        }
      }
    }
    this.frame = requestAnimationFrame(next => this.tick(next));
  }
}
