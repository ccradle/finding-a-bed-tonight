import { Page, Locator } from '@playwright/test';

export class CoordinatorDashboardPage {
  readonly page: Page;
  readonly heading: Locator;
  readonly shelterCards: Locator;

  constructor(page: Page) {
    this.page = page;
    this.heading = page.getByTestId('coordinator-heading');
    this.shelterCards = page.locator('[data-testid^="shelter-card-"]');
  }

  async goto() {
    await this.page.goto('/coordinator');
  }

  async waitForShelters() {
    // Wait for at least one shelter card to be visible (auto-retry)
    await this.shelterCards.first().waitFor({ state: 'visible', timeout: 15000 });
  }

  async expandShelter(index = 0) {
    await this.shelterCards.nth(index).click();
    await this.page.waitForTimeout(500);
  }

  async clickUpdateAvailability() {
    await this.page.locator('button', { hasText: /update availability/i }).first().click();
  }
}
