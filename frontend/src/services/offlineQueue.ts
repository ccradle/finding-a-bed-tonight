import { openDB, type DBSchema, type IDBPDatabase } from 'idb';
import { api, ApiError } from './api';

interface QueuedAction {
  id: string;
  type: string;
  url: string;
  method: 'GET' | 'POST' | 'PUT' | 'DELETE';
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
const DB_VERSION = 1;
const STORE_NAME = 'actions';

let dbPromise: Promise<IDBPDatabase<OfflineQueueDB>> | null = null;

function getDb(): Promise<IDBPDatabase<OfflineQueueDB>> {
  if (!dbPromise) {
    dbPromise = openDB<OfflineQueueDB>(DB_NAME, DB_VERSION, {
      upgrade(db) {
        const store = db.createObjectStore(STORE_NAME, { keyPath: 'id' });
        store.createIndex('by-timestamp', 'timestamp');
      },
    });
  }
  return dbPromise;
}

function generateId(): string {
  return `${Date.now()}-${Math.random().toString(36).substring(2, 9)}`;
}

export async function enqueueAction(
  type: string,
  url: string,
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  body?: unknown
): Promise<void> {
  const db = await getDb();
  const action: QueuedAction = {
    id: generateId(),
    type,
    url,
    method,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    timestamp: Date.now(),
  };
  await db.add(STORE_NAME, action);
}

export interface ReplayResult {
  succeeded: number;
  failed: number;
  conflicts: QueuedAction[];
}

export async function replayQueue(): Promise<ReplayResult> {
  const db = await getDb();
  const tx = db.transaction(STORE_NAME, 'readonly');
  const index = tx.store.index('by-timestamp');
  const actions = await index.getAll();
  await tx.done;

  const result: ReplayResult = {
    succeeded: 0,
    failed: 0,
    conflicts: [],
  };

  for (const action of actions) {
    try {
      const parsedBody = action.body ? JSON.parse(action.body) : undefined;

      switch (action.method) {
        case 'POST':
          await api.post(action.url, parsedBody);
          break;
        case 'PUT':
          await api.put(action.url, parsedBody);
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
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        result.conflicts.push(action);
        const deleteTx = db.transaction(STORE_NAME, 'readwrite');
        await deleteTx.store.delete(action.id);
        await deleteTx.done;
      } else {
        result.failed++;
      }
    }
  }

  return result;
}

export async function getQueueSize(): Promise<number> {
  const db = await getDb();
  return db.count(STORE_NAME);
}
