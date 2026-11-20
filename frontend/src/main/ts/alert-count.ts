// Every signed-in page asks for the number of open alerts every 30 seconds and paints the bell and the menu badge.
// Alerts now close by themselves when a reading is back in range, and readings from the ESP32 over TCP or USB never
// pass through the event service, so without this the count would only change on reload.
import { isRecord, requestJson } from './api/client.js';

const POLL_MS = 30_000;

interface OpenCount {
  count: number;
}

const isOpenCount = (value: unknown): value is OpenCount => isRecord(value) && typeof value['count'] === 'number';

/** The label the bell and the badges read out; also used by the tests. */
export function alertCountLabel(count: number): string {
  if (count <= 0) return 'Sin alertas pendientes';
  return count === 1 ? '1 alerta sin atender' : `${count} alertas sin atender`;
}

export function paintAlertCount(count: number): void {
  const label = alertCountLabel(count);
  for (const badge of document.querySelectorAll<HTMLElement>('[data-alert-count]')) {
    badge.textContent = String(count);
    badge.hidden = count <= 0;
    badge.setAttribute('aria-label', label);
  }
  const bell = document.getElementById('topbarAlerts');
  if (!bell) return;
  bell.setAttribute('aria-label', label);
  const dot = bell.querySelector<HTMLElement>('.topbar-dot');
  if (dot) dot.hidden = count <= 0;
}

export function startAlertCount(): void {
  const url = document.getElementById('topbarAlerts')?.dataset['countUrl'];
  if (!url) return;
  let timer: number | undefined;
  const refresh = async (): Promise<void> => {
    if (document.visibilityState === 'visible') {
      try {
        paintAlertCount((await requestJson(url, isOpenCount, { timeoutMs: 15_000 })).count);
      } catch {
        // The bell keeps what it shows; the next round tries again.
      }
    }
    timer = window.setTimeout(() => void refresh(), POLL_MS);
  };
  timer = window.setTimeout(() => void refresh(), POLL_MS);
  window.addEventListener('pagehide', () => window.clearTimeout(timer));
}
