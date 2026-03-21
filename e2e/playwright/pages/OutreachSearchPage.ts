import { Page, Locator } from '@playwright/test';

export class OutreachSearchPage {
  readonly page: Page;
  readonly heading: Locator;
  readonly searchInput: Locator;
  readonly populationTypeSelect: Locator;
  readonly shelterCards: Locator;
  readonly detailModal: Locator;
  readonly closeModalButton: Locator;

  constructor(page: Page) {
    this.page = page;
    this.heading = page.locator('main h1');
    this.searchInput = page.locator('main input[type="search"]');
    this.populationTypeSelect = page.locator('main select');
    this.shelterCards = page.locator('div[style*="border"][style*="borderRadius"]').filter({ hasText: /.+/ });
    this.detailModal = page.locator('div[style*="position: fixed"]');
    this.closeModalButton = page.locator('button', { hasText: /close|cerrar/i });
  }

  async goto() {
    await this.page.goto('/outreach');
  }

  async waitForResults() {
    await this.page.waitForSelector('main div[style*="border"]', { timeout: 10000 });
  }

  async selectPopulationType(value: string) {
    await this.populationTypeSelect.selectOption(value);
    await this.page.waitForTimeout(1000); // Wait for API refresh
  }

  async clickFilter(label: string) {
    await this.page.locator('main button', { hasText: label }).click();
    await this.page.waitForTimeout(1000);
  }

  async clickShelterCard(index = 0) {
    const cards = this.page.locator('main div[style*="cursor: pointer"][style*="border"]');
    await cards.nth(index).click();
  }

  async closeModal() {
    await this.closeModalButton.click();
  }
}
