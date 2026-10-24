# Frontend quality method (PLAN §3.3, §5)

## 1. Real-user Web Vitals (RUM): p75 LCP < 2.5 s, INP < 200 ms, CLS < 0.1
- Library: `web-vitals` (attribution build) loaded after hydration; reports `LCP`, `INP`, `CLS` (+ `TTFB`, `FCP` for diagnosis) with `navigator.sendBeacon` to `POST /api/rum` (BFF) → batched to CloudWatch (custom metrics) and S3 (raw, 30-day retention).
- Dimensions: route template (e.g. `/pools/[id]`), device class (from UA-CH / `navigator.deviceMemory` + `hardwareConcurrency` buckets), effective connection type, build id. No user ids; sampling 100 % during beta, 25 % otherwise.
- Aggregation: p75 per metric per route per week, **minimum 200 page views per route-week**; below that, report the count and "insufficient sample" instead of a number.
- Report labelled with population: beta users (n, dates) vs synthetic traffic (never mixed; synthetic RUM is excluded).

## 2. Lab regression gate
- Lighthouse CI on every PR for the 5 flagship screens + quote builder, preset **Moto G Power-class emulation, 4G (Lighthouse "mobile" throttling: 150 ms RTT, 1.6 Mbps)**, 3 runs, median.
- Assertions: performance score ≥ 0.85; LCP ≤ 2.5 s; TBT ≤ 200 ms (lab proxy for INP); CLS ≤ 0.1. Baselines stored in repo; > 10 % regression fails.

## 3. Route-level initial JS budgets
- Measured from the **production build** (`next build`) per route: sum of **gzip-compressed** sizes of JS chunks required for first load of that route (shared + route chunks), from the build manifest (`.next/build-manifest.json` + `app-build-manifest.json`), computed by a script in CI.
- Baseline: the first P1 walking-skeleton build sets the baseline per route; budget = min(baseline × 1.10, cap) with caps: Discover 170 KB, Join 150 KB, Compare 190 KB, Confirm 150 KB, Track 160 KB, Quote builder 230 KB (gzip). Cap values are starting assumptions (Q-037) to be revisited once the baseline exists.
- CI fails if any route exceeds its budget; raising a budget needs a PR note with the reason. Recharts and other heavy widgets must be dynamically imported and are excluded from first load.

## 4. Accessibility checklist (WCAG 2.2 AA)
Automated: axe-core in Playwright for every flagship screen in all 8 interaction states (§5.2), light + dark, 360 px + 1280 px; zero serious/critical violations.

Manual (per flagship screen and quote builder, each release candidate):
- [ ] Full keyboard operation; logical focus order; visible focus (2 px, ≥ 3:1 contrast) — 2.4.7, 2.4.11 (focus not obscured)
- [ ] Skip link; landmarks (`header/nav/main/footer`); one `h1`; headings hierarchical
- [ ] All controls have accessible names; icons have labels; currency read correctly ("₹8,55,000" read as "8 lakh 55 thousand rupees" via `aria-label` where needed)
- [ ] Target size ≥ 24×24 CSS px (2.5.8)
- [ ] Colour contrast ≥ 4.5:1 text, 3:1 UI; state never by colour alone (evidence indicator uses text + icon)
- [ ] Live regions: counts and countdowns announce politely and at most once per 30 s; deadline-passed/conflict uses `role="alert"`
- [ ] Forms: labels, errors linked with `aria-describedby`, input preserved on error (3.3.7 redundant entry), no CAPTCHA-only auth (3.3.8: passkeys/OTP)
- [ ] Dense tables (quote builder): `<table>` semantics, header scopes, caption, row headers, keyboard grid navigation documented
- [ ] Reflow at 320 px without horizontal scroll (except data tables in a scroll container with keyboard access)
- [ ] Reduced motion respected (`prefers-reduced-motion`)
- [ ] Screen-reader pass: NVDA + Firefox (Windows), VoiceOver + Safari (iOS) on the 5 flagship screens
- [ ] Hindi locale: `lang` attribute switches; numerals and lakh/crore formatting correct

## 5. Design-system gate (PLAN §5.4)
A component may be used in a flagship screen only when it has:
1. Storybook stories for every variant, size and state (incl. loading/disabled/error), light and dark;
2. documented keyboard and screen-reader behaviour, tested by Storybook interaction tests;
3. visual regression snapshots (Chromatic or Playwright screenshots) at 360 and 1280 px;
4. tokens only — lint rule (Stylelint + ESLint plugin) rejects raw colours, spacing and font sizes outside the token set;
5. an entry in the component inventory (owner, status: draft → reviewed → stable).

Adding a component: open a design note (problem, existing components considered, API), build in Storybook with stories + a11y tests, review by a second person (or recorded self-review checklist for a solo project), then mark stable.
