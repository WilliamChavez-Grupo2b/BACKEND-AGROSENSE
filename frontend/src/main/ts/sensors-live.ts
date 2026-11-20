// The home page asks every 30 seconds for the newest reading of each activated sensor and paints the ones that
// changed. Readings the backend reads from the ESP32 over TCP or USB never pass through the event service, so the live
// stream (events/live.ts) does not carry them; without this the list «Lecturas por sensor» would stay as it loaded.
import { isArrayOf, isRecord, requestJson } from './api/client.js';
import { all } from './core/dom.js';
import { newerReadings } from './device-status.js';
import { showReading } from './events/live.js';

interface LatestReading {
  sensorCode: string;
  value: number;
  unit: string | null;
  recordedAt: string | null;
}

const POLL_MS = 30_000;

const isNullableString = (value: unknown): value is string | null => value === null || typeof value === 'string';

const isLatestReading = (value: unknown): value is LatestReading =>
  isRecord(value) &&
  typeof value['sensorCode'] === 'string' &&
  typeof value['value'] === 'number' &&
  isNullableString(value['unit']) &&
  isNullableString(value['recordedAt']);

function start(): void {
  const section = document.querySelector<HTMLElement>('[data-sensors-live]');
  const url = section?.dataset['latestUrl'];
  if (!section || !url) return;
  const seen = new Map<string, number>();
  let first = true;
  let timer: number | undefined;

  const refresh = async (): Promise<void> => {
    if (document.visibilityState === 'visible') {
      try {
        const readings = await requestJson(url, isArrayOf(isLatestReading), { timeoutMs: 15_000 });
        const fresh = newerReadings(readings, seen);
        // The first answer only learns what the server already rendered; later ones paint what is new.
        if (!first) fresh.forEach((reading) => showReading(reading));
        first = false;
      } catch {
        // The list keeps what it shows; the next round tries again.
      }
    }
    timer = window.setTimeout(() => void refresh(), POLL_MS);
  };

  if (all('[data-sensor-code]', HTMLElement, section).length > 0) void refresh();
  window.addEventListener('pagehide', () => window.clearTimeout(timer));
}

start();
