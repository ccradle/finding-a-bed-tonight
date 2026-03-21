import { test as base } from '@playwright/test';

/**
 * Worker-to-shelter assignment fixture for parallel Playwright execution.
 * Each worker gets 3 dedicated shelters to avoid race conditions in mutation tests.
 * Shelter[9] (index 9) is reserved for creation tests.
 *
 * Assignment:
 *   Worker 0 → shelters[0..2]
 *   Worker 1 → shelters[3..5]
 *   Worker 2 → shelters[6..8]
 */
const SEED_SHELTER_UUIDS = [
  'd0000000-0000-0000-0000-000000000001',  // slot 0 — worker 0
  'd0000000-0000-0000-0000-000000000002',  // slot 1 — worker 0
  'd0000000-0000-0000-0000-000000000003',  // slot 2 — worker 0
  'd0000000-0000-0000-0000-000000000004',  // slot 3 — worker 1
  'd0000000-0000-0000-0000-000000000005',  // slot 4 — worker 1
  'd0000000-0000-0000-0000-000000000006',  // slot 5 — worker 1
  'd0000000-0000-0000-0000-000000000007',  // slot 6 — worker 2
  'd0000000-0000-0000-0000-000000000008',  // slot 7 — worker 2
  'd0000000-0000-0000-0000-000000000010',  // slot 8 — worker 2 (skip 009 = DV shelter)
];

export const test = base.extend<{
  workerShelter: (slot: 0 | 1 | 2) => string;
}>({
  workerShelter: async ({}, use, testInfo) => {
    const workerIndex = testInfo.workerIndex % 3;
    const baseSlot = workerIndex * 3;
    await use((slot: 0 | 1 | 2) => SEED_SHELTER_UUIDS[baseSlot + slot]);
  },
});

export { expect } from '@playwright/test';
