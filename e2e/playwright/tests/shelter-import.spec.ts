import { test, expect } from '../fixtures/auth.fixture';
import * as path from 'path';
import * as fs from 'fs';

/**
 * Issue #65 — Shelter import documentation + extended adapter Playwright tests.
 *
 * Tests the import page UX: quick-start card, template download links,
 * preview with upsert counts, error display, and successful import.
 *
 * Requires the dev stack running with nginx (BASE_URL=http://localhost:8081).
 */

const TEMPLATES_DIR = path.join(__dirname, '..', '..', '..', 'infra', 'templates');

test.describe('Shelter Import (Issue #65)', () => {

  test('7.1: import page shows quick-start card with download links', async ({ adminPage }) => {
    await adminPage.goto('/coordinator/import/211');

    const quickStart = adminPage.getByTestId('import-quick-start');
    await expect(quickStart).toBeVisible({ timeout: 5000 });

    // Three numbered steps
    const steps = quickStart.locator('ol li');
    expect(await steps.count()).toBe(3);

    // Download links for template and example
    const templateLink = quickStart.locator('a[download]').first();
    await expect(templateLink).toBeVisible();
    const exampleLink = quickStart.locator('a[download]').nth(1);
    await expect(exampleLink).toBeVisible();

    // Full format reference link
    const refLink = quickStart.locator('a[target="_blank"]');
    await expect(refLink).toBeVisible();
  });

  test('7.2: upload valid CSV → preview → commit → shelters created', async ({ adminPage }) => {
    await adminPage.goto('/coordinator/import/211');

    // Create a temp CSV with full columns
    const uniqueSuffix = Date.now().toString().slice(-6);
    const csv = [
      'name,addressStreet,addressCity,addressState,addressZip,phone,populationTypesServed,bedsTotal,bedsOccupied',
      `PW Import Test ${uniqueSuffix},100 Test St,Raleigh,NC,27601,919-555-0100,SINGLE_ADULT,30,10`,
    ].join('\n');

    const tmpFile = path.join(TEMPLATES_DIR, `pw-test-${uniqueSuffix}.csv`);
    fs.writeFileSync(tmpFile, csv);

    try {
      // Upload via the file input
      const fileInput = adminPage.locator('input[type="file"]');
      await fileInput.setInputFiles(tmpFile);

      // Click preview
      const previewBtn = adminPage.locator('button', { hasText: /Preview/i });
      await expect(previewBtn).toBeEnabled({ timeout: 3000 });
      await previewBtn.click();

      // Wait for preview to load — should show column mapping table
      await expect(adminPage.locator('table')).toBeVisible({ timeout: 10000 });

      // Import preview summary should show upsert counts
      const summary = adminPage.getByTestId('import-preview-summary');
      await expect(summary).toBeVisible({ timeout: 5000 });

      // Click confirm
      const confirmBtn = adminPage.locator('button', { hasText: /Confirm|Import/i });
      await expect(confirmBtn).toBeVisible();
      await confirmBtn.click();

      // Result should show created count
      await expect(adminPage.locator('text=1').first()).toBeVisible({ timeout: 10000 });
    } finally {
      // Cleanup temp file
      try { if (fs.existsSync(tmpFile)) fs.unlinkSync(tmpFile); } catch { /* Windows file lock — cleanup on next run */ }
    }
  });

  test('7.3: upload CSV with errors → error summary visible + download button', async ({ adminPage }) => {
    await adminPage.goto('/coordinator/import/211');

    const uniqueSuffix = Date.now().toString().slice(-6);
    const csv = [
      'name,addressCity,populationTypesServed,bedsTotal,bedsOccupied',
      `Good Shelter ${uniqueSuffix},Raleigh,SINGLE_ADULT,30,10`,
      `Bad Shelter ${uniqueSuffix},Raleigh,INVALID_TYPE,30,10`,
    ].join('\n');

    const tmpFile = path.join(TEMPLATES_DIR, `pw-error-${uniqueSuffix}.csv`);
    fs.writeFileSync(tmpFile, csv);

    try {
      const fileInput = adminPage.locator('input[type="file"]');
      await fileInput.setInputFiles(tmpFile);

      const previewBtn = adminPage.locator('button', { hasText: /Preview/i });
      await previewBtn.click();

      // Wait for preview
      await expect(adminPage.locator('table')).toBeVisible({ timeout: 10000 });

      // Import preview should show errors
      const summary = adminPage.getByTestId('import-preview-summary');
      await expect(summary).toBeVisible({ timeout: 5000 });

      // The preview should contain error information about the invalid type.
      // Check for either error list items OR the error count indicator.
      const summaryText = await summary.textContent();
      console.log('Import preview summary text:', summaryText);
      await expect(summary).toContainText(/INVALID_TYPE|error|Error/i, { timeout: 5000 });

      // Download errors button should be present
      const downloadBtn = adminPage.getByTestId('download-errors-btn');
      await expect(downloadBtn).toBeVisible();
    } finally {
      try { if (fs.existsSync(tmpFile)) fs.unlinkSync(tmpFile); } catch { /* Windows file lock — cleanup on next run */ }
    }
  });

  test('7.4: re-upload same file → preview shows Will update', async ({ adminPage }) => {
    await adminPage.goto('/coordinator/import/211');

    const uniqueSuffix = Date.now().toString().slice(-6);
    const csv = [
      'name,addressCity,phone',
      `Upsert PW Test ${uniqueSuffix},Raleigh,919-555-0001`,
    ].join('\n');

    const tmpFile = path.join(TEMPLATES_DIR, `pw-upsert-${uniqueSuffix}.csv`);
    fs.writeFileSync(tmpFile, csv);

    try {
      // First import — creates the shelter
      const fileInput = adminPage.locator('input[type="file"]');
      await fileInput.setInputFiles(tmpFile);

      const previewBtn = adminPage.locator('button', { hasText: /Preview/i });
      await previewBtn.click();
      await expect(adminPage.locator('table')).toBeVisible({ timeout: 10000 });

      const confirmBtn = adminPage.locator('button', { hasText: /Confirm|Import/i });
      await confirmBtn.click();
      await expect(adminPage.locator('text=1').first()).toBeVisible({ timeout: 10000 });

      // Reset and re-upload
      const resetBtn = adminPage.locator('button', { hasText: /Import Another|Start Over|New Import/i });
      await resetBtn.click();

      // Re-upload same CSV with updated phone
      const csv2 = [
        'name,addressCity,phone',
        `Upsert PW Test ${uniqueSuffix},Raleigh,919-555-9999`,
      ].join('\n');
      fs.writeFileSync(tmpFile, csv2);

      const fileInput2 = adminPage.locator('input[type="file"]');
      await fileInput2.setInputFiles(tmpFile);

      const previewBtn2 = adminPage.locator('button', { hasText: /Preview/i });
      await previewBtn2.click();
      await expect(adminPage.locator('table')).toBeVisible({ timeout: 10000 });

      // The import preview should show "will be updated" (not "will be created")
      const summary = adminPage.getByTestId('import-preview-summary');
      await expect(summary).toBeVisible({ timeout: 5000 });
      // The updated count should be > 0
      await expect(summary).toContainText(/will be updated/i);
    } finally {
      try { if (fs.existsSync(tmpFile)) fs.unlinkSync(tmpFile); } catch { /* Windows file lock — cleanup on next run */ }
    }
  });
});
