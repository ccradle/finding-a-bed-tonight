import { Page, Locator } from '@playwright/test';

export class ReservationPanel {
  readonly page: Page;
  readonly panelToggle: Locator;
  readonly reservationCards: Locator;
  readonly confirmButtons: Locator;
  readonly cancelButtons: Locator;

  constructor(page: Page) {
    this.page = page;
    this.panelToggle = page.locator('main button', { hasText: /my reservations/i });
    this.reservationCards = page.locator('main div[style*="borderBottom"]');
    this.confirmButtons = page.locator('main button', { hasText: /confirm/i });
    this.cancelButtons = page.locator('main button', { hasText: /cancel/i });
  }

  async isVisible(): Promise<boolean> {
    return this.panelToggle.isVisible();
  }

  async toggle() {
    await this.panelToggle.click();
    await this.page.waitForTimeout(300);
  }
}
