# UI backlog: open P2 and P3 items after the "ship" pass

Items come from the U.0 review. None are built. P1 parts that were outside the ship scope are listed first.

## Left over from P1 items
- **Compare sorting (`ranking-rule.md` §4):** the rule says offers are sortable by cash payable, net cost, delivery date and evidence strength. Only the cash-payable order is built, with no sort control.
- **Quote builder as a work tool (1.13):** a sticky preview beside the tiers and copying figures between variants are not built. Typing inside a grouped amount moves the caret to the end.
- **Admin layout (1.15):** the table with expandable rows, and counts on the tabs, are not built. Whether the server blocks self-approval from the UI path was not tested; `Payments.java` has the check.
- **Hindi beyond the money screens (1.16):** the model page's remaining strings are marked `lang="en"`, not translated. The dealer tools are all English and marked as such. `scripts/i18n-untranslated.mjs` still undercounts. All Hindi remains an unreviewed machine draft.
- **Demo confirmation window:** still 60 s, so the last-minute warning shows as soon as the confirm screen opens. Only the decision window was lengthened.

## P2
- Pool title from a slug if the catalogue is unreachable: a hyphenated brand reads badly in that fallback only.
- Model estimate: a ₹0 TCS line still shows on cars below the TCS threshold.
- Raw formats: "2026-11" for the purchase month, ISO delivery dates, "PETROL · MT", lowercased pool membership states, ungrouped large counts ("12345").
- Quote builder at 200% text: reaches about 515 px wide at a 360 px viewport.
- Compare with 500 offers: about 330,000 px tall, one dealer request per card. Needs a paged matrix and a batched dealer lookup; no cap was found.
- Signed-in header: three wrapped rows at 360 px; nav and language targets are 20 px tall and pass 2.5.8 only through spacing.
- Quote builder details: fixed what-if counts (3, 7, 12) can all sit below the minimum; long accessory names clip in small inputs; with 0 variants the page says only "Price at least one variant".
- Pool: leaving has no confirmation; a dead column at 1280 px.
- Order: "final price is set when confirmations close" stays visible beside the final price; steps have no dates.
- Discover: the chosen city is dropped on the way to the model page.
- Admin tabs skip the tabs pattern (no arrow keys or panel link), though `ui/tabs.tsx` exists.
- Polling: about 45 requests a minute on an order, continuing in hidden tabs and after booking.
- Heading skips on the remaining order cards.
- Dealer name length: `trade_name` is unbounded text; it wraps today, but no limit is decided.

## P3
- The `[data-theme]` token block and the unmounted toast are unused.
- Emoji bell; static `themeColor` in dark mode.
- Snapshot hash and ruleset shown to buyers at full prominence.
- `Price`'s `aria-label` on a span is dropped by the accessibility tree; the visible text still reads sensibly.

## For the owner
- **`docs/rules/deadline-rule.md` §3** words the countdown as "server time 17:58:41" and "must *reach our database*". The UI now shows the server time to the minute as "Time now" and says "must reach us", as directed. Enforcement and the last-60-second warning are unchanged, and the rule file was not edited. Aligning its wording needs an amendment.
