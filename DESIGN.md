---
name: Anitabi Navigator
description: Neutral map discovery and a warm paper travel journal
colors:
  paper: "#F7F6F2"
  ink: "#20231F"
  vermilion: "#C93E4F"
  on-vermilion: "#FFFFFF"
  soft-vermilion: "#F8DADD"
  supporting-ink: "#5F625D"
  paper-surface: "#FFFFFF"
  paper-container: "#F2F1EC"
  paper-rule: "#E2DFD7"
  paper-dark: "#1A1815"
  ink-dark: "#ECE1D6"
  vermilion-dark: "#FFB4A6"
  on-vermilion-dark: "#571C12"
  supporting-ink-dark: "#D0C5BA"
  paper-surface-dark: "#211E1A"
  paper-container-dark: "#27231F"
  paper-rule-dark: "#49423B"
  map-background: "#F5F6F7"
  map-surface: "#FFFFFF"
  map-ink: "#202124"
  map-supporting-ink: "#53585F"
  map-rule: "#DDE1E5"
  map-background-dark: "#17191C"
  map-surface-dark: "#202226"
  map-ink-dark: "#E2E3E5"
  map-supporting-ink-dark: "#C3C7CC"
  map-rule-dark: "#44474C"
typography:
  display:
    fontFamily: system sans-serif
    fontSize: 28sp
    lineHeight: 34sp
    fontWeight: 600
    letterSpacing: -0.3sp
  headline:
    fontFamily: system sans-serif
    fontSize: 24sp
    lineHeight: 30sp
    fontWeight: 600
    letterSpacing: -0.2sp
  title:
    fontFamily: system sans-serif
    fontSize: 20sp
    lineHeight: 26sp
    fontWeight: 600
  section-title:
    fontFamily: system sans-serif
    fontSize: 17sp
    lineHeight: 24sp
    fontWeight: 600
  row-title:
    fontFamily: system sans-serif
    fontSize: 16sp
    lineHeight: 22sp
    fontWeight: 500
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
  label:
    fontFamily: system sans-serif
    fontSize: 14sp
    lineHeight: 20sp
    fontWeight: 600
rounded:
  extra-small: 6dp
  small: 8dp
  search-field: 10dp
  medium: 12dp
  large: 16dp
  panel: 24dp
spacing:
  tight: 4dp
  inline: 8dp
  related: 12dp
  content: 16dp
  page: 20dp
  section: 24dp
components:
  journal-primary-action:
    backgroundColor: "{colors.vermilion}"
    textColor: "{colors.on-vermilion}"
    typography: "{typography.label}"
    rounded: "{rounded.medium}"
  journal-primary-action-dark:
    backgroundColor: "{colors.vermilion-dark}"
    textColor: "{colors.on-vermilion-dark}"
    typography: "{typography.label}"
    rounded: "{rounded.medium}"
  search-field:
    backgroundColor: "{colors.paper-surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.search-field}"
  journal-toolbar:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    typography: "{typography.title}"
  settings-group:
    backgroundColor: "{colors.paper-surface}"
    rounded: "{rounded.large}"
  discovery-panel:
    backgroundColor: "{colors.map-surface}"
    textColor: "{colors.map-ink}"
    rounded: "{rounded.panel}"
  discovery-panel-dark:
    backgroundColor: "{colors.map-surface-dark}"
    textColor: "{colors.map-ink-dark}"
    rounded: "{rounded.panel}"
---

# Design System: Anitabi Navigator

## Overview

**Creative North Star: "Neutral map and warm paper travel journal"**

The approved direction pairs a quiet map with warm paper, ink and vermilion on lists and forms. Places and guidance carry the map experience; typography, spacing and fine separators organize the journal. Android Material 3 supplies controls and interaction, including system Back, readable states and touch feedback.

This is a record of the implemented design and the approved constraints. The implementation source is [Theme.kt](app/src/main/java/cn/anitabi/navigator/ui/theme/Theme.kt), with shared journal components, the app shell and discovery panel described below. All redesign screenshot fixtures use synthetic content; they do not prove real provider behavior. Final AMap and provider verification remains pending, and this document does not declare full acceptance. Evidence belongs in [the acceptance record](docs/FRONTEND_REDESIGN_ACCEPTANCE.md).

**Key Characteristics:**

- Neutral map surfaces and separately designed warm light/dark journal surfaces.
- System Chinese-capable typography, semantic Material colors and native controls.
- One measured discovery panel with restored content, expansion and scroll context.
- Clear selection, source and loading states without concealing valid places.

## Colors

`AnitabiTheme` selects the paper palette; `MapSurfaceTheme` replaces map surface, text and separator roles while retaining the shared accent and type system. Use Material semantic roles in components rather than copying frontmatter literals into screens.

### Primary

Vermilion identifies primary actions and selected controls. Its light-theme container provides a soft selection surface. The dark theme uses its own pale vermilion and matching dark foreground. Error states use Material error roles, not a substitute brand accent.

### Neutral

Paper, ink and supporting ink define reading pages. White/light and warm-gray/dark surfaces distinguish groups without heavy borders. The map uses neutral surfaces with its own ink, supporting ink and fine rule. The complete surface-container levels remain in `Theme.kt`; the sidecar records their observed tonal sequence rather than inventing a palette.

Appearance defaults to the system setting, with explicit light and dark choices. Switching appearance updates the active theme. Wallpaper-derived Dynamic Color is not enabled by the approved brief.

## Typography

`FontFamily.SansSerif` uses the device's system font and Chinese fallback. All app-owned text uses scalable `sp` Material roles; the frontmatter records the explicit role sizes in `Theme.kt`. Body text uses `bodyLarge`; supporting text uses `bodyMedium`/`bodySmall`. Control labels use the shared label roles. `NumericTextStyle` adds tabular figures for changing numbers.

Titles and section headings expose heading semantics. Selected, expanded and checked states must be understandable through semantics and visible icons or outlines. Large text may wrap and extend scrollable content; avoid reducing font size to preserve a fixed composition. Provider-owned navigation instruction styling remains owned by its SDK.

## Layout

- **App shell:** map, search and trips use a Material navigation bar at compact window width and a navigation rail from the Material medium-width window class. About/settings, selection, planning and navigation are separate destinations. Top-level navigation saves and restores state; the navigation task remains independently resumable.
- **Discovery:** one content surface switches among overview, work, place and overlapping members. Each content key retains its expansion and list position; Android Back returns to the previous content. Phone portrait supports collapsed, half and expanded states. A panel becomes a side panel at content width of at least 840 dp or in landscape, with width capped at the smaller of 380 dp and 44% of available width. List mode uses the full available content width.
- **Measured space:** panel height is bounded by available space after the measured toolbar and system insets. The half state accounts for measured header, footer and visible content; short content wraps instead of filling an arbitrary height. Map padding comes from the actual toolbar and panel sizes. Keep camera commands tied to the current measured panel so restoring or expanding details preserves view context. Do not move geographic coordinates for visual placement.
- **Content widths:** search is capped at 840 dp, about/settings at 720 dp and onboarding at 640 dp. Planner forms use independently scrollable columns when wide or in landscape. Long results use lazy lists.
- **Reachability:** honor status/navigation bars, cutouts and keyboard insets. Touch targets are at least 48 dp. The journal toolbar has a 64 dp minimum content row, plus any status-bar inset; its title and subtitle may increase its height. Map controls use 48 dp targets and 8 dp spacing, moving inline when the panel or large text leaves insufficient map space.

## Elevation & Depth

Journal pages are organized by thin separators and Material surface levels. Floating map search and circular controls use 2 dp shadow elevation; the discovery panel uses 3 dp. Other persistent action surfaces retain their local Material elevation. These are native elevation values, not CSS shadow recipes. Map panels are opaque for legibility; decorative glass and broad blur are outside the approved direction.

Top-level `NavHost` transitions are explicitly disabled. Other controls use standard Compose/Material behavior; do not add custom animation that ignores Android's system animation setting. Native rendering, font scaling and motion require Android verification.

## Shapes

The shared Material shape scale is captured in the frontmatter. Thumbnails commonly use the small radius; explicit journal buttons use the medium radius; grouped settings use the large radius. The discovery panel applies the panel radius to its top corners only. Its floating search surface is a 28 dp capsule and its map controls are circles. The search text field has its own observed radius. Material controls whose shapes are not explicitly overridden retain their component defaults; the journal button radius is not a global replacement for those defaults.

## Components

### Journal structure

`JournalTopBar` provides a title, optional Back, subtitle and actions followed by a fine rule. `JournalSectionHeading` uses the shared section title with optional supporting text. Both expose heading semantics. Settings groups use native switches/radio selection and the shared paper surfaces; appearance, image markers and independent telemetry choices keep their existing immediate behavior.

### Actions and fields

Use Material filled, outlined and text buttons for primary, secondary and source actions. The explicit journal primary-action shape applies to search and the forms that request it. Native focus, pressed, disabled and error states remain Material states. The search field uses an outlined Material field with the primary role for its focused border and label.

The combined search surface queries loaded map data as input changes, with separate work/place/city groups and an honest completeness notice. Its keyboard Search action closes the keyboard and keeps the local results; only the labeled Bangumi action starts that remote search. The standalone Bangumi-only search path retains its keyboard search action. Keep the selected-trip summary available while results scroll.

### Discovery content and selection

The single panel header exposes its current title, Back when applicable, and explicit expand/collapse controls in addition to dragging. Place content presents the image, available metadata, description and source, with add/remove-trip as its primary footer action. Source attribution and the source link remain secondary. Work content keeps its summary and collapsible point groups; offer episode grouping only when that metadata exists and retain ungrouped members.

`DiscoveryPointRow` keeps a thumbnail or place placeholder, title, optional supporting line and a stateful add/remove control. Rows have an 80 dp minimum height and a fine trailing separator. Filter chips affect map visibility independently of trip selection; selected filters stay available after panning. Disabled, unavailable or failed images never remove their places. Cluster membership and accessible member lists preserve all valid points.

### Navigation and provider context

Retain the provider's native road instructions and voice, together with app-owned progress, arrival and end controls. Transit and external handoff states keep their distinct actions and truthful availability messages. Discovery styling must preserve one active map, explicit provider switching, region checks and AMap consent before map SDK use. Keep source links and privacy explanations visible in the corresponding native flows. These are existing boundaries, not new provider or SDK behavior introduced by this design record.

The sidecar's HTML/CSS samples are schematic token illustrations for the documentation panel. They are not Android components, screenshot fixtures or evidence of native visual/interaction acceptance; the Compose files remain authoritative.

## Do's and Don'ts

### Do:

- Do keep neutral map chrome and warm journal surfaces within the shared Material type and control system.
- Do preserve system Chinese font scaling, 48 dp touch targets, visible state cues and TalkBack semantics.
- Do measure panel and inset space, restore each content's view context and keep essential actions reachable.
- Do show source attribution and distinguish partial data, image failure, map failure and route failure.
- Do retain the one-map, privacy, provider and saved-trip boundaries while changing presentation.

### Don't:

- Don't enable wallpaper-derived branding or broad glass effects under this brief.
- Don't hide valid places for visual density or performance, or change coordinates to fit a panel.
- Don't make local keyboard search silently issue a Bangumi request.
- Don't invent provider metrics or treat synthetic screenshots as real maps, routes, voice or device acceptance.
- Don't declare the redesign fully accepted while real AMap and provider verification remains pending.