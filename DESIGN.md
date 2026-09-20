---
name: Anitabi Navigator
description: Neutral map discovery and a warm paper travel journal
colors:
  paper: "#F7F6F2"
  ink: "#20231F"
  vermilion: "#C93E4F"
  supporting-ink: "#5F625D"
  paper-dark: "#1A1815"
  ink-dark: "#ECE1D6"
  vermilion-dark: "#FFB4A6"
  supporting-ink-dark: "#D0C5BA"
  map-background: "#F5F6F7"
  map-surface: "#FFFFFF"
  map-background-dark: "#17191C"
  map-surface-dark: "#202226"
typography:
  headline:
    fontFamily: system sans-serif
    fontSize: 24sp
    lineHeight: 30sp
    fontWeight: 600
  title:
    fontFamily: system sans-serif
    fontSize: 20sp
    lineHeight: 26sp
    fontWeight: 600
  body:
    fontFamily: system sans-serif
    fontSize: 16sp
    lineHeight: 24sp
    fontWeight: 400
  supporting:
    fontFamily: system sans-serif
    fontSize: 14sp
    lineHeight: 20sp
    fontWeight: 400
rounded:
  small: 8dp
  medium: 12dp
  large: 16dp
  panel: 24dp
spacing:
  inline: 8dp
  related: 12dp
  content: 16dp
  page: 20dp
  section: 24dp
components:
  primary-action:
    backgroundColor: "{colors.vermilion}"
    rounded: "{rounded.medium}"
    height: 48dp minimum
  journal-toolbar:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    height: 64dp minimum
---

# Design system

## Overview

The approved direction pairs a neutral map with a warm paper travel journal. Map chrome recedes behind places and guidance; forms and lists use typography, spacing and thin rules. Material 3 supplies the controls and native interaction model.

## Colors

`ui/theme/Theme.kt` is the implementation source. `AnitabiTheme` selects the warm light or dark palette; `MapSurfaceTheme` supplies neutral surfaces without changing appearance. Components use semantic Material color roles. Dark paper has separate warm-gray surface levels.

## Typography

System sans-serif supports Chinese and Android font scaling. Primary reading text is 16 sp; supporting text and control labels are at least 14 sp. Headings use weight and scale, with no decorative eyebrow labels. Route numbers use tabular figures. Navigation instructions remain available in full through scrolling.

## Layout

Phone map content uses one panel; wide layouts use a side panel. Search is constrained to 840 dp, about/settings to 720 dp and onboarding to 640 dp. Planner forms retain independently scrollable columns on wide screens. Lists are lazy and long content scrolls. Bottom actions must remain reachable in short layouts.

## Elevation & Depth

Paper pages use fine separators. Neutral navigation panels use Material tonal elevation. Floating map controls and external navigation controls use elevation to separate them from the map. Surfaces are opaque, without decorative blur.

## Shapes

Small thumbnail corners are 8 dp, actions 12 dp, grouped settings 16 dp and main panels 24 dp. Shapes follow content and Material conventions.

## Components

- `JournalTopBar`: screen title, optional Back, subtitle and actions, followed by a thin rule.
- `JournalSectionHeading`: heading semantics and optional supporting description.
- Selection rows expose checkbox/radio state. Selected places require a visible icon or outline in addition to color.
- Search keeps a persistent selection summary and explicit Bangumi action. Local result groups share the same lazy list.
- Navigation preserves provider-specific instructions/actions, compact distance/progress information and user-driven arrival/end controls.
- Appearance and image choices apply through app settings; telemetry choices remain independent and immediate.

## Do's and Don'ts

- Preserve the brief's neutral map and warm paper distinction with shared semantic type and controls.
- Keep points when images are unavailable or disabled.
- Use safe-area/keyboard insets, 48 dp controls and accessible state descriptions.
- Do not enable wallpaper branding, invent route metrics, hide points to improve performance or move coordinates to fit panels.
- Visual acceptance requires Android emulator captures; tokens alone do not prove device acceptance.
