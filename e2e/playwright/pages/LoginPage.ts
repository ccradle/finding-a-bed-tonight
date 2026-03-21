import { Page, Locator } from '@playwright/test';

export class LoginPage {
  readonly page: Page;
  readonly tenantSlugInput: Locator;
  readonly emailInput: Locator;
  readonly passwordInput: Locator;
  readonly submitButton: Locator;
  readonly errorMessage: Locator;

  constructor(page: Page) {
    this.page = page;
    this.tenantSlugInput = page.locator('input[placeholder*="tenant"], input[placeholder*="organization"], input[name*="tenant"], input[name*="slug"]');
    this.emailInput = page.locator('input[type="email"]');
    this.passwordInput = page.locator('input[type="password"]');
    this.submitButton = page.locator('button[type="submit"]');
    this.errorMessage = page.locator('[role="alert"], [style*="fef2f2"]');
  }

  async goto() {
    await this.page.goto('/login');
  }

  async login(email: string, password: string, tenantSlug = 'dev-coc') {
    if (await this.tenantSlugInput.count() > 0) {
      await this.tenantSlugInput.first().fill(tenantSlug);
    }
    await this.emailInput.fill(email);
    await this.passwordInput.fill(password);
    await this.submitButton.click();
  }

  async loginAndWaitForRedirect(email: string, password: string, tenantSlug = 'dev-coc') {
    await this.login(email, password, tenantSlug);
    await this.page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 10000 });
  }
}
