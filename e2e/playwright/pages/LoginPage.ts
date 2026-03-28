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
    this.tenantSlugInput = page.locator('[data-testid="login-tenant-slug"]');
    this.emailInput = page.locator('[data-testid="login-email"]');
    this.passwordInput = page.locator('[data-testid="login-password"]');
    this.submitButton = page.locator('[data-testid="login-submit"]');
    this.errorMessage = page.locator('[role="alert"]');
  }

  async goto() {
    await this.page.goto('/login');
    await this.tenantSlugInput.waitFor({ state: 'visible', timeout: 10000 });
  }

  async login(email: string, password: string, tenantSlug = 'dev-coc') {
    await this.tenantSlugInput.fill(tenantSlug);
    await this.emailInput.fill(email);
    await this.passwordInput.fill(password);
    await this.submitButton.click();
  }

  async loginAndWaitForRedirect(email: string, password: string, tenantSlug = 'dev-coc') {
    await this.login(email, password, tenantSlug);
    await this.page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 10000 });
  }
}
