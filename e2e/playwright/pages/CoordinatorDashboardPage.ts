import { Page, Locator } from '@playwright/test';

export class CoordinatorDashboardPage {
  readonly page: Page;
  readonly heading: Locator;
  readonly shelterCards: Locator;

  constructor(page: Page) {
    this.page = page;
    this.heading = page.locator('main h1');
    this.shelterCards = page.locator('main button[style*="text-align: left"]');
  }

  async goto() {
    await this.page.goto('/coordinator');
  }

  async waitForShelters() {
    await this.page.waitForSelector('button[style*="text-align: left"]', { timeout: 10000 });
  }

  async expandShelter(index = 0) {
    await this.shelterCards.nth(index).click();
    await this.page.waitForTimeout(500);
  }

  async clickUpdateAvailability() {
    await this.page.locator('button', { hasText: /update availability/i }).first().click();
  }

  async clickSaveCapacity() {
    await this.page.locator('button', { hasText: /save/i }).click();
  }
}
