export type ZoomPoint = { x: number; y: number };

/** Document-only pinch/trackpad gestures; a single finger remains ordinary scrolling. */
export class ZoomGesture {
  private events = new AbortController();
  private distance = 0;
  private scale = 1;
  private factor = 1;
  private origin: ZoomPoint = { x: 0, y: 0 };
  private timer = 0;
  private suppressUntil = 0;

  constructor(surface: HTMLElement, private change: (factor: number, origin: ZoomPoint, finished: boolean) => void) {
    const options = { passive: false, signal: this.events.signal };
    const center = (touches: TouchList): ZoomPoint => ({ x: (touches[0].clientX + touches[1].clientX) / 2,
      y: (touches[0].clientY + touches[1].clientY) / 2 });
    const distance = (touches: TouchList) => Math.hypot(touches[0].clientX - touches[1].clientX, touches[0].clientY - touches[1].clientY);
    surface.addEventListener('touchstart', event => {
      if (event.touches.length !== 2) return;
      event.preventDefault(); this.distance = Math.max(1, distance(event.touches));
      this.factor = 1; this.origin = center(event.touches); this.change(1, this.origin, false);
    }, options);
    surface.addEventListener('touchmove', event => {
      if (!this.distance || event.touches.length !== 2) return;
      event.preventDefault(); this.factor = Math.max(.25, Math.min(4, distance(event.touches) / this.distance));
      this.change(this.factor, this.origin, false);
    }, options);
    const finish = () => {
      if (!this.distance) return;
      this.distance = 0; this.suppressUntil = performance.now() + 500;
      this.change(this.factor, this.origin, true);
    };
    surface.addEventListener('touchend', event => { if (event.touches.length < 2) finish(); }, options);
    surface.addEventListener('touchcancel', finish, options);
    surface.addEventListener('click', event => {
      if (performance.now() < this.suppressUntil) { event.preventDefault(); event.stopImmediatePropagation(); }
    }, { capture: true, signal: this.events.signal });
    surface.addEventListener('wheel', event => {
      if (!event.ctrlKey) return;
      event.preventDefault();
      if (!this.timer) { this.scale = 1; this.origin = { x: event.clientX, y: event.clientY }; this.change(1, this.origin, false); }
      this.scale = Math.max(.25, Math.min(4, this.scale * Math.exp(-event.deltaY * .005)));
      this.change(this.scale, this.origin, false);
      window.clearTimeout(this.timer);
      this.timer = window.setTimeout(() => { this.timer = 0; this.change(this.scale, this.origin, true); }, 160);
    }, options);
  }
  destroy(): void { this.events.abort(); window.clearTimeout(this.timer); }
}
