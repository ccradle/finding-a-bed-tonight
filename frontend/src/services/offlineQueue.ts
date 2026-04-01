import { openDB, type DBSchema, type IDBPDatabase } from 'idb';
import { api, ApiError } from './api';

interface QueuedAction {
  id: string;
  idempotencyKey: string;
  type: string;
  url: string;
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: string;
  timestamp: number;
}

interface OfflineQueueDB extends DBSchema {
  actions: {
    key: string;
    value: QueuedAction;
    indexes: { 'by-timestamp': number };
  };
}

const DB_NAME = 'fabt-offline-queue';
const DB_VERSION = 2; // Bumped for idempotencyKey field
const STORE_NAME = 'actions';

let dbPromise: Promise<IDBPDatabase<OfflineQueueDB>> | null = null;

function getDb(): Promise<IDBPDatabase<OfflineQueueDB>> {
  if (!dbPromise) {
    dbPromise = openDB<OfflineQueueDB>(DB_NAME, DB_VERSION, {
      upgrade(db, oldVersion) {
        if (oldVersion < 1) {
          const store = db.createObjectStore(STORE_NAME, { keyPath: 'id' });
          store.createIndex('by-timestamp', 'timestamp');
        }
        // V2: idempotencyKey added to the record — no schema change needed (field on value)
      },
    });
  }
  return dbPromise;
}

function generateId(): string {
  return `${Date.now()}-${Math.random().toString(36).substring(2, 9)}`;
}

function generateIdempotencyKey(): string {
  return crypto.randomUUID();
}

export async function enqueueAction(
  type: string,
  url: string,
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  body?: unknown
): Promise<string> {
  const db = await getDb();
  const idempotencyKey = generateIdempotencyKey();
  const action: QueuedAction = {
    id: generateId(),
    idempotencyKey,
    type,
    url,
    method,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    timestamp: Date.now(),
  };
  await db.add(STORE_NAME, action);
  return idempotencyKey;
}

export interface ReplayResult {
  succeeded: number;
  failed: number;
  succeededActions: QueuedAction[];
  expired: QueuedAction[];
  conflicts: QueuedAction[];
  failedActions: QueuedAction[];
}

let replaying = false;

/**
 * Replay queued actions. Hold actions older than (holdDurationMs - bufferMs) are skipped as expired.
 * Includes a concurrent replay guard — if called while already running, returns early with empty result.
 * @param holdDurationMs tenant hold duration in ms (default 90 minutes)
 * @param bufferMs buffer before expiry to skip (default 5 minutes)
 */
export async function replayQueue(
  holdDurationMs: number = 90 * 60 * 1000,
  bufferMs: number = 5 * 60 * 1000
): Promise<ReplayResult> {
  if (replaying) {
    return { succeeded: 0, failed: 0, succeededActions: [], expired: [], conflicts: [], failedActions: [] };
  }
  replaying = true;
  try {
    return await _replayQueueImpl(holdDurationMs, bufferMs);
  } finally {
    replaying = false;
  }
}

export function isReplaying(): boolean {
  return replaying;
}

async function _replayQueueImpl(
  holdDurationMs: number,
  bufferMs: number
): Promise<ReplayResult> {
  const db = await getDb();
  const tx = db.transaction(STORE_NAME, 'readonly');
  const index = tx.store.index('by-timestamp');
  const actions = await index.getAll();
  await tx.done;

  const result: ReplayResult = {
    succeeded: 0,
    failed: 0,
    succeededActions: [],
    expired: [],
    conflicts: [],
    failedActions: [],
  };

  const now = Date.now();

  for (const action of actions) {
    // Hold expiry check: skip HOLD_BED actions older than (holdDuration - buffer)
    if (action.type === 'HOLD_BED' && (now - action.timestamp > holdDurationMs - bufferMs)) {
      result.expired.push(action);
      const deleteTx = db.transaction(STORE_NAME, 'readwrite');
      await deleteTx.store.delete(action.id);
      await deleteTx.done;
      continue;
    }

    try {
      const parsedBody = action.body ? JSON.parse(action.body) : undefined;
      const headers: Record<string, string> = {};

      // Include idempotency key for deduplication
      if (action.idempotencyKey) {
        headers['X-Idempotency-Key'] = action.idempotencyKey;
      }

      switch (action.method) {
        case 'POST':
          await api.post(action.url, parsedBody, { headers });
          break;
        case 'PUT':
          await api.put(action.url, parsedBody, headers);
          break;
        case 'PATCH':
          await api.patch(action.url, parsedBody, headers);
          break;
        case 'DELETE':
          await api.delete(action.url);
          break;
        default:
          await api.get(action.url);
      }

      const deleteTx = db.transaction(STORE_NAME, 'readwrite');
      await deleteTx.store.delete(action.id);
      await deleteTx.done;
      result.succeeded++;
      result.succeededActions.push(action);
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        result.conflicts.push(action);
        const deleteTx = db.transaction(STORE_NAME, 'readwrite');
        await deleteTx.store.delete(action.id);
        await deleteTx.done;
      } else {
        result.failed++;
        result.failedActions.push(action);
      }
    }
  }

  return result;
}

export async function getQueueSize(): Promise<number> {
  const db = await getDb();
  return db.count(STORE_NAME);
}

export async function getQueuedActions(): Promise<QueuedAction[]> {
  const db = await getDb();
  const tx = db.transaction(STORE_NAME, 'readonly');
  const index = tx.store.index('by-timestamp');
  const actions = await index.getAll();
  await tx.done;
  return actions;
}

/** Reset module state — for testing only. */
export function _resetForTesting(): void {
  dbPromise = null;
  replaying = false;
}

export type { QueuedAction };
