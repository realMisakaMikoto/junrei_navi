# Product context

## Product and audience

Anitabi Navigator (巡礼手帳) is a native Android app for discovering animation filming locations and arranging a personal pilgrimage. People browse places across works, select places, plan one-provider trips, and follow road or transit guidance.

## Approved redesign brief

- Open on the discovery map after the complete first-run guide; restore active navigation when present.
- Three primary destinations: map, search, trips. About/settings are toolbar destinations. Navigation is a separate task.
- One map content panel: discovery, work, place. Returning restores each panel's expansion and scroll state.
- Search local works, places and cities separately from explicit Bangumi search.
- Map filtering and trip selection are independent. Every valid point remains represented, including in clusters.
- Phone panels adapt to a side panel on wide screens. Support dark appearance, font scaling and TalkBack.

## Durable constraints

- Material 3 controls, Android Back behavior, system fonts and at least 48 dp touch targets.
- Neutral map chrome; warm paper, ink and vermilion on lists and forms. The approved palette does not follow wallpaper colors.
- Appearance defaults to system, with manual light/dark options. Image markers default on; disabling them never removes places.
- Preserve saved trips, provider separation, privacy consent, quotas, route lifecycles and external navigation behavior.
- Only one active map. Never change geographic coordinates for visual positioning.

## Evidence boundary

This context records the approved brief. It does not assert completed visual, device, navigation or performance acceptance; those results belong in the task verification record.
