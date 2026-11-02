---
name: Convoy
description: Group car buying for India. Records the visual system as implemented ("the rate card").
colors:
  primary: "#0f5c4d"
  primary-fg: "#ffffff"
  bg: "#f6f7f6"
  surface: "#ffffff"
  surface-2: "#eceeec"
  fg: "#1b1d1f"
  muted: "#4a5057"
  border: "#d9dcd9"
  control: "#858a8f"
  danger: "#a3261d"
  danger-fg: "#ffffff"
  danger-bg: "#fbeceb"
  warning-bg: "#fdf3dd"
  warning-fg: "#6b4a00"
  success-bg: "#e6f3ee"
  success-fg: "#0b4d3f"
  info-bg: "#e9eef6"
  info-fg: "#23406b"
  focus: "#1a56db"
typography:
  headline:
    fontFamily: "IBM Plex Sans, IBM Plex Sans Devanagari, Segoe UI, system-ui, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 600
    lineHeight: 1.25
  title:
    fontFamily: "IBM Plex Sans, IBM Plex Sans Devanagari, Segoe UI, system-ui, sans-serif"
    fontSize: "1.125rem"
    fontWeight: 600
    lineHeight: 1.375
  price-lg:
    fontFamily: "IBM Plex Sans, IBM Plex Sans Devanagari, Segoe UI, system-ui, sans-serif"
    fontSize: "1.875rem"
    fontWeight: 600
    fontFeature: "tnum"
  body:
    fontFamily: "IBM Plex Sans, IBM Plex Sans Devanagari, Segoe UI, system-ui, sans-serif"
    fontSize: "1rem"
    fontWeight: 400
    lineHeight: 1.5
  body-sm:
    fontFamily: "IBM Plex Sans, IBM Plex Sans Devanagari, Segoe UI, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.43
  label:
    fontFamily: "IBM Plex Sans, IBM Plex Sans Devanagari, Segoe UI, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 600
    lineHeight: 1.33
rounded:
  lg: "0.625rem"
  full: "9999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "20px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.primary-fg}"
    rounded: "{rounded.lg}"
    height: "44px"
    padding: "0 16px"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.fg}"
    rounded: "{rounded.lg}"
    height: "44px"
    padding: "0 16px"
  button-danger:
    backgroundColor: "{colors.danger}"
    textColor: "{colors.danger-fg}"
    rounded: "{rounded.lg}"
    height: "44px"
    padding: "0 16px"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.fg}"
    rounded: "{rounded.lg}"
    height: "44px"
    padding: "0 12px"
  card:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.lg}"
    padding: "16px"
  badge-neutral:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.fg}"
    rounded: "{rounded.full}"
    padding: "2px 8px"
    typography: "{typography.label}"
---

# Design System: Convoy

<!-- Scope: the system as implemented on 2026-11-02, from web/app/globals.css and web/components/ui. -->

## Overview

**Creative North Star: "The Rate Card"**

The priced, itemised sheet is the product's signature. The home page leads with leverage ("Dealers compete for you"), and the decision screens lead with the sheet: the most you can pay, each line, and what each line is based on. Cool neutrals and one green keep attention on the figures.

**Key Characteristics:**
- Ruled rows inside flat, bordered cards, with no resting shadows.
- One action colour, deep green. Amber means "unverified" or "needs your attention".
- IBM Plex Sans and IBM Plex Sans Devanagari, self-hosted, at two weights.
- Two densities: generous for buyers, dense for dealer and admin tools.
- Light and dark themes through semantic tokens; dark follows the operating system.

## Colors

### Primary
- **Deep Bottle Green:** primary buttons, links, the emphasised card border, the progress fill and the current timeline step. The dark theme swaps it for a lighter mint (`#4fbfa5`) with near-black text (`#08201b`).

### Neutral
- **Page (`bg`), White (`surface`), Cool Tint (`surface-2`):** page, cards and inputs, then neutral badges, the pre-commit panel and rows that differ across offers.
- **Ink (`fg`):** body text and the test-mode banner background.
- **Slate (`muted`):** secondary text.
- **Hairline (`border`):** rules and card edges, never the only edge of a control.
- **Control (`control`):** input, radio-row and secondary-button borders, at least 3:1 on the page and on white in both themes (`#858a8f`, dark `#6e757b`).

### Status
Each status is a pale background with a dark foreground: **danger**, **warning**, **success** and **info**. The dark theme mirrors each pair. `danger-fg` is the text on a danger button (white, or near-black on the dark theme's light red).

**The Shape-And-Word Rule.** Evidence levels are never colour alone: each carries a shape mark (■ ▣ □) and a word.

## Typography

**Font:** IBM Plex Sans first, because IBM Plex Sans Devanagari has no rupee sign. Both are self-hosted subsets in `app/fonts` at 400 and 600. The Devanagari files load only when a page has Hindi text. Every heavy utility resolves to 600, and `font-medium` to 400.

### Hierarchy
- **Hero figure** (600, 2.25rem, tabular): the ceiling on the confirm screen.
- **Headline** (600, 1.5rem; 1.875 to 2.25rem on home): the page `h1`.
- **Title** (600, 1.125rem): card titles.
- **Price** (600, 1.875rem / 1.25rem / 1rem, tabular): rupee amounts through the `Price` primitive.
- **Body** (400, 1rem) and **Body small** (400, 0.875rem).
- **Label** (0.75rem): evidence words and fine print.

Hindi uses a line height of 1.7 for body text and 1.35 for headings.

**The Tabular Money Rule.** Rupee amounts and counts use tabular figures, with lakh and crore grouping. Plex Sans digits are tabular by default.

### Density
- **Buyer (default):** 16 px body, 44 px controls (`--control-h`).
- **Work (`data-density="work"`, dealer and admin layouts):** 14 px body, 13 px small text, 36 px controls, 32 px from 1024 px wide. Targets stay above the 24 px floor.

## Layout

A single centred column, `max-w-5xl` with 16px side padding, under a wrapping header. Screens stack cards with 16 to 24px gaps. Spacing follows Tailwind's 4px steps, with 8 and 12px gaps most common.

Grids appear from the `sm`, `md` and `lg` breakpoints: discover goes to two then three columns, and the model and pool pages to two asymmetric columns. Compare is one DOM: sorted cards on a phone, and from `md` a matrix whose rows align across dealers through CSS subgrid, with row labels in a first column. Below `sm`, the price breakdown switches from a table to a stacked list. There is no sticky action bar and no route-level navigation beyond the header links. On `origin/main` and the live build, a small footer line shows the build id.

## Elevation & Depth

Flat by default. Depth comes from borders and the `surface` / `surface-2` tint. A 2px primary border (`emphasis`) marks the card that matters on a screen.

### Shadow Vocabulary
- **Dialog** (`box-shadow: xl`, over a 55% black scrim): dialogs only.
- **Toast** (`box-shadow: lg`): toasts only. The primitive exists, but `origin/main` no longer mounts it in the app layout.

**The Flat-By-Default Rule.** Surfaces carry no shadow at rest; only overlays lift.

## Shapes

One gently rounded corner (0.625rem) on cards, buttons, inputs and notices. Badges and the progress track are full pills. The quote builder's small table inputs use a tighter default corner. Borders are 1px hairlines, with 2px for emphasis and a dashed border for the non-guaranteed projection.

## Components

### Buttons
Plain and direct, with no iconography.
- **Shape:** gently rounded, 44px minimum height.
- **Primary:** green with white text, semibold 0.95rem.
- **Secondary:** white with a control border; hover tints to `surface-2`.
- **Danger:** red with white text.
- **Link:** green underlined text at 24px minimum height.
- **States:** hover brightens or tints, disabled drops to 55% opacity, and loading adds a small spinner and `aria-busy`.

### Cards / Containers
- **Style:** white, 1px hairline border, 16px padding (20px from `sm`), no shadow.
- **Emphasis:** a 2px green border.

### Inputs / Fields
- **Style:** white with a control border, `--control-h` minimum height, label above in semibold small text, hint below it.
- **Focus:** the global 3px blue outline with 2px offset.
- **Error:** danger border with a linked `role="alert"` message; the value is kept. Several forms outside this primitive use raw inputs with `p-2` and no minimum height.

### Badges
Small pills in neutral, success, warning, danger or info tints, never wrapping.

### Navigation
Text links in a wrapping header row beneath a dark, full-width test-mode banner. There is no active-page state. Language switch and sign-in are pushed to the right; the notifications bell is an emoji with a screen-reader label.

### Evidence Badge
The product's one signature primitive: a small word beside an amount, with a shape mark (Dealer-confirmed, Catalogue estimate, Unverified). Only "Unverified" takes colour. Estimates never read as dealer-confirmed.

### Countdown
A deadline stated as a fact: its date and time in IST, and the time now on the server clock, to the minute. No ticking seconds. A plain note appears in the last minute, and the last minute and the close are each announced once to screen readers.

### Two-Step Confirm
Consequential actions (decline, cancel, withdraw, approve, verify) open their terms and an explicit yes in place. Focus lands on the safe choice.

### Tier Ladder
A plain ordered list of confirmed-buyer ranges and prices, with the ceiling tier in 600.

### Order Timeline
A vertical list of steps, followed by a tinted next-action strip. Done is a check, current is a ring with a 600 label, not yet is a hollow dot, and did not happen is a dash.

## Do's and Don'ts

### Do:
- **Do** use the semantic tokens; components reference no raw hex apart from a few white literals.
- **Do** keep money in tabular figures with lakh and crore grouping.
- **Do** pair every status colour with words, and evidence with its shape mark.
- **Do** keep the visible test-mode banner on every page.
- **Do** respect `prefers-reduced-motion`, which currently disables all animation and transitions.

### Don't:
- **Don't** add resting shadows; this system is flat, and only overlays lift.
- **Don't** introduce a second action colour; green is the only one in use.
- **Don't** use `--border` as the only edge of a control; use `--control`.
