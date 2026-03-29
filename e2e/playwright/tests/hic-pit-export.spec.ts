import { test, expect } from '../fixtures/auth.fixture';

/**
 * Riley's HIC/PIT export E2E tests.
 *
 * "Can Marcus actually click the download button, get a file, and is it the right file?"
 *
 * The download buttons use authenticated fetch + blob (not <a href>),
 * so the JWT token is sent with the request. Playwright intercepts
 * the blob download via the 'download' event.
 */

test.describe('HIC/PIT Export E2E', () => {

  test('click Download HIC CSV — file has correct HUD columns and integer codes', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Analytics$/ }).first().click();
    await adminPage.waitForTimeout(2000);

    // Start listening for download BEFORE clicking
    const downloadPromise = adminPage.waitForEvent('download');

    // Click the actual download button (now a <button> with fetch+blob)
    await adminPage.locator('[data-testid="download-hic-btn"]').click();

    // Wait for the browser to receive the file
    const download = await downloadPromise;

    // Verify filename matches pattern
    expect(download.suggestedFilename()).toMatch(/^hic-.*\.csv$/);

    // Read the downloaded content via stream
    const stream = await download.createReadStream();
    expect(stream).toBeTruthy();
    const chunks: Buffer[] = [];
    for await (const chunk of stream!) chunks.push(chunk as Buffer);
    const csv = Buffer.concat(chunks).toString('utf-8');

    // --- Validate HUD Inventory.csv header ---
    const lines = csv.split('\n').filter(l => l.trim().length > 0);
    expect(lines.length).toBeGreaterThan(0);

    const header = lines[0];
    expect(header).toContain('InventoryID');
    expect(header).toContain('ProjectID');
    expect(header).toContain('CoCCode');
    expect(header).toContain('HouseholdType');
    expect(header).toContain('Availability');
    expect(header).toContain('BedInventory');
    expect(header).toContain('UnitInventory');
    expect(header).toContain('VetBedInventory');
    expect(header).toContain('OtherBedInventory');
    expect(header).toContain('ESBedType');
    expect(header).toContain('InventoryStartDate');

    // --- Verify data rows exist ---
    expect(lines.length).toBeGreaterThan(1);

    // --- Verify NO string household types leaked through ---
    expect(csv).not.toContain('"Families"');
    expect(csv).not.toContain('"Adults Only"');
    expect(csv).not.toContain('"Veterans"');
    expect(csv).not.toContain('"Children Only"');

    // --- Verify column count is consistent ---
    const headerColCount = header.split(',').length;
    for (let i = 1; i < lines.length; i++) {
      const rowColCount = lines[i].split(',').length;
      expect(rowColCount).toBe(headerColCount);
    }

    // --- Verify HouseholdType uses HUD integers ---
    const headers = header.split(',');
    const hhIndex = headers.indexOf('HouseholdType');
    for (let i = 1; i < lines.length; i++) {
      const cols = lines[i].split(',');
      const hhType = cols[hhIndex].trim();
      expect(['1', '3', '4']).toContain(hhType);
    }
  });

  test('click Download PIT CSV — file has integer codes and correct structure', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Analytics$/ }).first().click();
    await adminPage.waitForTimeout(2000);

    const downloadPromise = adminPage.waitForEvent('download');
    await adminPage.locator('[data-testid="download-pit-btn"]').click();
    const download = await downloadPromise;

    // Verify filename
    expect(download.suggestedFilename()).toMatch(/^pit-.*\.csv$/);

    // Read content
    const stream = await download.createReadStream();
    expect(stream).toBeTruthy();
    const chunks: Buffer[] = [];
    for await (const chunk of stream!) chunks.push(chunk as Buffer);
    const csv = Buffer.concat(chunks).toString('utf-8');

    // Verify header
    const lines = csv.split('\n').filter(l => l.trim().length > 0);
    expect(lines[0]).toBe('CoCCode,ProjectType,HouseholdType,TotalPersons');

    // Verify data rows use integer codes
    if (lines.length > 1) {
      for (let i = 1; i < lines.length; i++) {
        const cols = lines[i].split(',');
        // CoCCode non-empty
        expect(cols[0].trim().length).toBeGreaterThan(0);
        // ProjectType = 0 (ES Entry/Exit), not "ES"
        expect(cols[1].trim()).toBe('0');
        // HouseholdType integer
        expect(['1', '3', '4']).toContain(cols[2].trim());
        // TotalPersons non-negative
        expect(parseInt(cols[3].trim())).toBeGreaterThanOrEqual(0);
      }
    }
  });
});
