<!-- anchor: uiux-architecture-title -->
# UI/UX Architecture: Village Homepage
**Status:** UI_REQUIRED

<!-- anchor: 1-design-system-specification -->
## 1. Design System Specification
Village Homepage adopts a hybrid approach that marries server-rendered Qute templates with Ant Design primitives and custom TypeScript islands. The design system must hold consistency across homepage widgets, classifieds, Good Sites, profile templates, and admin tooling while staying compliant with Policy set P1-P14. Tokens are delivered to React mounts via `ConfigProvider` overrides, while Qute templates read the same values from a centralized `DesignTokensType` DTO. All tokens support light, dark, and high-contrast modes so ExperienceShell can switch themes behind feature flags without redeployment.

<!-- anchor: 1-1-color-palette -->
### 1.1 Color Palette
Color values are provided in hex and compiled into CSS custom properties served via Qute. Colors are optimized for WCAG AA contrast with both light and dark surfaces.

<!-- anchor: 1-1-1-brand-colors -->
#### 1.1.1 Brand Colors
| Token | Hex | Usage | Notes |
|-------|-----|-------|-------|
| `--color-primary-500` | #1464f4 | Action buttons, primary links, homepage widget chrome | Matches VillageCompute blue and aligns with Ant Design primary token |
| `--color-primary-600` | #0d4fd4 | Hover/focus variant for interactive states | Maintain 4.5:1 contrast on light backgrounds |
| `--color-primary-700` | #0a3baa | Active state, pressed cards, CTA outlines | Use for high-density nav elements |
| `--color-secondary-500` | #13c2c2 | Marketplace tags, accent badges, progress highlights | Balanced with teal data viz ramp |
| `--color-secondary-600` | #0fa3a3 | Hover/pressed variant for accent surfaces | Keep combined ratio > 7:1 against dark background |
| `--color-accent-magenta` | #f759ab | Alerts for AI budget, promotional upsells | Use sparingly for critical callouts |
| `--color-accent-lime` | #a0d911 | Success states, recommendation highlights, Good Sites karma badges | Provide friendly tone |
| `--color-accent-orange` | #fa8c16 | Social reconnect banner for stale tokens (matches Policy P13) | Light/dark variants available |
| `--color-surface-light` | #f7f9fc | Default page background for light mode Qute templates | Supports gridstack drop shadows |
| `--color-surface-dark` | #111a2c | Default background for dark mode, toggled per user preference | Ensures data viz readability |

<!-- anchor: 1-1-2-semantic-colors -->
#### 1.1.2 Semantic Colors
| Semantic Token | Light Mode | Dark Mode | Use Cases |
|----------------|-----------|-----------|-----------|
| `--color-success` | #52c41a | #95de64 | Confirmation toasts, listing approval states |
| `--color-warning` | #faad14 | #ffd666 | Weather alert chips, social staleness 4-7 day banner |
| `--color-error` | #f5222d | #ff7875 | Form errors, refund failures, OAuth consent issues |
| `--color-info` | #1890ff | #69c0ff | Default banner, widget hints, onboarding |
| `--color-neutral-strong` | #1f2a37 | #e6f7ff | Base text and icon color |
| `--color-neutral-muted` | #6b7280 | #94a3b8 | Secondary text, helper copy |
| `--color-neutral-border` | #d0d5dd | #2f405f | Input borders, grid outlines |
| `--color-neutral-elevated` | #ffffff | #1b2436 | Card backgrounds, widget tiles |

<!-- anchor: 1-1-3-neutral-surfaces -->
#### 1.1.3 Neutral & Surface System
The surface stack ensures depth cues across server-rendered and React-driven content.
- `surface-0`: page background (`--color-surface-light` or `--color-surface-dark`), applied to `<body>` via Qute layout.
- `surface-1`: base card (#ffffff / #1b2436) used for widget panels, Good Sites cards, listing preview shells.
- `surface-2`: raised overlays (#f4f7ff / #1f2a37) for modals, drawers, GDPR consent dialogues.
- `surface-3`: highest elevation (#eef2ff / #25304a) for sticky headers, floating action popovers, drag handles.
- Borders rely on `--color-neutral-border` with 1px hairlines on light surfaces and 0.5px + opacity on dark surfaces to maintain clarity.

<!-- anchor: 1-1-4-data-viz-ramps -->
#### 1.1.4 Data Visualization Ramps
G2Plot charts inside admin analytics, stock widgets, and weather modules adopt unified ramps.
| Ramp | Color Stops | Usage |
|------|-------------|-------|
| `stocks` | #bae7ff → #1890ff → #0050b3 | Price trends, sparkline gradients; darker step for negative values (#ff4d4f overlay) |
| `weather-temp` | #5cdbd3 → #ffd666 → #ff7a45 | Hourly and 7-day temperature charts; ensures readability for color blind users |
| `traffic-heat` | #f0f5ff → #2f54eb → #061178 | Click analytics heatmaps, Good Sites rank intensity |
| `marketplace-category` | #ffccc7, #ffe7ba, #d9f7be, #b5f5ec, #d6e4ff | Category distribution pie/column charts |
| `karma` | #fff1b8 → #ffd666 → #fa8c16 | Good Sites karma progress and moderator dashboards |
Guidelines:
1. Always pair ramp visuals with numeric labels for accessibility.
2. Use dashed baselines for thresholds (AI budget 75/90/100%).
3. Provide pattern overlays (diagonal stripes) for high-density columns to help colorblind recognition.

<!-- anchor: 1-1-5-theme-flags -->
#### 1.1.5 Theme & Feature Flag Alignment
- Theme tokens stored in `ThemeTokensType` with fields `mode`, `accent`, `contrast`. ExperienceShell reads from `user_preferences.theme` and falls back to `system` mode based on `prefers-color-scheme`.
- Feature flag `high_contrast_theme` gates alternative palette using `#000` text, `#fff` background, high-saturation focus outlines (#ff0a54).
- Beta experiments (e.g., `marketplace_modern_theme`) override only accent tokens; base neutrals stay identical for cognitive stability.
- All palette updates require running automated contrast tests (Pa11y CLI) as part of pipeline.

<!-- anchor: 1-2-typography -->
### 1.2 Typography
Typography leans on the Inter family for UI clarity and Merriweather for editorial surfaces (e.g., Your Times template). Ant Design's defaults are overridden via `ConfigProvider` and Qute layout CSS variables.

<!-- anchor: 1-2-1-font-stack -->
#### 1.2.1 Font Stack & Loading Strategy
- **Primary UI Font:** `Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif` for widgets, nav, forms.
- **Editorial Accent Font:** `"Merriweather", "Georgia", serif` for curated profile templates requiring newspaper feel.
- **Monospace:** `"IBM Plex Mono", "SFMono-Regular", Consolas, monospace` for API keys, code samples inside admin consoles.
- Fonts served via self-hosted WOFF2 to satisfy GDPR. Preload critical weights (400, 500, 600) and use `font-display: swap`.
- FOUT/FOIT mitigated by inline CSS `@supports (font-variation-settings: normal)` to toggle variable font features when supported.

<!-- anchor: 1-2-2-type-scale -->
#### 1.2.2 Type Scale
| Token | Rem | Usage |
|-------|-----|-------|
| `display-2xl` | 3.5rem | Hero statements on homepage, marketing sections |
| `display-xl` | 3rem | Public profile mastheads, marketplace hero title |
| `display-lg` | 2.5rem | Section intros, Good Sites top categories |
| `display-md` | 2.125rem | Social widget headings, admin dashboards |
| `display-sm` | 1.875rem | Card group labels |
| `text-xl` | 1.5rem | Listing detail price, curated article headlines |
| `text-lg` | 1.25rem | Card titles, widget subheads |
| `text-md` | 1.125rem | Body text default for widgets |
| `text-base` | 1rem | Form inputs, Good Sites descriptions |
| `text-sm` | 0.875rem | Metadata (timestamps, categories) |
| `text-xs` | 0.75rem | Captions, gridstack handle labels |
Tracking and leading tuned for each template: e.g., `Your Report` uses tighter letter-spacing (-0.5%) on uppercase headlines; `Your Times` uses 1.6 line-height to mimic print columns.

<!-- anchor: 1-2-3-typography-usage -->
#### 1.2.3 Usage Guidelines
- **Widget titles:** `text-lg` semibold, uppercase optional for feature flagged experiments.
- **Body copy:** `text-md` regular for readability; Good Sites cards limit to 4 lines using `line-clamp` to keep grid uniform.
- **Marketplace listings:** Titles `text-xl` with line clamp 2; descriptions `text-base` with sanitized plain text; price uses tabular numbers via CSS `font-variant-numeric: tabular-nums` for alignment.
- **Profile templates:** `Your Times` uses `Merriweather` for slots, while `public_homepage` stays with Inter to align with private homepage.
- **Numbers:** Use `font-feature-settings: "tnum" 1` for stats, `"ss01"` for zero vs O clarity.

<!-- anchor: 1-2-4-typography-localization -->
#### 1.2.4 Localization & Numerics
- Support extended Latin for bilingual content; fallback to `Noto Sans` for non-Latin entries (queued for v2 but fonts preloaded now).
- Provide `dir` awareness for future RTL; CSS includes `[dir="rtl"]` overrides for padding/margin.
- All timestamps use `dayjs` with locale packs loaded on demand via dynamic imports controlled by `data-locale` attribute.

<!-- anchor: 1-3-spacing-layout -->
### 1.3 Spacing & Layout Grid
Spacing ensures consistent rhythm between Qute layout and React widgets.

<!-- anchor: 1-3-1-spacing-scale -->
#### 1.3.1 Spacing Scale
| Token | Pixels | Usage |
|-------|--------|-------|
| `space-0` | 0 | Hard edges, table collapse |
| `space-1` | 4px | Icon padding, chip spacing |
| `space-2` | 8px | Inline form control gaps |
| `space-3` | 12px | Tag clusters, button icon separation |
| `space-4` | 16px | Default card padding |
| `space-5` | 20px | Drawer body padding |
| `space-6` | 24px | Widget gutters |
| `space-7` | 32px | Section padding desktop |
| `space-8` | 40px | Hero spacing, profile sections |
| `space-9` | 48px | Layout breakpoints between modules |
| `space-10` | 64px | Landing hero top/bottom |
| `space-11` | 80px | Public homepage optional header |
| `space-12` | 96px | Directory mega-headers |
Spacing tokens map to CSS custom properties and gridstack configuration for consistent drag handles.

<!-- anchor: 1-3-2-grid-system -->
#### 1.3.2 Grid Systems
- **Homepage Gridstack:** 12 columns desktop, 6 tablet, 2 mobile. Each column width set via CSS `grid-template-columns`. React widget editor uses `data-grid` attributes to sync.
- **Marketplace Listings:** 4-column masonry for desktop (list, filters, featured, ads), collapsing to single column on mobile with filters inside Drawer.
- **Good Sites Grid:** CSS Grid with `grid-auto-flow: dense` for bubbling algorithm; uses `minmax(280px, 1fr)` cards to keep readability.
- **Admin Analytics:** 12-column CSS grid aligning with Ant Design `Row/Col`; charts and tables align to multiples of `space-4`.
- Provide `LayoutGuidelineType` JSON for each page feeding to front-end to ensure cross-team alignment.

<!-- anchor: 1-3-3-elevation -->
#### 1.3.3 Elevation & Depth
- Shadow tokens: `shadow-xs (0 1px 2px rgba(15, 23, 42, 0.08))`, `shadow-sm`, `shadow-md`, `shadow-lg`, `shadow-xl` defined for cards, modals, floating editors.
- Gridstack drag preview uses `shadow-xl` + `outline: 2px dashed #1464f4` to meet ADA guidelines.
- Elevated surfaces include `backdrop-filter: blur(8px)` for sticky mastheads on profiles; degrade gracefully on unsupported browsers.
- For high-contrast mode, replace shadow cues with hairline borders + dual-tone backgrounds to maintain depth perception for low-vision users.

<!-- anchor: 1-4-component-tokens -->
### 1.4 Component Tokens & Micro-Interactions
Tokens standardize corner radius, stroke width, icon sizing, and transitions so Qute markup and React components behave identically.

<!-- anchor: 1-4-1-radii -->
#### 1.4.1 Radius & Border Tokens
| Token | Pixels | Usage |
|-------|--------|-------|
| `radius-none` | 0 | Tables, Good Sites newspaper template edges |
| `radius-sm` | 4 | Inputs, buttons |
| `radius-md` | 8 | Cards, widget panels |
| `radius-lg` | 16 | Modals, feature hero panels |
| `radius-pill` | 999px | Tags, badges, quick filters |
- Border width tokens: `border-1` (1px) for skeletons, `border-2` for highlight, `border-focus` (2px primary) for keyboard focus rings.

<!-- anchor: 1-4-2-shadow-motion -->
#### 1.4.2 Shadow & Motion Tokens
- Transition tokens: `transition-fast (120ms cubic-bezier(0.4, 0, 0.2, 1))`, `transition-base (200ms)`, `transition-slow (320ms)`. Use `prefers-reduced-motion` media query to disable transforms.
- Motion libraries rely on CSS transitions; avoid heavy JS animations to keep Quarkus-rendered DOM lightweight.
- Drag interactions for gridstack use `transform: scale(1.02)` and `box-shadow` for affordance.

<!-- anchor: 1-4-3-iconography -->
#### 1.4.3 Iconography & Illustration Guidelines
- Icons use Ant Design icon set; restrictions: no filled icons for destructive actions to avoid color-blind confusion.
- Provide 24px bounding box, stroke width 2px, align to `space-1` grid.
- Social providers maintain brand color for recognition but place on neutral shapes for contrast.
- Illustrations for empty states follow duotone palette (`#1464f4` + `#d6e4ff`) to avoid noise.

<!-- anchor: 1-4-4-data-density -->
#### 1.4.4 Data Density & Mode Switching
- Introduce `density` token per widget (`comfortable`, `compact`) stored inside `UserPreferencesType.widgetConfigs`. Compact mode reduces padding to `space-2`, table row height to 36px, and hides card shadows.
- Admin analytics defaults to compact to fit more rows; marketplace list view uses comfortable for readability.
- Provide toggles accessible from settings; respects `prefers-reduced-motion` to avoid abrupt reflow; transitions occur with `height` animations limited to <200ms.

<!-- anchor: 1-5-accessibility-visual -->
### 1.5 Accessibility-First Visual Treatments
- Focus outlines: `outline: 3px solid var(--color-primary-500)` with `outline-offset: 2px` to ensure keyboard users can navigate gridstack handles and Antd inputs.
- Provide `aria-live="polite"` for widget drop announcements; custom React wrappers broadcast layout changes.
- Provide text alternative tokens for icons; e.g., quick link cards store `aria-label` describing destination.
- Color-blind safe mode toggles pattern overlays in charts using `defs` textures and stripes for top/bottom thresholds.
- All banners referencing GDPR or Social reconnect include `role="status"` and inline actions accessible via keyboard.

<!-- anchor: 1-6-content-voice -->
### 1.6 Content Voice & Messaging Tokens
- **Tone:** Friendly, helpful, transparent—mirrors compliance obligations without legal jargon overload.
- **Voice tokens:** `tone-default`, `tone-compliance`, `tone-celebratory`, `tone-alert`. Each maps to copy guidelines stored in Contentful (if used) but currently maintained as JSON for Qute templates.
- Microcopy for AI budget warnings references percent thresholds, e.g., "Claude tagging paused at 90% of budget" to maintain clarity.
- Provide `CTA Verb` library: "Customize", "Reconnect", "Review", "Explore" to maintain consistent action language.

<!-- anchor: 1-7-media-guidelines -->
### 1.7 Media, Imagery & Screenshot Treatment
- Follow Policy P4: thumbnails 320x200 WebP, full 1280x800; UI must show `loading=lazy` for thumbnails, lightbox for full versions.
- Provide `screenshot_version_badge` component to show version numbers; color-coded by recency.
- Marketplace listing galleries limit to 12 images with `aria-roledescription="carousel"`; arrows sized 48px for touch.
- Social widget uses aspect ratio boxes (1:1 for Instagram, 1.91:1 for Facebook); maintain placeholder gradient while fetching cached posts.

<!-- anchor: 1-8-illustration-guidelines -->
### 1.8 Illustration & Avatar Treatment
- Default avatar uses circle with gradient background (#1464f4 → #13c2c2) and uppercase initials.
- Public profiles may include custom hero images stored via StorageGateway; UI enforces 1920x600 recommended size with cropping tool using `react-easy-crop` inside React island.
- Good Sites category icons follow 32px grid, simple line art referencing directory topic to maintain low visual noise.

<!-- anchor: 1-9-data-ink-ratio -->
### 1.9 Data-Ink & Content Hierarchy Rules
- Limit each widget to maximum of three simultaneously visible visual weights (e.g., bold headline, medium metadata, regular body) to prevent clutter.
- Provide `metadata rails` for timestamp, feed origin, AI tags; displayed as pill stack with `text-xs` size.
- `Gridstack` ensures top-left priority for search bar, weather, stocks per default layout; order accessible via keyboard reorder controls.

<!-- anchor: 1-10-copywriting -->
### 1.10 Copywriting Checklists
1. Always describe data merges, AI tagging, or consent using action-first sentences.
2. Provide fallback text for empty states like "No personalized feeds yet" with CTA to open feed management modal.
3. Use sentence case except for Good Sites `Your Report` template that intentionally uses uppercase for broadcast aesthetic.
4. Provide locale-specific date/time formats using `dayjs` with `Intl.RelativeTimeFormat` fallback for browsers lacking full support.
5. Document all copy variants in `/docs/content-matrix.md` to keep parity between Qute and React text.

<!-- anchor: 2-component-architecture -->
## 2. Component Architecture
Components follow an Atomic Design spectrum while binding to Ant Design primitives. Server-rendered Qute composes organisms and templates. React islands provide high interactivity (gridstack controls, chart dashboards, profile editors).

<!-- anchor: 2-1-architecture-overview -->
### 2.1 Overview & Methodology
- **Atoms:** Buttons, inputs, tags, avatar chips, icons, skeleton loaders. These map directly to Ant Design components with theme overrides.
- **Molecules:** Search bar, weather summary stack, stock row, social post cell, Good Sites vote control, marketplace filter item.
- **Organisms:** News widget, marketplace listing card, Good Sites category grid, public profile header, analytics charts.
- **Templates:** Homepage grid, marketplace browse layout, Good Sites directory page, admin analytics console, profile templates (public_homepage, your_times, your_report).
- Components versioned via `ComponentManifestType` stored in docs to align cross-team contributions.

<!-- anchor: 2-2-atoms -->
### 2.2 Atom Inventory
| Atom | Description | Props | Accessibility Notes |
|------|-------------|-------|---------------------|
| `VcButton` | Antd Button wrapper with theme tokens | `variant`, `size`, `icon`, `loading`, `danger` | Auto-adds `aria-busy` when loading |
| `VcIcon` | Icon renderer bridging Ant Design icons and custom glyphs | `name`, `ariaLabel`, `tone` | Provide `role="img"` with `aria-hidden` when decorative |
| `VcInput` | Shared input field | `type`, `prefix`, `suffix`, `status` | Associates `<label>` and `aria-describedby` |
| `VcSelect` | Antd Select with async search support | `mode`, `options`, `loading`, `virtual` | Keyboard accessible multi-select |
| `VcCheckbox` | Uses Antd Checkbox with `aria-live` for validation |
| `VcRadio` | Styled radio groups for feed sort preferences |
| `VcSwitch` | Feature toggles (e.g., enable AI tagging) |
| `VcAvatar` | Initials or image; supports `badge` for notifications |
| `VcBadge` | Status indicator for tokens, listing statuses |
| `VcTag` | Category chips (news topics, marketplace categories) |
| `VcTooltip` | Info popovers with delay 200ms |
| `VcSkeleton` | Loading placeholder; shapes for cards, tables |
| `VcProgress` | Horizontal progress bars for AI budget usage |
| `VcDivider` | Visual separators |
| `VcBreadcrumb` | Navigation crumb for Good Sites categories |
| `VcPagination` | Standard pagination aligning with server-side data |
| `VcUpload` | Image uploader for marketplace listings |
| `VcAlert` | Banners for consent, tokens, AI budget |
| `VcStatistic` | Antd Statistic for key KPIs |
| `VcBadgeCount` | Number bubble for notifications |
| `VcChipToggle` | Custom pill for quick filters |
| `VcIconButton` | Square action button for gridstack handles |
| `VcStepperDot` | Step indicator for onboarding |
| `VcDataLabel` | Inline label-value pair |
| `VcTagList` | Scrollable list of tags with focus wrap |
| `VcEmpty` | Standard empty state placeholder |
| `VcSpinner` | Inline spinner for asynchronous fetches |
| `VcHotkey` | Visual representation of keyboard shortcuts |
| `VcInlineLink` | Styled anchor ensuring tracked URLs |
| `VcColorSwatch` | Preview for theme editor |
| `VcLegendItem` | Chart legend entry with marker |
| `VcIconBadge` | Icon + badge used in nav |
| `VcProgressRing` | Circular progress for screenshot queue |
| `VcToast` | Notification container hooking into Antd message API |
| `VcDropdownTrigger` | For overflow menus |
| `VcStepperNav` | Next/back buttons cluster |
This inventory ensures 30+ atoms, allowing mix-and-match across modules.

<!-- anchor: 2-3-molecules -->
### 2.3 Molecule Library
| Molecule | Composition | Variants | Notes |
|----------|-------------|----------|-------|
| `SearchBar` | `VcInput` + `VcButton` + quick filters | Default, Marketplace, Directory | Ties into `/track/click` for query logging |
| `NewsHeadline` | Avatar + title + AI tags + metadata rail | Normal, Featured | Supports `aria-expanded` for summary collapse |
| `WeatherSummary` | Icon stack + temperature + text rows | Metric/Imperial | Pulls from WeatherCache |
| `HourlyScroller` | Horizontal list of `VcCard`s | Touch-friendly | Snap scrolling with Buttons |
| `StockRow` | Symbol, price, percent change, sparkline | Watchlist, Mini detail | Inline color-coded stats |
| `SocialPost` | Media card + caption + CTA | Instagram, Facebook | Banner overlay for stale tokens |
| `QuickLinksGrid` | Icon buttons linking to saved URLs | 2xN layout | Drag handles for reorder |
| `WidgetToolbar` | Title + dropdown + action buttons | Standard, Minimal | Houses FeatureFlag toggles |
| `FeedFilterBar` | Tag list + sort select + refresh button | News, Good Sites | Works with RateLimitService |
| `MarketplaceFilterPanel` | Category tree + radius slider + price inputs | Desktop (sticky), Mobile (Drawer) | Sends `POST /api/marketplace/search` |
| `ListingGallery` | Carousel + thumbnails + upload | Compose with `VcUpload` | Enforces image count limit |
| `ListingCardMeta` | Title, price, badges, location | Variation for promoted/bumped | Shows expiration countdown |
| `ProfileSlotEditor` | Drag interface for `Your Times` slots | Slot types: headline, secondary, sidebar, grid | Interacts with curated articles API |
| `ProfileHero` | Avatar + display name + actions | Public, Owner view | Shows publish status |
| `DirectoryVoteControl` | Upvote/downvote buttons + score | Standard, Compact | Rate-limited interactions |
| `DirectorySubmissionForm` | Input, select, screenshot preview | Steps: URL > Metadata > Category > Review | Inline validation |
| `ScreenshotVersionTimeline` | Timeline list of captures | Thumbnail, Metadata rows | Link to R2 download |
| `ConsentModal` | `VcAlert` + `VcCheckbox` + `VcButton` | Merge consent, analytics | Follows P1 requirements |
| `FeatureFlagToggle` | Switch + description + rollout meter | Admin only | Shows hashed cohort result |
| `AnalyticsSummaryRow` | Statistic + sparkline + trend arrow | Clicks, Unique users, Budget | `aria-live` updates |
| `ClickTrackingTable` | Table + filter controls + export button | Category, Type views | Connects to `/admin/api/analytics` |
| `JobQueueMonitor` | List of queue stats | DEFAULT/HIGH/LOW/BULK/SCREENSHOT | Highlights backlog thresholds |
| `WeatherAlertCard` | Icon + severity + CTA | Info, Warning, Alert | Colors tied to semantic tokens |
| `AIUsageBanner` | Progress + text + manage link | Inline (content), Modal (admin) | Reflect P2/P10 states |
| `MarketplaceFeeBreakdown` | Table + badges | Category-specific fees + promotions | Connect to Stripe UI |
| `DirectoryBreadcrumb` | `VcBreadcrumb` + `VcTag` bubble | Show bubbled links counts |
| `ProfileTemplatePicker` | Cards with preview images | 3 templates + future expansions |
| `LinkCuratorList` | Table of feed items with actions | Filtered by source/topic | Bulk select to assign slots |
| `RateLimitNotice` | Banner with timer | Search, Votes, Submissions | Shows `Retry-After` threshold |
| `NotificationToast` | Toast with CTA | Onboarding, Alerts | Auto-dismiss with `aria-live`
Molecules abstract Ant Design components for reusability.

<!-- anchor: 2-4-organisms -->
### 2.4 Organism Catalogue
| Organism | Description | Key Child Molecules | Data Sources |
|----------|-------------|---------------------|--------------|
| `HomepageNewsWidget` | Displays interest-matched stories, AI tags | WidgetToolbar, NewsHeadline list, FeedFilterBar | FeedAggregationService, AiTaggingService |
| `WeatherWidget` | Current + hourly + 7-day + alerts | WeatherSummary, HourlyScroller, WeatherAlertCard | WeatherService |
| `StocksWidget` | Watchlist management, sparkline charts | WidgetToolbar, StockRow list, G2Plot area chart | StockService (Alpha Vantage) |
| `SocialWidget` | Instagram/Facebook posts with reconnect banner | WidgetToolbar, SocialPost grid, Banner | SocialIntegrationService |
| `QuickLinksWidget` | Bookmark grid with drag handles | QuickLinksGrid, ConsentModal (if linking to external) | UserPreferencesType |
| `SearchHero` | Search bar + feature flag toggles + suggestions | SearchBar, TagList | SearchService, FeatureFlagService |
| `MarketplaceCategoryList` | Craigslist-style list of categories | MarketplaceFilterPanel, VcTagList, DirectoryBreadcrumb (for path) | MarketplaceService |
| `MarketplaceListingFeed` | Card list with inline filters and promoted slots | ListingCardMeta, ListingGallery preview, RateLimitNotice | MarketplaceService, ClickTracking |
| `MarketplaceListingDetail` | Gallery + description + contact CTA + map | ListingGallery, Contact panel, FeatureFlag toggles | MarketplaceService, StorageGateway |
| `ListingComposer` | Multi-step React flow inside Qute page | DirectorySubmissionForm derivative, VcStepperNav | MarketplaceService + Stripe |
| `GoodSitesCategoryGrid` | Showcase categories with counts | DirectoryBreadcrumb, VcCard, VcBadge | DirectoryService |
| `GoodSitesListing` | List of site cards + vote controls + screenshot preview | DirectoryVoteControl, ScreenshotVersionTimeline | DirectoryService, ScreenshotService |
| `GoodSitesModerationQueue` | Table of pending submissions, inline actions | VcTable, VcDrawer, RateLimitNotice | DirectoryService |
| `ProfileBuilder` | Template select + slot assignment + style controls | ProfileTemplatePicker, ProfileSlotEditor, VcColorSwatch | ProfileService |
| `PublicHomepageTemplate` | Displays curated widget grid to visitors | QuickLinksGrid, WeatherWidget, HeadlineBlock | ProfileService, News, Weather |
| `YourTimesTemplate` | Newspaper layout with curated slots | ProfileHero, NewsHeadline, DirectoryBreadcrumb | ProfileService, Feed items |
| `YourReportTemplate` | Link aggregator with columns + hero photo | LinkCuratorList, MarketplaceCategoryList derivative | ProfileService |
| `AdminAnalyticsDashboard` | Overview cards + charts + tables | AnalyticsSummaryRow, G2Plot charts, ClickTrackingTable | ClickTrackingService |
| `FeatureFlagConsole` | Manage flags, rollouts, analytics | FeatureFlagToggle, DirectoryBreadcrumb (for audits) | FeatureFlagService |
| `JobQueueConsole` | Visualize delayed job depth | JobQueueMonitor, VcTable | DelayedJobService |
| `ScreenshotOpsPanel` | Manage screenshot queue, view versions | ScreenshotVersionTimeline, AnalyticsSummaryRow | ScreenshotService |
| `ConsentExperience` | Modal + stepper for OAuth merge | ConsentModal, VcCheckbox, VcButton | AuthIdentityService |
| `AIUsageConsole` | Details AI usage, queue states | AIUsageBanner, VcTable, Column chart | AiTaggingBudgetService |
| `RateLimitConsole` | Manage tiers and view violations | VcTable, VcForm, BadgeList | RateLimitService |
Organisms align with backend services, ensuring data/responsibility boundaries.

<!-- anchor: 2-5-templates-layouts -->
### 2.5 Templates & Layout Blueprints
- **Homepage Template:** Qute layout `homepage.html` defines header (logo, nav, user controls), `#gridstack` container, and mount points for editing toolbar. React island `gridstack-editor.tsx` loads only for authenticated users or when editing, respecting `marketplace` and `good_sites` feature flags.
- **Marketplace Template:** `marketplace.html` sets two-column layout with sticky left filter column. On mobile, filter button triggers React Drawer. Search results area streams server-rendered listing cards but mounts `ListingActionsIsland` to handle bookmarking and share interactions.
- **Good Sites Template:** Breadcrumb header + dual column (subcategories + list). React `vote-control.tsx` handles asynchronous voting while list remains server-rendered for SEO.
- **Profile Templates:** Each template has dedicated partial. `public_homepage` uses gridstack configuration stored separately from private layout; `your_times` uses CSS grid replicating masthead/newspaper layout; `your_report` uses flex columns with uppercase typography.
- **Admin Template:** `admin/base.html` includes nav with sections (Analytics, Feeds, Flags, Jobs). React islands mount charts, job monitors, etc.
- **Auth Template:** Lightweight layout for bootstrap & consent forms with central card.

<!-- anchor: 2-6-gridstack-integration -->
### 2.6 Gridstack Integration & Drag Handles
- `gridstack.js` configured with `acceptWidgets: true`, `float: true` for overlapping prevention, `cellHeight` responsive to breakpoints via CSS variables.
- Drag handles use `VcIconButton` with `aria-label="Move widget"`. Provide `aria-describedby` referencing instructions for screen readers.
- Keyboard interactions: `Tab` focus on toolbar, `Space/Enter` toggles move mode, arrow keys adjust `x/y`. Provide `LiveRegion` updates describing new coordinates.
- Layout state serialized to JSON via `UserPreferencesType.layout.widgets`. React island ensures diffing to avoid heavy re-renders.

<!-- anchor: 2-7-react-mount-strategy -->
### 2.7 React Mount Strategy & Data Contracts
- All islands registered in `mounts.ts`; `data-component` attribute identifies component, `data-props` includes JSON configuration (validated via `zod`-like guards before render).
- Example: `<div data-component="StocksWidgetMount" data-props='{"endpoint":"/api/widgets/stocks","featureFlag":"stocks_widget"}'></div>`.
- React components fetch data via `fetchWithCsrf` helper, caching results where possible and streaming skeletons until data arrives.
- ConfigProvider applies tokens defined earlier. Each island sends instrumentation events via shared `useClickTracking` hook.

<!-- anchor: 2-8-component-lifecycle -->
### 2.8 Component Lifecycle & State Machine Rules
- Widgets adopt `loading → ready → error → empty` states, each with consistent visuals (skeleton, success state, error card, empty placeholder). React components store `status` in internal state and expose via `data-status` attribute for CSS.
- Organisms interacting with delayed jobs (e.g., screenshot queue) include `refresh` button with `isPolling` state; use `setInterval` with cleanup to avoid memory leaks.
- Marketplace composer uses finite state machine: `draft → uploading → validating → payment_pending → published`. UI conditions (buttons enabled/disabled) align with backend statuses.

<!-- anchor: 2-9-component-hierarchy-diagram -->
### 2.9 Component Hierarchy Diagram (PlantUML)
~~~plantuml
@startuml
skinparam backgroundColor #0a0f1b
skinparam node {
  BackgroundColor #14213d
  BorderColor #fca311
  FontColor white
}
skinparam ArrowColor #fca311

package "Experience Shell (Qute)" {
  [Layout Templates]
  [Server Widgets]
}

package "React Islands" {
  [Gridstack Editor]
  [Widget Organisms]
  [Marketplace Composer]
  [Good Sites Voting]
  [Profile Builder]
  [Admin Analytics]
}

package "Atoms/Molecules" {
  [Ant Design Primitives]
  [VC Tokens]
}

package "Services DTOs" {
  [UserPreferencesType]
  [WidgetDataType]
  [MarketplaceListingType]
  [DirectorySiteType]
  [ProfileTemplateConfigType]
}

[Layout Templates] --> [Gridstack Editor]
[Layout Templates] --> [Marketplace Composer]
[Layout Templates] --> [Good Sites Voting]
[Layout Templates] --> [Profile Builder]
[Layout Templates] --> [Admin Analytics]
[Gridstack Editor] --> [Widget Organisms]
[Widget Organisms] --> [Atoms/Molecules]
[Marketplace Composer] --> [Atoms/Molecules]
[Good Sites Voting] --> [Atoms/Molecules]
[Profile Builder] --> [Atoms/Molecules]
[Admin Analytics] --> [Atoms/Molecules]
[Services DTOs] --> [Layout Templates]
[Services DTOs] --> [React Islands]
@enduml
~~~

<!-- anchor: 2-10-component-accessibility -->
### 2.10 Component Accessibility & Error Patterns
- Each interactive organism exposes `aria` attributes describing state: e.g., `WidgetToolbar` includes `aria-controls` referencing accordion body.
- All asynchronous actions (vote, save layout, update preferences) show inline success/error toast with `aria-live` updates and fallback text near origin control.
- Provide print styles for Good Sites and profile templates by reusing server-rendered static HTML; React islands degrade gracefully.

<!-- anchor: 3-application-structure -->
## 3. Application Structure & User Flows
The application merges multiple feature domains. Server-rendered routes ensure SEO for public content, while React islands upgrade interactions only where necessary. The table below enumerates major routes and their page-level composition.

<!-- anchor: 3-1-route-definitions -->
### 3.1 Route Definitions
| Route | Description | Access | Primary Components |
|-------|-------------|--------|--------------------|
| `/` | Homepage (anonymous default or personalized) | Public / Auth | SearchHero, HomepageNewsWidget, WeatherWidget, StocksWidget, QuickLinksWidget |
| `/login` | OAuth entry | Public | Auth template, ConsentModal |
| `/bootstrap` | First admin creation | Restricted | Auth template |
| `/settings/profile` | Profile settings | Auth | ProfileBuilder, ProfileTemplatePicker |
| `/settings/preferences` | Widget/customization preferences | Auth | WidgetToolbar, VcForm |
| `/marketplace` | Marketplace landing with categories | Public | MarketplaceCategoryList, MarketplaceFilterPanel |
| `/marketplace/for-sale` etc. | Category-specific listing feed | Public | MarketplaceListingFeed |
| `/marketplace/listing/{id}` | Listing detail | Public | MarketplaceListingDetail |
| `/marketplace/post` | Create listing | Auth | ListingComposer |
| `/marketplace/my-listings` | Manage user listings | Auth | VcTable, ListingCardMeta |
| `/marketplace/payments` | Promotion and refunds dashboard | Auth (seller) | MarketplaceFeeBreakdown |
| `/good-sites` | Directory homepage | Public | GoodSitesCategoryGrid |
| `/good-sites/{categorySlug}` | Category detail | Public | GoodSitesListing, DirectoryBreadcrumb |
| `/good-sites/site/{id}` | Site detail | Public | ScreenshotVersionTimeline |
| `/good-sites/submit` | Submit site | Auth | DirectorySubmissionForm |
| `/good-sites/moderate` | Moderation console | Moderator/Admin | GoodSitesModerationQueue |
| `/u/{username}` | Public profile page | Public | Template-specific organisms |
| `/u/{username}/edit` | Profile editing | Owner | ProfileBuilder |
| `/u/{username}/curate` | Curated article assignment | Owner | ProfileSlotEditor, LinkCuratorList |
| `/admin` | Admin landing (analytics) | Roles: super_admin, ops, read_only | AdminAnalyticsDashboard |
| `/admin/feeds` | RSS management | Admin | VcTable, VcForm |
| `/admin/feature-flags` | Feature flag console | Admin | FeatureFlagConsole |
| `/admin/jobs` | Job queue monitor | Admin | JobQueueConsole |
| `/admin/ai-budget` | AI usage console | Admin | AIUsageConsole |
| `/admin/rate-limits` | Rate limit console | Admin | RateLimitConsole |
| `/api/widgets/{type}` | JSON endpoints for widgets | Auth/Anon with rate limits | DTO responses |
| `/track/click` | Click tracking redirect | Public | ClickTrackingResource |
| `/sitemap*.xml` | SEO sitemap endpoints | Public | SitemapResource |
| `/webhooks/stripe` | Stripe webhook | System | JSON handler |
This is not exhaustive but covers 20+ critical pages per requirement for large scope.

<!-- anchor: 3-2-page-templates -->
### 3.2 Page Layout Blueprints
- **Homepage:** Sticky header with nav (Home, Marketplace, Good Sites, Profiles). Secondary nav reveals Quick Links + Getting Started tasks based on personalization status. Footer includes sitemap, consent, contact info.
- **Marketplace:** Left column (on desktop) contains filters with `position: sticky` and accessible headings; right column houses listing feed. On mobile, filter button sits on top and opens Drawer via React.
- **Good Sites:** Breadcrumb at top, category description, filter toggles (sort by score/date). Layout ensures category nav stays accessible at top with `position: sticky` for long lists.
- **Profile Pages:** Each template has hero region, share button, and canonical meta tags. Public pages include follow/share controls, widget sections, curated article lists. Provide `Print` style.
- **Admin:** Primary nav left column (collapsed on smaller screens). Content area uses 12-column grid with cards + charts. Each card includes quick actions (download CSV, view details).
- **Settings:** Tabbed interface (Preferences, Integrations, Privacy). Each tab uses `VcForm` and `VcCard` wrappers for clarity.

<!-- anchor: 3-3-user-journeys -->
### 3.3 Critical User Journeys Overview
1. **Anonymous Personalization:** Visitor receives `vu_anon_id`, customizes widget layout, preferences stored server-side; when logging in, merge occurs with consent.
2. **OAuth Merge with Consent:** Existing anonymous data merges into authenticated account after explicit consent referencing Policy P1.
3. **Widget Customization & Drag:** Authenticated user reorders widgets, adjusts size, chooses widget-specific settings.
4. **Marketplace Listing Creation:** Seller completes multi-step form, uploads images, pays via Stripe (if necessary), publishes listing, receives reminder notifications.
5. **Marketplace Discovery & Contact:** Buyer filters listings by category/radius, views details, sends masked email inquiry, receives confirmation.
6. **Good Sites Submission & Voting:** User submits site, metadata captured, moderators review, voting occurs, scoreboard updates.
7. **Profile Template Curation:** User designs public profile using chosen template, drags articles, toggles visibility, publishes page.
8. **Social Token Refresh & Banner Flow:** Social widget detects stale token, shows banner with CTA to reconnect, fallback to cached posts after 7 days.
9. **AI Budget Oversight for Admins:** Admin visits analytics, checks AI usage, toggles flag if budgets reached, sees alerts.
10. **Feature Flag Experiment Rollout:** Admin adjusts rollout percentage, monitors cohort analytics, ensures stable hash assignments.

<!-- anchor: 3-4-detailed-flows -->
### 3.4 Detailed Flow Narratives
- **Anonymous Personalization:**
  1. Visitor loads `/` and gets default layout (Search, News, Weather, Stocks, Quick Links). `gridstack` disabled for drag but `Customize` CTA prompts login or start customizing with limited features.
  2. On customizing, `AnonymousPreferenceModal` explains cookie usage; upon acceptance, layout editing unlocks with persistent banner reminding to log in for merge.
  3. Layout changes saved via `/api/preferences/layout` associated with anonymous user; success toast show `Saved for this browser` copy.
  4. When the user logs in via OAuth, consent modal describes merge; once accepted, layout persists and `account_merge_audit` recorded.
- **OAuth Merge with Consent:**
  1. After OAuth redirect, server detects `vu_anon_id` cookie. Qute renders `ConsentModal` describing data types (widgets, topics, drafts).
  2. Modal includes checkboxes: `Merge my data`, `Delete anonymous data after merge`, `Keep layout only`. API ensures only valid combos stored.
  3. On acceptance, UI shows progress indicator; if error occurs, fallback page instructs contacting support referencing audit ID.
- **Widget Customization:**
  1. `Edit Layout` button toggles `gridstack` edit mode; instructions overlay describes drag handles.
  2. Widgets show toolbar with icons for configuration, duplication, removal.
  3. Save triggers validation ensuring widgets within bounds; error outline displayed for invalid positions.
  4. After save, `gridstack` switches to view mode; `undo` action available for 3 minutes to revert to last saved state.
- **Marketplace Listing Creation:**
  1. Step 1: Details (title, description, category). Inline validation ensures length and prohibits banned keywords (per Community policy).
  2. Step 2: Media (image uploader). UI shows progress bars for each file, allows reorder, enforces 12 image limit.
  3. Step 3: Pricing & Terms (price fields, contact method). `VcCheckbox` toggles `is_free`, `contact_for_price`.
  4. Step 4: Location (map search, city selection). `LocationSearchMolecule` queries PostGIS via API.
  5. Step 5: Review & Publish. If fee required, Stripe Payment Element appears; success leads to confirmation screen with listing link.
  6. Post-publish: Toast invites share; detail page shows `featured` badge if purchased.
- **Marketplace Discovery & Contact:**
  1. Buyer selects category, radius, and filters (price range, has images). UI updates query string for shareable search.
  2. List view uses `VcCard` with preview images; promoted listings pinned with icon and `Sponsored` label.
  3. Click `Contact` opens modal requiring login (if anonymous). After sending message, success toast clarifies message masked email process per Policy.
  4. Rate limiting surfaces when buyer sends >X messages/min; `RateLimitNotice` shows timer.
- **Good Sites Submission & Voting:**
  1. Submission page includes multi-step form (URL → Metadata preview → Category selection → Confirmation). Screenshot preview loads asynchronously.
  2. Trusted users auto-publish; others see `Pending moderation` badge.
  3. Category pages show vote controls; clicking updates score instantly with optimistic UI, fallback to server state on failure.
  4. Moderators use queue view with bulk actions; decisions include optional note sent to submitter via email template.
- **Profile Template Curation:**
  1. Profile builder shows template previews; selecting one loads layout editor.
  2. For `Your Times`, slot list appears; user drags feed items from `LinkCuratorList` onto slots (drag + keyboard accessible). Each slot shows metadata, custom fields (headline, blurb, image).
  3. For `Your Report`, UI shows columns; user adds section headers and links; toggles uppercase option.
  4. Publishing requires `is_published` toggle; preview link provided for share.
- **Social Token Refresh Flow:**
  1. Social widget checks `SocialWidgetStateType`; if `isStale`, top banner with color-coded level appears.
  2. Banner includes CTA `Reconnect your account`, linking to OAuth flow. Also includes `Hide widget` and `Delete data` options to comply with Policy P5.
  3. After 7 days of staleness, banner color shifts to alert (#fa8c16), plus new copy "Showing posts from X days ago".
- **AI Budget Oversight:**
  1. Admin sees `AIUsageBanner` with progress meter and thresholds markers at 75/90/100.
  2. Table lists job batches with cost per request; includes CTA to adjust batch sizes or pause features.
  3. Alerts triggered when hitting thresholds send Notification toast + email; admin can open `AiTaggingJobHandler` queue view for details.
- **Feature Flag Experiment:**
  1. Admin toggles `stocks_widget` flag or adjusts rollout percentage slider.
  2. UI shows hashed cohort example and analytics summary for conversions.
  3. Audit history displayed in Drawer showing previous states.

<!-- anchor: 3-5-user-flow-diagram -->
### 3.5 Critical User Journeys (PlantUML)
~~~plantuml
@startuml
skinparam backgroundColor #0e1116
skinparam node {
  BackgroundColor #14213d
  FontColor white
}
skinparam ArrowColor #ffb703

|Anonymous User|
start
:Visit "/" homepage;
:Customize layout (limited);
if (Consent accepted?) then (yes)
  :Save layout to anonymous profile;
else (no)
  :Show reminder banner;
endif
:Click "Sign in";
|OIDC Provider|
:Authenticate via OAuth;
|Authenticated User|
:Return to app with tokens;
:Display GDPR consent modal;
if (User merges data?) then (merge)
  :Call /api/merge-anonymous;
  :Persist audit record;
else (discard)
  :Delete anonymous prefs;
endif
:Show personalized homepage;
stop

|Seller|
start
:Open /marketplace/post;
:Complete steps (Details->Media->Pricing->Location);
:Upload images to R2 via signed URLs;
:Review listing;
if (Category has fee?) then (yes)
  |Stripe|
  :Collect payment intent;
  |Seller|
  :Await confirmation;
endif
:Publish listing;
:Trigger email + listing preview;
stop

|Directory Contributor|
start
:Submit site via form;
:Fetch metadata + screenshot;
if (karma >= 10?) then (trusted)
  :Auto-publish entry;
else (moderated)
  |Moderator|
  :Review submission;
  if (Approve?) then (yes)
    :Publish site;
  else (reject)
    :Send rejection notice;
  endif
endif
:Visitors vote via Good Sites listing;
stop
@enduml
~~~

<!-- anchor: 3-6-empty-error-states -->
### 3.6 Empty & Error State Playbook
- **Homepage Widgets:** Empty state cards show friendly illustration, summary text, CTA to connect data (e.g., "Add stocks to your watchlist"). Errors show inline message with `Retry` button; repeated failures log to ClickTracking for analytics.
- **Marketplace Search:** If filters yield no results, show suggestions (expand radius, adjust price). Provide quick link to saved searches.
- **Good Sites Category:** When no submissions exist, highlight `Submit a site` CTA and show guidelines.
- **Profiles:** Unpublished profiles show `Private preview` overlay. Visitors hitting unpublished slug get 404 with suggestion to explore Good Sites.
- **Admin:** If analytics endpoints fail (e.g., Elasticsearch down), show `VcAlert` with fallback metrics sourced from Postgres rollups.

<!-- anchor: 3-7-multi-device-flows -->
### 3.7 Multi-Device Considerations
- Mobile nav condenses to bottom tab bar (Home, Marketplace, Good Sites, Profiles, Menu). Drag interactions replaced with list reorder controls using up/down buttons for accessibility.
- Tablet retains gridstack but reduces columns to 6; widgets auto-span even numbers to maintain readability.
- Desktop uses multi-column layouts with `space-7` sections for comfortable reading.

<!-- anchor: 4-cross-cutting -->
## 4. Cross-Cutting Concerns

<!-- anchor: 4-1-state-management -->
### 4.1 State Management Strategy
- Server remains source of truth; Qute template receives DTOs with `version` field.
- React islands use lightweight context + hooks rather than heavy libraries. Example: `useWidgetPreferences` fetches `/api/preferences` and caches in `sessionStorage` with ETag.
- Gridstack updates use `postMessage` bus for cross-iframe (if admin customizing inside embed) though default inline.
- Admin dashboards use `SWR`-style fetching (custom hook) with background refresh; each fetch includes `If-None-Match` header from ETag; fallback to cached data when offline.
- Shared `EventBus` dispatches `layoutUpdated`, `widgetReload`, `tokenRefresh` events. `document.dispatchEvent` ensures Qute-supplied modules can listen without React dependency.

<!-- anchor: 4-2-responsive-design -->
### 4.2 Responsive Design (Mobile-First)
- Breakpoints: `xs < 576px`, `sm 576-767`, `md 768-991`, `lg 992-1199`, `xl 1200-1599`, `xxl >= 1600` to align with Ant Design defaults.
- Patterns: nav becomes hamburger at `md`, gridstack reduces columns per earlier definition, Good Sites cards go single column at `xs`, two columns `sm`, 3+ columns `md+`.
- Marketplace filters collapse at `md`, replaced with Drawer triggered by `Filters` button; search summary retains context.
- Buttons maintain minimum tap size (48px). Banners reposition to top for compliance; on mobile they may use `VcDrawer` to avoid covering content.
- Media queries also monitor `prefers-reduced-motion` and `prefers-contrast` to alter animations and colors.

<!-- anchor: 4-3-accessibility -->
### 4.3 Accessibility (WCAG 2.1 AA)
- Semantic HTML from Qute ensures `<main>`, `<nav>`, `<section aria-labelledby>` wrappers.
- All interactive elements reachable via keyboard; modals trap focus with shift+tab loops; closing uses ESC or close button.
- Provide screen reader instructions when entering gridstack edit mode ("You are now editing layout. Use arrow keys to reposition.").
- Charts include accessible tables via `aria-describedby` referencing hidden table containing data values.
- Motion reduction: disable parallax or animated gradient backgrounds and switch to static colors when `prefers-reduced-motion` true.
- Form validation uses inline error text near fields, with `aria-live` status describing issues.
- Provide transcripts/captions for any embedded video inside profile templates (if embed block used) via sanitized markdown with `figure` + `figcaption`.

<!-- anchor: 4-4-performance -->
### 4.4 Performance & Optimization
- Performance budgets: `TTI < 3.5s` on desktop, `<4.5s` on mobile 4G; `Largest Contentful Paint < 2.7s`, `CLS < 0.1`. Enforce bundle budget < 200KB per React island by splitting vendor chunks and lazy-loading AntV modules per chart.
- Use `IntersectionObserver` to defer chart rendering until visible; fallback to static server-rendered placeholders.
- Preload critical CSS inline for above-the-fold; other CSS chunked via `<link rel="preload" as="style">`.
- Use `loading="lazy"` for images, `decoding="async"`. Marketplace gallery uses `srcset` for different sizes.
- Use `gridstack` `staticGrid` mode when not editing to disable event listeners.
- Minimize hydration cost by limiting React islands to necessary areas; keep majority server-rendered for SEO/performance.

<!-- anchor: 4-5-backend-integration -->
### 4.5 Backend Integration Patterns
- All fetches go through `fetchWithAuth` to include JWT and CSRF token; handles 401 by redirecting to `/login` and storing intended path.
- Data passed from server includes signed click-tracking URLs to maintain analytics integrity. React components should not compute tracking tokens on client.
- Widget data endpoints support caching headers; front-end respects `Cache-Control` for anonymous responses (news, weather) via `stale-while-revalidate` pattern.
- Error responses normalized using `ErrorEnvelopeType` { `code`, `message`, `details?` }. UI displays friendly text and optionally developer info when `env=dev`.
- Websocket not used; rely on SSE or polling when near-real-time needed (job queue monitor). SSE channel `/_events/jobs` pushes queue stats with JWT auth.

<!-- anchor: 4-6-security-privacy -->
### 4.6 Security & Privacy UX Patterns
- Display OAuth provider icons and list data that will be merged to satisfy Policy P1 and GDPR transparency.
- Show `Masked Email` info in marketplace contact modal; explain relay process and data retention.
- Provide `Delete data` buttons for social widgets and Good Sites submissions; confirm via modal referencing retention policies (90-day logs, indefinite archives for screenshots per P4).
- Clipboard operations (copy share link) show toast to reassure success and avoid repeated tries.
- Sessions show `last activity` and `device` info on settings page, allowing remote logout.

<!-- anchor: 4-7-observability -->
### 4.7 Observability & Analytics Hooks
- All CTAs emit `trackEvent` via Beacon API to `/track/click` when possible; degrade to server redirect fallback.
- React islands log component load times and error boundaries to `window.__vcTelemetry`. Data forwarded to backend via `/api/telemetry` for aggregated metrics.
- Admin analytics uses `@antv/g2plot` events to allow brush/pan interactions; instrumentation records chart filter selections for reproducibility.
- Banners for AI budget levels embed `data-alert-level` for CSS and script instrumentation.

<!-- anchor: 4-8-content-governance -->
### 4.8 Content Governance & Moderation Workflow UX
- Provide `Report` button on marketplace listings and Good Sites entries; on click, open modal listing policy reasons with radio buttons and optional text.
- Moderation queue surfaces flagged content counts and filter chips for severity.
- Copy includes link to policy docs stored in Qute partial for reuse.
- Admin actions require reason input; UI writes to audit tables.
- Provide `Undo` or `Restore` options whenever possible (soft delete). Timer-based toasts show new state.

<!-- anchor: 4-9-consent -->
### 4.9 Consent & Privacy Preference UX
- Analytics consent banner appears at bottom for EU visitors; includes `Accept`, `Decline`, `Learn more`. Accept sets `analytics_consent=true` and reloads feature flag analytics logging.
- Data export and deletion requests triggered from settings; UI shows job status with spinner and history list referencing job IDs.
- Provide `GDPR Consent Version` text accessible via tooltip linking to legal doc.

<!-- anchor: 4-10-error-notifications -->
### 4.10 Error Message Framework
- Categorize errors: `inline`, `banner`, `toast`, `full-page`. Use severity-specific colors and icons.
- Provide actionable instructions ("Retry", "Contact support with code AI-1234").
- For rate limit errors (HTTP 429), show countdown timer using server-provided `Retry-After`.
- Display screenshot queue failure states with instructions to re-trigger capture or upload manual image.

<!-- anchor: 5-tooling-dependencies -->
## 5. Tooling & Dependencies

<!-- anchor: 5-1-core-deps -->
### 5.1 Core Dependencies
- **Ant Design 5.x:** Primary component library; theme tokens align with design system.
- **gridstack.js:** Drag-and-drop widget layout; integrate with React for editing mode only.
- **React 18 + TypeScript:** Used for islands; bundling via esbuild per P8.
- **@antv/g2plot, @antv/s2, @antv/l7:** Data viz for charts, tables, maps.
- **dayjs, Intl APIs:** Date/time formatting, timezone handling.
- **zod (or custom guards):** Validate `data-props` payloads from DOM before React render.
- **lodash-es (cherry-picked):** Utility functions for throttle/debounce.
- **axios not used**; rely on native fetch with wrappers to reduce bundle size.
- **react-use-measure** for drag/responsive calculations, ensuring minimal layout thrashing.

<!-- anchor: 5-2-dev-tooling -->
### 5.2 Development Tooling
- **esbuild** bundler invoked via `frontend-maven-plugin` on `mvn compile` and `mvn package`.
- **ESLint + @typescript-eslint** enforcing strict mode aligned with TypeScript project config.
- **Stylelint** optional for CSS modules inside React islands; server CSS follows PostCSS guidelines.
- **Jest + Testing Library** for React component tests; integrate with Quarkus integration tests for server-rendered snapshots.
- **Storybook** (deployed locally) for atoms/molecules to verify tokens and states. Stories exported to Chromatic-like service for regression (if allowed) or local Percy snapshots.
- **Pa11y CLI** and `axe-core` integration inside tests to enforce accessibility per component.

<!-- anchor: 5-3-testing -->
### 5.3 Testing Strategy
- **Unit tests:** Buttons, inputs, molecules with jest + RTL.
- **Integration tests:** React islands interacting with APIs via MSW mocks; ensures forms behave correctly.
- **End-to-end tests:** Playwright/Quarkus integration for gridstack flows, marketplace posting, good sites voting, profile publishing.
- **Visual regression:** Storybook + `chromatic` or `lokitest` to compare tokens after updates.
- **Performance tests:** Lighthouse CI on key pages (home, marketplace, good sites, profile) in both light/dark modes.
- **Accessibility tests:** `axe` checks in CI plus manual screen reader passes for gridstack editing and chart interactions.

<!-- anchor: 5-4-collaboration -->
### 5.4 Collaboration & Documentation
- Maintain `Design Tokens` JSON and Figma library (if available) referencing same naming as CSS tokens.
- Provide `ComponentContract.md` documents describing props, expected data shape, and backend owner contact.
- Use `docs/ui-playbooks/` to record flows (consent, marketplace, directory). Each doc references blueprint anchor for traceability.
- Weekly cross-team sync ensures ExperienceShell, Marketplace, Directory, Profile squads align on tokens and release timeline.

<!-- anchor: 5-5-deployment -->
### 5.5 Deployment & Monitoring Hooks
- Frontend assets bundled into `META-INF/resources/assets/js`; hashed filenames ensure cache busting.
- Service workers not used (per policy), but HTTP caching leveraged via CDN.
- Use FeatureFlagService to stage rollouts: `beta` environment enables `stocks_widget`, `social_integration` for QA cohorts before production.
- Monitoring: integrate synthetic checks (e.g., Pingdom) to load `/` and `/marketplace` verifying key components render.
- Logging: include `component_version` field in telemetry for quick regression diagnosis.

<!-- anchor: 6-extended-appendices -->
## 6. Extended Reference Appendices
The following appendices provide domain-specific UI/UX requirements to guide squads as they implement widgets, marketplace tooling, Good Sites curation, public profiles, and admin consoles. These sections ensure alignment with the `01_Blueprint_Foundation.md` mandates while giving granular rules for state coverage, copy tone, and interaction subtleties. Each appendix can be used as an acceptance checklist before merging UI work.

<!-- anchor: 6-1-widget-matrix -->
### 6.1 Widget-Specific Interaction Matrix
| Widget | States | Data Dependencies | Interactions | Edge Cases |
|--------|--------|-------------------|--------------|------------|
| `news_feed` | loading, personalized, fallback, error | Feed items, AI tags, interest preferences | Save article, hide source, reorder | Source offline, AI budget paused |
| `weather` | location loading, permission denied, forecast ready | WeatherService (Open-Meteo/NWS) | Toggle metric/imperial, cycle saved locations | Severe alert overlay stacking |
| `stocks` | loading, watchlist empty, watchlist populated, rate limit | StockService + AI tagging for trends | Add symbol, reorder, remove, open detail drawer | Alpha Vantage quota hit |
| `social_feed` | connected, stale (info/warning/alert), archived | SocialIntegrationService | Reconnect CTA, hide widget, delete cached data | Meta API failure, feature flag disabled |
| `rss_feed` | system feed, user custom feed, validation error | FeedAggregationService | Manage feed list, reorder display cards | Invalid RSS URL, dedupe across languages |
| `quick_links` | default set, customized, empty | UserPreferencesType.links | Drag/drop reorder, edit, delete | External link banned list |
| `search_bar` | default, query typed, suggestions open | SearchService | Submit, filter chip toggle, show saved queries | Rate limit, feature flag experiments |
| `headline_block` | static content, editing, published | Profile template config | Markdown editing, preview | Sanitization failure |
| `text_block` | markdown vs plain text, collapsed vs expanded | Profile template config | Format toolbar, link insert | Content violating policy |
| `image_block` | fallback placeholder, uploaded, remote URL | StorageGateway | Crop, alt text input, caption editing | WebP conversion failure |
| `embed_block` | preview, failure | Allowed embed providers (YouTube, Vimeo, etc.) | Source validation, responsive resizing | Unsupported provider, script blocking |
Each widget documents animation duration, skeleton layout, and instrumentation events. Designers must supply Figma frames for all states, and developers ensure storybook coverage.

<!-- anchor: 6-2-marketplace-state-table -->
### 6.2 Marketplace State Table
| State | Description | UI Treatment | Trigger | Responsible Component |
|-------|-------------|--------------|---------|-----------------------|
| `draft` | Listing saved but not submitted | Card badge `Draft`, CTA `Resume editing`, grey background | User saves mid-flow | `ListingComposer`, `MyListingsTable` |
| `pending` | Awaiting moderation (if flag set) | Yellow badge, inline text "In review" | Submission completed | `MarketplaceListingCard` |
| `active` | Live listing | Green badge, display stats | Approved/published | `MarketplaceListingFeed` |
| `flagged` | Auto-hidden due to flags | Red badge, toast, admin notification | 3+ user flags or AI heuristics | `FlaggedListingReview` |
| `expired` | Past 30-day window | Grey badge, CTA `Renew listing` | Cron job | `ListingExpirationBanner` |
| `removed` | Deleted by admin or user | Strikethrough title, "Removed" text | Manual action | `MyListingsTable` |
| `sold` | Marked sold by user | Blue badge, share CTA | Seller action | `MyListingsTable`, `ListingDetail` |
| `promoted` | Featured/bump add-on | `Featured` badge, pinned position | Payment success | `ListingCardMeta` |
| `refund_pending` | Payment refund requested | Orange badge, timeline status | Refund workflow | `MarketplaceFeeBreakdown` |
| `payment_failed` | Stripe error | Inline error box with `Retry payment` button | Stripe webhook failure | `ListingComposer` |
For each state, build QA checklist verifying colors, icons, copy, instrumentation, and transitions. Document interplay with Policy P3 (refunds) and P12 (jobs) to keep flows accountable.

<!-- anchor: 6-3-good-sites-behavior -->
### 6.3 Good Sites Interaction & Karma Rules
- **Submission Form Steps:**
  1. **URL Entry:** Validate format, canonicalize to HTTPS; inline suggestion for duplicates linking to existing site.
  2. **Metadata Review:** Display title, description, og:image from metadata extraction; allow editing before saving.
  3. **Screenshot Review:** Show latest screenshot or fallback OG image; include `Retake screenshot` button if outdated.
  4. **Category Selection:** Provide searchable tree; highlight categories where user is moderator or frequent contributor.
  5. **Confirmation:** Summaries data, indicates moderation status based on karma.
- **Karma Display:**
  - Users see karma value (0-100+) in header; tooltips explain privileges (auto-publish, edit rights, moderator eligibility).
  - Negative karma triggers warning banner advising quality improvements.
- **Voting Mechanics:**
  - Upvote/downvote buttons large enough for touch; show `+/-` icons with color-coded state (green/up, red/down) while respecting color-blind guidelines with icons.
  - Score updates optimistically; failure rolls back with toast `Vote failed. Please retry.`
- **Flagging:**
  - Flag button sits near vote controls; opens modal requiring reason and optional notes. Provide `spam`, `broken`, `wrong category`, `inappropriate`, `other` radio options.
  - Confirmation message clarifies moderation timeline to avoid duplicate flags.
- **Moderator Tools:**
  - Moderation queue table columns: Site, Category, Submitted by, Karma, Flags, Screenshot preview, Actions.
  - Actions include `Approve`, `Reject (with reason)`, `Request changes`. Each action logs to audit.
  - Provide `Bulk Approve` and `Bulk Reject` with confirm step summarizing selection.
- **Bubble-up to Parent Categories:**
  - UI indicates bubbled links via pill `From Programming > Java` with tooltip describing threshold logic (score ≥10 and top 3).
  - Sorting ensures bubbled links display after direct category entries to avoid confusion.

<!-- anchor: 6-4-profile-template-details -->
### 6.4 Profile Template Detail Guide
| Template | Layout | Editable Regions | Special Rules |
|----------|--------|------------------|---------------|
| `public_homepage` | Gridstack clone with extra block types | Header text, accent color, widget selection, custom blocks | Public layout stored separately, default theme inherits private tokens |
| `your_times` | Newspaper-style CSS grid | Masthead text, tagline, slot assignments, color scheme | Each slot limited to curated article; user can override headline, blurb, image |
| `your_report` | Three-column link aggregator | Main header, column items, section headers, headline photo | Column count reduces to 2 on tablet, 1 on mobile; uppercase style toggle |
- Template editor shows device preview tabs (Desktop, Tablet, Mobile). Each tab uses CSS to mimic actual breakpoints.
- Template configuration stored as JSON (per spec). UI ensures all required slots filled before publish; incomplete slots show warning `Add a story or hide slot`.
- Provide SEO preview card showing `<title>`, `<meta description>`, `<og:image>` to encourage best practices per F11.9.

<!-- anchor: 6-5-accessibility-test-matrix -->
### 6.5 Accessibility Test Matrix
| Scenario | Screen Reader Steps | Keyboard Steps | Expected Outcome |
|----------|---------------------|----------------|------------------|
| Gridstack Edit Mode | Announce entering edit, describe controls, read widget titles | Tab to widget toolbar, press Space to toggle move mode, arrow keys to reposition, ESC to exit | Screen reader announces new coordinates; focus stays on handles |
| Marketplace Filter Drawer | `aria-controls` linking button to Drawer, `aria-modal` true | Tab into Drawer, shift+tab cycles, ESC closes | Drawer traps focus; closing returns focus to trigger |
| Good Sites Voting | `aria-pressed` toggles on buttons, `aria-live` for score | Space to upvote/downvote | Score update read aloud; button state toggles |
| Consent Modal | `aria-labelledby` referencing heading; descriptive copy | Tab order: Accept, Decline, Learn more, close button | Can't interact with background while modal open |
| Social Reconnect Banner | `role="status"` | Tab to `Reconnect` button | Banner text read once; button focusable |
| AI Usage Console Chart | Hidden table describing data, accessible description referencing color ramp | `Tab` enters chart, arrow to data points | Provide textual descriptions per data point |
| Profile Template Editor | Provide instructions for drag/drop; offer button alternatives | Use `Move up/down` buttons for slots | Keyboard alternatives functional |
| Directory Submission Form | Input instructions read before fields | Tab order matches visual layout | Error message read when invalid |
Testing schedule: run manual passes quarterly; run automated `axe` on commit for forms and modals.

<!-- anchor: 6-6-error-dictionary -->
### 6.6 Error & Empty State Dictionary
| Code | Context | Message | User Action |
|------|---------|---------|-------------|
| `AI-BUDGET-PAUSED` | AI tagging disabled | "AI tagging is paused because this month's budget has been reached." | Provide link to admin contact, show fallback content |
| `SOCIAL-TOKEN-STALE` | Social widget stale | "Showing posts from {days} days ago. Reconnect to refresh." | CTA to reconnect and delete data |
| `RSS-INVALID` | Custom feed invalid | "We couldn't verify that RSS feed. Check the URL and try again." | Link to docs |
| `LISTING-IMAGE-LIMIT` | >12 images | "You can upload up to 12 images per listing." | Suggest removing before adding |
| `LISTING-RADIUS-UNSUPPORTED` | >250 miles search | "Nationwide search isn't available yet. Choose a radius up to 250 miles." | Provide options |
| `DIRECTORY-DUPLICATE` | Duplicate site submission | "Looks like this site already exists in {categories}." | Link to existing listing |
| `PROFILE-NOT-PUBLISHED` | Viewing unpublished profile | "This profile isn't published yet." | Provide CTA to publish or go back |
| `FLAG-LIMIT` | Too many flags quickly | "Thanks for keeping the community safe. Please wait before flagging more listings." | Rate limit info |
| `ANON-CONSENT-REQUIRED` | Merge without consent | "Review how we'll handle your data before continuing." | Show consent modal |
| `WEATHER-LOCATION-DENIED` | Geolocation blocked | "We couldn't access your location. Enter a city to see weather." | Input field |
Each error message includes `Support Code` referencing log entry for ops debugging.

<!-- anchor: 6-7-interaction-checklists -->
### 6.7 Interaction Design Checklists
- **Widget Editor Checklist:**
  - [] Provide tooltips for each toolbar icon.
  - [] Confirm `Undo`/`Redo` states disabled appropriately.
  - [] Support keyboard reorder instructions.
  - [] Broadcast layout change events for server analytics.
  - [] Ensure drag handles visible in high contrast theme.
- **Marketplace Checkout Checklist:**
  - [] Display price breakdown before payment (posting fee + promotions).
  - [] Confirm Stripe errors mapped to friendly copy.
  - [] Provide `Processing...` state while waiting for webhook.
  - [] Offer `Need help? Contact support` link referencing Policy P3.
- **Good Sites Voting Checklist:**
  - [] Buttons show focus ring.
  - [] Score increments with animation <150ms.
  - [] Rate limit message includes countdown.
  - [] Provide `Why can't I vote?` link for anonymous users.
- **Profile Template Checklist:**
  - [] Device preview tabs update automatically on config change.
  - [] Template palette accessible per WCAG; run automated contrast tests.
  - [] Provide `Reset template` option.
  - [] Save/publish actions disabled while screenshot job running.
- **Admin Analytics Checklist:**
  - [] Chart data export buttons (CSV/JSON) accessible.
  - [] Filter chips persist via query params for shareable URLs.
  - [] Loading skeletons show consistent height to avoid layout shift.
  - [] Provide `Last updated` timestamp referencing data fetch time.

<!-- anchor: 6-8-copy-matrix -->
### 6.8 Copy Matrix for Key Modules
| Module | Primary CTA | Secondary CTA | Info Text | Localization Notes |
|--------|-------------|---------------|-----------|--------------------|
| Homepage personalization | "Customize" | "Reset layout" | "Drag any widget to rearrange." | Provide translation placeholders |
| Consent modal | "Merge & continue" | "Discard anonymous data" | "We connect anonymous data to your new account so you can keep your layout." | Provide `Learn more` link |
| Marketplace listing detail | "Contact seller" | "Save listing" | "Messages are relayed through Village Homepage; your email stays private." | Offer telephone instructions |
| Good Sites submission | "Submit site" | "Save draft" | "Describe why this site is useful to the Village community." | Ensure text limit warnings localized |
| Profile builder | "Publish profile" | "Preview" | "Changes are saved automatically." | Provide `Last edited` timestamp |
| Social widget banner | "Reconnect your account" | "Hide widget" | "Showing posts from {days} days ago." | Days string localized |
| AI usage console | "Adjust batch size" | "Notify ops" | "You're at {percent}% of the monthly budget." | Format currency/percent |
| Feature flags console | "Save rollout" | "Create cohort" | "Stable cohorts ensure users see consistent experiences." | Provide translation for roles |
| Directory moderation | "Approve" | "Reject" | "Give contributors guidance." | Provide templated rejection reasons |
| Marketplace refunds | "Approve refund" | "Reject" | "Refund window is 24 hours for moderation or technical failures." | Provide timezone info |

<!-- anchor: 6-9-analytics-dashboard-details -->
### 6.9 Admin Analytics Dashboard Details
- **Overview Section:** Cards for `Total clicks today`, `Total clicks 7d`, `Unique users today`, `AI budget`. Each card includes sparkline showing 7-day trend, `Trending up/down` label, and tooltip describing data sources (click_stats_daily, ai_usage_tracking).
- **Category Performance Chart:** Multi-select filter for click type (feed_item, directory_site, marketplace_listing, profile_curated). Pie chart transitions to donut when category filter applied. Provide legend with percentages and absolute numbers.
- **Top Items Table:** Columns: Rank, Title, Category, Clicks, Unique users, Trend (sparkline). Table rows clickable to open Drawer with detail view, including breakdown by referer domain and device type.
- **Traffic Sources Panel:** Bar chart showing referer domains; color-coded by traffic type (search, social, direct). Provide `Show filtered list` button to open modal with data table (domain, clicks, unique sessions, bounce rate if available).
- **Feature Flag Analytics:** Inline card showing evaluation counts per flag and conversion metrics when analytics enabled. Provide callout to respect Policy P14 (purge data when consent withdrawn) and mark cohorts as `Partial data` if significant deletions occurred.
- **Job Health Summary:** Table showing queue name, backlog size, average wait time, number of stuck jobs; color-coded thresholds to highlight issues.

<!-- anchor: 6-10-job-ux -->
### 6.10 Job Queue & Background Process UX
- Provide `JobQueueConsole` grid with cards for each queue (DEFAULT, HIGH, LOW, BULK, SCREENSHOT). Each card displays `Queued`, `Processing`, `Failed`, `Oldest job age`. Buttons: `View jobs`, `Retry failed`, `Scale guidance` (links to runbook).
- For screenshot queue, show per-browser concurrency, average duration, last error screenshot preview.
- `AiTaggingJobHandler` view displays `batch size` slider (read-only for now) plus `Change log` linking to policy decisions.
- Provide timeline view for `ProfileViewCountAggregator` to illustrate view counts aggregated hourly.
- Each job detail Drawer shows: payload JSON, attempts, errors, `Retry` button (if permission), `Copy job ID` button.

<!-- anchor: 6-11-data-export-ux -->
### 6.11 Data Export & Deletion UX
- Settings page includes `Data export` card with description referencing GDPR Article 15. Button `Request export` triggers job; status pill shows `Requested`, `Processing`, `Ready`, `Expired`. Provide download link with expiration countdown.
- Deletion request flow includes multi-step confirmation: summary of data to delete, reminder about irreversible action, `Confirm deletion` requiring typing `DELETE`. UI warns that feature flag evaluations and social tokens removed immediately in compliance with P14 and P5.
- Show timeline of past exports/deletions with status and job ID for auditing.

<!-- anchor: 6-12-rate-limit-ui -->
### 6.12 Rate Limit UX Artifacts
- Rate limit violations display toast `You're performing actions too quickly` plus banner with timer for repeated offenses.
- Admin console table columns: Action type, Tier, Limit, Window, Violations (24h). Provide `Edit` button to open Drawer with form fields. Validate entries (limit <= 1000, window <= 3600 sec) before saving.
- Provide IP/User blocklist management UI with search/filter, reason input, expiry date; actions log to audit.
- End user view sample copy: `Search limit reached. Please wait 15 seconds before trying again.` and `Voting paused due to high activity. Try again in 30 seconds.`

<!-- anchor: 6-13-onboarding -->
### 6.13 Onboarding Experience Notes
- New authenticated users see `Getting Started` checklist: `Customize widgets`, `Pick news topics`, `Add watchlist symbols`, `Explore Good Sites`, `Create public profile`. Each item uses `VcCheckbox` and progress meter.
- Provide progress toast when tasks completed; data stored in `user_preferences.widgetConfigs.onboarding`. Checklist hidden once all tasks done.
- For marketplace sellers, show `Seller onboarding` card with steps: `Verify email`, `Set contact method`, `Review posting fees`, `Create first listing`.

<!-- anchor: 6-14-localization -->
### 6.14 Localization & Internationalization Guidelines
- Use `Intl.NumberFormat`, `Intl.DateTimeFormat` wrappers for currency/time; fallback to `dayjs` plugin when needed.
- Provide translation files stored per locale (en-US default). Keys follow `namespace.key` pattern (e.g., `home.widgets.news.title`).
- Support en-US + en-CA copy with measurement toggles (miles vs kilometers). Additional languages flagged for v2 but design ensures string expansion room (30% growth).
- Date pickers default to ISO but show localized format; e.g., `Jan 5, 2026` vs `5 Jan 2026` based on locale.
- Provide `Language settings` (if not yet implemented, placeholder) to future-proof UI.

<!-- anchor: 6-15-csp-embeds -->
### 6.15 Embed & Content Security Policy Notes
- Allowed embed providers: YouTube, Vimeo, SoundCloud, Spotify, Twitter/X (if allowed), Mastodon, simple iframe for calendars. All others blocked.
- UI warns when user attempts unsupported embed; provides suggestion to link to site via Quick Link instead.
- Provide `Preview embed` button launching sandboxed iframe with `allowfullscreen` toggled per provider; display fallback text for screen readers describing embed content.
- Document CSP directives to ensure UI copy references restrictions when embed fails.

<!-- anchor: 6-16-print-friendly -->
### 6.16 Print-Friendly Views
- Provide `Print` action for Good Sites and profiles. Print styles hide nav, convert backgrounds to white, show link URLs after text using CSS `content: attr(href)`.
- Listing detail print includes QR code linking to page (generated server-side). Provide note about masked email.
- Ensure charts convert to tables for print mode; hide animations.

<!-- anchor: 6-17-mobile-gestures -->
### 6.17 Mobile Gesture Patterns
- Swipe actions allowed on Quick Links (swipe left to delete). Provide undo button for 5 seconds.
- Marketplace cards support horizontal swipe to reveal `Save` and `Share` actions; fallback to menu button for accessibility.
- Good Sites cards do not use swipe to avoid accidental votes; rely on buttons.
- Provide `pull to refresh` for homepage feed on mobile; disable if `prefers-reduced-motion` to avoid conflict with OS gestures.

<!-- anchor: 6-18-notifications -->
### 6.18 Notification Strategy
- Use Ant Design `notification` for persistent messages (e.g., listing published). Provide close button accessible with `aria-label`.
- For critical alerts (AI budget, screenshot backlog), show inline banner plus email per policy.
- Rate limit toasts degrade to inline text when user has `prefers-reduced-motion` or `reduce-notifications` preference.
- Notification center (future) should store last 20 messages; UI spec prepared now to inform design decisions.

<!-- anchor: 6-19-qa-handbook -->
### 6.19 QA Handbook Snippets
- Verify cross-browser (Chrome, Firefox, Safari, Edge) for gridstack, drag, and CSS grid layouts.
- Confirm server-side rendered pages maintain canonical meta tags and JSON-LD markup per F11.9 & F14.5.
- Check `prefers-color-scheme: dark` and `prefers-contrast` combos for each page.
- Validate SSE fallback in job monitor when SSE unsupported (auto degrade to polling).
- Ensure click tracking wrappers present on all external links by verifying anchor `href` begins with `/track/click`.
- For social widget, confirm reconnect banner colors correspond to days stale (1-3 info, 4-7 warning, 7+ alert) and text updates per day count.
- Marketplace location radius controls should disable `Any` option per Policy P11.

<!-- anchor: 6-20-release-readiness -->
### 6.20 Release Readiness Checklist
1. **Design QA:** All new components documented in Storybook with tokens applied.
2. **Accessibility QA:** `axe` scan, screen reader pass, keyboard navigation for flows touched this release.
3. **Performance QA:** Lighthouse budgets satisfied on targeted pages; bundler stats recorded.
4. **Feature Flag Plan:** Document enabling/disabling steps, cohorts, rollback plan.
5. **Analytics:** Ensure new events/tracking endpoints documented and verified in admin console.
6. **Compliance:** If data handling changes, update consent copy and documentation.
7. **Operational Hooks:** Add runbook entries for new jobs or alerts, including metric thresholds.
8. **Localization:** Strings externalized if new copy added; default translations tested.
9. **Screenshots:** If Good Sites categories changed, ensure screenshot jobs scheduled and UI handles new versions.
10. **Monitoring:** Dashboards updated with new KPIs; alerts configured.

<!-- anchor: 6-21-future-scaling -->
### 6.21 Future Scaling Considerations
- Prepare for additional social providers (X/Twitter) by abstracting token UI. Provide placeholder UI states now to ease future expansion.
- Anticipate marketplace `Any` radius request (v2) by designing filter UI to accommodate toggle; keep copy referencing upcoming feature.
- Provide design tokens for `international` color scheme to support markets outside North America.
- Document approach for multi-language Good Sites categories to ensure layout supports longer text strings.
- Profile templates should consider video blocks; specify ratio containers and fallback copy.

<!-- anchor: 6-22-design-assets -->
### 6.22 Design Asset Inventory
- Maintain Figma sections: `Tokens`, `Atoms`, `Molecules`, `Organisms`, `Templates`, `Flows`, `Modals`, `Banners`, `Charts`.
- Provide `widget-layout.fig`, `marketplace.fig`, `good-sites.fig`, `profiles.fig`, `admin.fig` files with component variants.
- Use shared library for icons; ensure source-of-truth matches Ant Design icon set to avoid drift.
- Export accessible color palettes as JSON for direct injection into `DesignTokensType` DTO.

<!-- anchor: 6-23-figma-handoff -->
### 6.23 Figma Handoff Guidelines
- Each frame must include annotation layer describing interactions, transitions, and data states.
- Provide `Spec` component listing tokens used (colors, typography, spacing) to allow developers to map to CSS variables quickly.
- Include `Prototype` for flows (consent, listing creation, voting) for dev reference.
- Document `component version` number that matches Storybook entry to maintain traceability.

<!-- anchor: 6-24-cross-team-sync -->
### 6.24 Cross-Team Sync Rituals
- Weekly UI architecture sync includes leads from ExperienceShell, Marketplace, Directory, Profiles, Admin squads. Agenda: review open design tokens, cross-cutting concerns, feature flag rollouts.
- Monthly compliance review ensures copy and flows align with GDPR/CCPA obligations, especially for consent and data export.
- Bi-weekly ops handoff covers job health dashboards, screenshot queue capacity (P12), AI budget usage, rate limit adjustments.

<!-- anchor: 6-25-documentation-links -->
### 6.25 Documentation Links & Storage
- `.codemachine/artifacts` hosts architecture docs (this file). Additional docs: `/docs/ui-guides/marketplace.md`, `/docs/ui-guides/good-sites.md`, `/docs/ui-guides/profiles.md` for per-domain requirements.
- Provide `README` for React islands describing mount points and hydration instructions.
- Keep `CHANGELOG_UI.md` to capture token tweaks, component renames, major UI upgrades tied to feature flags.

