---
name: Convoy
description: Group car buying for India. Records the visual system as implemented; it is not a target.
colors:
  primary: "#0f5c4d"
  primary-fg: "#ffffff"
  accent: "#b4520c"
  bg: "#f7f7f5"
  surface: "#ffffff"
  surface-2: "#efeee9"
  fg: "#1b1d1f"
  muted: "#55595e"
  border: "#d4d2cb"
  danger: "#a3261d"
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
    fontFamily: "Inter, ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Noto Sans, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 800
    lineHeight: 1.25
  title:
    fontFamily: "Inter, ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Noto Sans, sans-serif"
    fontSize: "1.125rem"
    fontWeight: 700
    lineHeight: 1.375
  price-lg:
    fontFamily: "Inter, ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Noto Sans, sans-serif"
    fontSize: "1.875rem"
    fontWeight: 700
    fontFeature: "tnum"
  body:
    fontFamily: "Inter, ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Noto Sans, sans-serif"
    fontSize: "1rem"
    fontWeight: 400
    lineHeight: 1.5
  body-sm:
    fontFamily: "Inter, ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Noto Sans, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.43
  label:
    fontFamily: "Inter, ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Noto Sans, sans-serif"
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
    textColor: "#ffffff"
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

<!-- Scope: the system as implemented on 2026-11-02, from web/app/globals.css and web/components/ui. A record, not an endorsement. -->

## Overview

**Creative North Star: "The Default Utility"**

Convoy looks like what it is today: a blunt, accurate, tokenised utility with little authored character. Everything sits in a single centred column of flat, bordered cards on a warm off-white page, with one deep green for actions. Its discipline shows in behaviour and accuracy (states, focus, tabular money, evidence labels) rather than in visual identity.

Nearly every screen is built from the same few primitives, so buyer, dealer and admin surfaces share one density and one look. Hierarchy comes from weight and size inside cards, and the page itself has no distinct composition, imagery or brand mark beyond the bold wordmark.

**Key Characteristics:**
- Flat, bordered cards with no resting shadows.
- One action colour, deep green; amber, red, green and blue tints carry status.
- System sans in practice, because the named first choice is never loaded.
- Small text everywhere: `text-sm` is by far the most used size.
- Light and dark themes through semantic tokens; dark follows the operating system.

## Colors

A neutral warm-grey page and white cards, one deep green for action, and pale tinted pairs for status.

### Primary
- **Deep Bottle Green:** primary buttons, links, the emphasised card border, progress fill and the current timeline step. Dark theme swaps it for a lighter mint (`#4fbfa5`) with near-black text (`#08201b`).

### Secondary
- **Burnt Orange (`accent`):** defined as a token but not used by the components surveyed.

### Neutral
- **Warm Off-White (`bg`):** page background.
- **White (`surface`):** cards, inputs, secondary buttons.
- **Warm Grey Tint (`surface-2`):** neutral badges, the pre-commit panel, skeletons, the next-action strip.
- **Ink (`fg`):** body text and the test-mode banner background.
- **Slate (`muted`):** secondary text, at about 6.6:1 on the page.
- **Stone (`border`):** all hairlines and input borders, at about 1.5:1 on white.

### Status
Each status is a pale background with a dark foreground: **danger**, **warning**, **success** and **info**. The dark theme mirrors each pair with a deep background and a light foreground.

**The Shape-And-Word Rule.** Evidence levels are never colour alone: each badge carries a shape mark (■ ▣ □) and a text label.

## Typography

**Body Font:** Inter is named first in the stack, with `ui-sans-serif`, `system-ui`, `-apple-system`, Segoe UI, Roboto and Noto Sans behind it.

**Character:** Inter is never loaded (no `@font-face`, no `next/font`), so visitors get their platform's system sans unless Inter happens to be installed. Devanagari falls back to whichever system font covers it.

### Hierarchy
- **Headline** (800, 1.5rem, rising to 1.875rem on the home and model pages from `sm`): the page `h1`.
- **Title** (700, 1.125rem): card titles.
- **Price** (700, 1.875rem / 1.25rem / 1rem, tabular): rupee amounts through the `Price` primitive.
- **Body** (400, 1rem): default page text and input text.
- **Body small** (400, 0.875rem): most running text, including terms and explanations.
- **Label** (600, 0.75rem): badges and the fine print under sections. The test-mode banner uses this size at weight 700.

**The Tabular Money Rule.** Rupee amounts and counts use tabular figures, formatted with lakh and crore grouping.

## Layout

A single centred column, `max-w-5xl` with 16px side padding, under a wrapping header. Screens stack cards with 16 to 24px gaps. Spacing follows Tailwind's 4px steps, with 8 and 12px gaps most common.

Grids appear from the `sm`, `md` and `lg` breakpoints: discover goes to two then three columns, the model and pool pages to two asymmetric columns, and compare to two offer cards at `lg`. Below `sm`, the price breakdown switches from a table to a stacked list. There is no sticky action bar and no route-level navigation beyond the header links. On `origin/main` and the live build, a small footer line shows the build id.

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
- **Secondary:** white with a stone border; hover tints to `surface-2`.
- **Danger:** red with white text.
- **Link:** green underlined text at 24px minimum height.
- **States:** hover brightens or tints, disabled drops to 55% opacity, and loading adds a small spinner and `aria-busy`.

### Cards / Containers
- **Style:** white, 1px stone border, 16px padding (20px from `sm`), no shadow.
- **Emphasis:** a 2px green border.

### Inputs / Fields
- **Style:** white with a stone border, 44px minimum height, label above in semibold small text, hint below it.
- **Focus:** the global 3px blue outline with 2px offset.
- **Error:** danger border with a linked `role="alert"` message; the value is kept. Several forms outside this primitive use raw inputs with `p-2` and no minimum height.

### Badges
Small pills in neutral, success, warning, danger or info tints, never wrapping.

### Navigation
Text links in a wrapping header row beneath a dark, full-width test-mode banner. There is no active-page state. Language switch and sign-in are pushed to the right; the notifications bell is an emoji with a screen-reader label.

### Evidence Badge
The product's one signature primitive: an evidence level as a tinted pill with a shape mark and label (Dealer-confirmed, Catalogue estimate, Unverified).

### Countdown
A deadline line with its IST time and a live remaining value, ticking each second inside the last 24 hours, followed by a "Server time" line. It adds a warning in the final 60 seconds.

### Tier Ladder
A plain ordered list of confirmed-buyer ranges and prices, with the ceiling tier in bold.

### Order Timeline
A vertical list of steps with small status dots, followed by a tinted next-action strip.

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
- **Don't** treat this record as a target. A proposed direction is under review separately.
