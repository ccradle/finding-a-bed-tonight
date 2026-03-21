import { Page, Locator } from '@playwright/test';

export class AdminPanelPage {
  readonly page: Page;
  readonly heading: Locator;
  readonly tabs: Locator;

  constructor(page: Page) {
    this.page = page;
    this.heading = page.locator('h1');
    this.tabs = page.locator('button[style*="whiteSpace: nowrap"]');
  }

  async goto() {
    await this.page.goto('/admin');
  }

  async selectTab(tabText: string) {
    await this.page.locator('button', { hasText: new RegExp(tabText, 'i') }).click();
    await this.page.waitForTimeout(500);
  }

  async clickCreateUser() {
    await this.page.locator('button', { hasText: /create user/i }).click();
  }

  async fillCreateUserForm(email: string, displayName: string, password: string, roles: string[]) {
    await this.page.locator('input[type="email"]').last().fill(email);
    await this.page.locator('input').filter({ hasText: '' }).nth(1).fill(displayName);
    await this.page.locator('input[type="password"]').last().fill(password);
    for (const role of roles) {
      await this.page.locator('button', { hasText: role }).click();
    }
  }
}
