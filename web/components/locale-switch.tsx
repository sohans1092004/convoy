'use client';
import { useLocale } from 'next-intl';
import { useRouter } from 'next/navigation';

/** English / हिन्दी. On an English page the Hindi label uses the system font, so this one word doesn't fetch the Devanagari webfont. Stores the choice in the NEXT_LOCALE cookie and re-renders the page in that language. */
export function LocaleSwitch() {
  const locale = useLocale();
  const router = useRouter();
  const set = (l: string) => {
    document.cookie = `NEXT_LOCALE=${l}; path=/; max-age=31536000; samesite=lax`;
    router.refresh();
  };
  return (
    <span className="flex items-center gap-1 text-sm" role="group" aria-label="Language">
      <button type="button" lang="en" aria-pressed={locale === 'en'} onClick={() => set('en')} className={locale === 'en' ? 'font-bold underline' : 'text-muted'}>English</button>
      <span aria-hidden className="text-muted">·</span>
      <button type="button" lang="hi" aria-pressed={locale === 'hi'} onClick={() => set('hi')} className={locale === 'hi' ? 'font-bold underline' : 'font-[system-ui] text-muted'}>हिन्दी</button>
    </span>
  );
}
