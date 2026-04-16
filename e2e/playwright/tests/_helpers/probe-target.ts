import { test } from '@playwright/test';

/**
 * Shared helper: probe a URL and skip the calling test/suite if it isn't
 * reachable. One-line replacement for repeating `beforeAll` + `test.skip()`
 * across every spec that depends on nginx (:8081), the observability
 * actuator (:9091), or any other infrastructure that isn't always present.
 *
 * CI's `e2e-tests.yml` job starts only Vite + backend + postgres. Specs
 * that need nginx-set headers, SSE buffering behavior, or actuator
 * metrics call this from a `beforeAll` (or inline at the top of an
 * individual test) so they cleanly skip rather than failing 3× with
 * `ECONNREFUSED`.
 *
 * The 2500ms timeout is generous on a cold CI runner (where dev-stack
 * containers are still warming up) but bounded so the probe doesn't
 * stretch the suite when the target is genuinely down.
 *
 * Usage:
 *   test.beforeAll(async () => {
 *     await requireReachable(BASE_URL, 'nginx (run dev-start.sh --nginx)');
 *   });
 */
export async function requireReachable(url: string, hint: string, timeoutMs = 2500): Promise<void> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  let reachable = false;
  try {
    const res = await fetch(url, { signal: controller.signal, method: 'GET' });
    reachable = res.status < 500;
  } catch {
    reachable = false;
  } finally {
    clearTimeout(timer);
  }
  test.skip(!reachable, `Skipping: ${url} not reachable. Requires ${hint}.`);
}
