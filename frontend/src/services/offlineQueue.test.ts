import { describe, it, expect, beforeEach, vi } from 'vitest';
import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';

// We need to reset the global indexedDB for each test
// to avoid cross-test DB state leaking
let offlineQueue: typeof import('./offlineQueue');

async function loadModule() {
  // Reset indexedDB globally so each test gets a fresh DB
  globalThis.indexedDB = new IDBFactory();
  // Re-import the module fresh (bypass vi cache)
  vi.resetModules();
  offlineQueue = await import('./offlineQueue');
  offlineQueue._resetForTesting();
}

// Mock the api module
const mockPost = vi.fn().mockResolvedValue({});
const mockPatch = vi.fn().mockResolvedValue({});

class MockApiError extends Error {
  status: number;
  error: string;
  constructor(response: { status: number; error: string; message: string }) {
    super(response.message);
    this.name = 'ApiError';
    this.status = response.status;
    this.error = response.error;
  }
}

vi.mock('./api', () => ({
  api: {
    post: mockPost,
    put: vi.fn().mockResolvedValue({}),
    patch: mockPatch,
    delete: vi.fn().mockResolvedValue({}),
    get: vi.fn().mockResolvedValue({}),
  },
  ApiError: MockApiError,
}));

beforeEach(async () => {
  mockPost.mockReset().mockResolvedValue({});
  mockPatch.mockReset().mockResolvedValue({});
  await loadModule();
});

describe('enqueueAction', () => {
  it('enqueues an action and returns an idempotency key', async () => {
    const key = await offlineQueue.enqueueAction('HOLD_BED', '/api/v1/reservations', 'POST', { shelterId: '123' });

    expect(key).toBeTruthy();
    expect(typeof key).toBe('string');
    expect(key.length).toBe(36); // UUID format

    const size = await offlineQueue.getQueueSize();
    expect(size).toBe(1);
  });

  it('generates unique idempotency keys for each action', async () => {
    const key1 = await offlineQueue.enqueueAction('HOLD_BED', '/api/v1/reservations', 'POST', {});
    const key2 = await offlineQueue.enqueueAction('HOLD_BED', '/api/v1/reservations', 'POST', {});

    expect(key1).not.toBe(key2);
  });

  it('stores actions retrievable in timestamp order', async () => {
    await offlineQueue.enqueueAction('UPDATE_AVAILABILITY', '/api/v1/shelters/1/availability', 'PATCH', { bedsOccupied: 5 });
    // Small delay to ensure different timestamps
    await new Promise(r => setTimeout(r, 5));
    await offlineQueue.enqueueAction('HOLD_BED', '/api/v1/reservations', 'POST', { shelterId: '1' });

    const actions = await offlineQueue.getQueuedActions();
    expect(actions.length).toBe(2);
    expect(actions[0].type).toBe('UPDATE_AVAILABILITY');
    expect(actions[1].type).toBe('HOLD_BED');
    expect(actions[0].timestamp).toBeLessThan(actions[1].timestamp);
  });

  it('serializes body to JSON string', async () => {
    await offlineQueue.enqueueAction('HOLD_BED', '/api/v1/reservations', 'POST', { shelterId: 'abc', populationType: 'VETERAN' });

    const actions = await offlineQueue.getQueuedActions();
    expect(actions[0].body).toBe('{"shelterId":"abc","populationType":"VETERAN"}');
  });
});

describe('replayQueue', () => {
  it('replays actions and removes them from queue on success', async () => {
    await offlineQueue.enqueueAction('HOLD_BED', '/api/v1/reservations', 'POST', { shelterId: '1' });
    await offlineQueue.enqueueAction('UPDATE_AVAILABILITY', '/api/v1/shelters/1/availability', 'PATCH', { bedsOccupied: 5 });

    const result = await offlineQueue.replayQueue();

    expect(result.succeeded).toBe(2);
    expect(result.failed).toBe(0);
    expect(result.succeededActions.length).toBe(2);
    expect(await offlineQueue.getQueueSize()).toBe(0);
  });

  it('includes X-Idempotency-Key header when replaying', async () => {
    const key = await offlineQueue.enqueueAction('HOLD_BED', '/api/v1/reservations', 'POST', {});

    await offlineQueue.replayQueue();

    expect(mockPost).toHaveBeenCalledWith(
      '/api/v1/reservations',
      {},
      { headers: { 'X-Idempotency-Key': key } }
    );
  });

  it('skips expired HOLD_BED actions', async () => {
    await offlineQueue.enqueueAction('HOLD_BED', '/api/v1/reservations', 'POST', {});

    // Small delay so the action's timestamp is at least 2ms old
    await new Promise(r => setTimeout(r, 5));

    // holdDuration=1ms, buffer=0 means anything older than 1ms is expired
    const result = await offlineQueue.replayQueue(1, 0);

    expect(result.expired.length).toBe(1);
    expect(result.succeeded).toBe(0);
    expect(mockPost).not.toHaveBeenCalled();
    expect(await offlineQueue.getQueueSize()).toBe(0);
  });

  it('replays HOLD_BED that is NOT yet expired', async () => {
    await offlineQueue.enqueueAction('HOLD_BED', '/api/v1/reservations', 'POST', {});

    // holdDuration=2hrs, buffer=5min — action just created, well within window
    const result = await offlineQueue.replayQueue(2 * 60 * 60 * 1000, 5 * 60 * 1000);

    expect(result.succeeded).toBe(1);
    expect(result.expired.length).toBe(0);
  });

  it('never expires UPDATE_AVAILABILITY actions', async () => {
    await offlineQueue.enqueueAction('UPDATE_AVAILABILITY', '/api/v1/shelters/1/availability', 'PATCH', {});

    // Even with holdDuration=0, availability updates should still replay
    const result = await offlineQueue.replayQueue(0, 0);

    expect(result.succeeded).toBe(1);
    expect(result.expired.length).toBe(0);
  });

  it('handles 409 conflict — removes action and adds to conflicts', async () => {
    mockPost.mockRejectedValueOnce(new MockApiError({ status: 409, error: 'Conflict', message: 'No beds' }));

    await offlineQueue.enqueueAction('HOLD_BED', '/api/v1/reservations', 'POST', {});

    const result = await offlineQueue.replayQueue();

    expect(result.conflicts.length).toBe(1);
    expect(result.succeeded).toBe(0);
    expect(result.failed).toBe(0);
    expect(await offlineQueue.getQueueSize()).toBe(0); // removed from queue
  });

  it('handles non-409 error — action remains in queue for retry', async () => {
    mockPost.mockRejectedValueOnce(new Error('Network error'));

    await offlineQueue.enqueueAction('HOLD_BED', '/api/v1/reservations', 'POST', {});

    const result = await offlineQueue.replayQueue();

    expect(result.failed).toBe(1);
    expect(result.failedActions.length).toBe(1);
    expect(result.succeeded).toBe(0);
    expect(await offlineQueue.getQueueSize()).toBe(1); // still in queue
  });

  it('concurrent replay guard — second call returns empty result', async () => {
    // Make the first replay slow
    mockPost.mockImplementation(() => new Promise(r => setTimeout(() => r({}), 100)));

    await offlineQueue.enqueueAction('HOLD_BED', '/api/v1/reservations', 'POST', {});

    // Start two replays concurrently
    const [result1, result2] = await Promise.all([
      offlineQueue.replayQueue(),
      offlineQueue.replayQueue(),
    ]);

    // One should succeed, one should be empty (guard)
    const totalSucceeded = result1.succeeded + result2.succeeded;
    expect(totalSucceeded).toBe(1);

    // The guarded one has all zeros
    const guarded = result1.succeeded === 0 ? result1 : result2;
    expect(guarded.succeeded).toBe(0);
    expect(guarded.failed).toBe(0);
    expect(guarded.expired.length).toBe(0);
    expect(guarded.conflicts.length).toBe(0);
  });

  it('isReplaying returns correct state', async () => {
    expect(offlineQueue.isReplaying()).toBe(false);

    mockPost.mockImplementation(() => new Promise(r => setTimeout(() => r({}), 50)));
    await offlineQueue.enqueueAction('HOLD_BED', '/api/v1/reservations', 'POST', {});

    const replayPromise = offlineQueue.replayQueue();
    // Can't reliably test mid-flight state without more complex setup,
    // but after completion it should be false
    await replayPromise;
    expect(offlineQueue.isReplaying()).toBe(false);
  });
});

describe('getQueueSize', () => {
  it('returns 0 for empty queue', async () => {
    expect(await offlineQueue.getQueueSize()).toBe(0);
  });

  it('returns correct count after enqueuing', async () => {
    await offlineQueue.enqueueAction('HOLD_BED', '/api/v1/reservations', 'POST', {});
    await offlineQueue.enqueueAction('HOLD_BED', '/api/v1/reservations', 'POST', {});
    await offlineQueue.enqueueAction('UPDATE_AVAILABILITY', '/api/v1/shelters/1/availability', 'PATCH', {});

    expect(await offlineQueue.getQueueSize()).toBe(3);
  });
});
