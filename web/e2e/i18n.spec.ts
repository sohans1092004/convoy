import { expect, test } from '@playwright/test';

// The buyer journey in Hindi (machine-drafted, see messages/README.md): the header switch changes language and lang.
test('the language switch shows the buyer pages in Hindi and back', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('html')).toHaveAttribute('lang', 'en-IN');
  await page.getByRole('button', { name: 'हिन्दी' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('एक ही कार साथ मिलकर खरीदें, ऐसी कीमतों पर जिन्हें आप जाँच सकें');
  await expect(page.locator('html')).toHaveAttribute('lang', 'hi-IN');
  await expect(page.getByLabel('कारें खोजें')).toBeVisible();
  await page.getByRole('button', { name: 'English' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Buy the same car together, with prices you can check');
});
