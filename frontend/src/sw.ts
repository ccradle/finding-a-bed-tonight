/// <reference lib="webworker" />
import { precacheAndRoute } from 'workbox-precaching';
import { registerRoute } from 'workbox-routing';
import { NetworkFirst } from 'workbox-strategies';

declare let self: ServiceWorkerGlobalScope;

// Precache static assets (injected by vite-plugin-pwa)
precacheAndRoute(self.__WB_MANIFEST);

// Runtime cache: GET /api/v1/* — NetworkFirst with 5-second timeout
// Offline resilience for mutations (POST/PATCH) is handled entirely by the
// app-level IndexedDB queue (offlineQueue.ts), not by the service worker.
// This avoids dual-queue deduplication complexity and keeps offline behavior
// fully testable in Playwright. See design.md "Architecture decision: single queue".
registerRoute(
  ({ url, request }) =>
    url.pathname.startsWith('/api/v1/') && request.method === 'GET',
  new NetworkFirst({
    cacheName: 'api-cache',
    networkTimeoutSeconds: 5,
    plugins: [
      {
        cacheWillUpdate: async ({ response }) => {
          // Only cache successful responses
          return response && response.status === 200 ? response : null;
        },
      },
    ],
  })
);
