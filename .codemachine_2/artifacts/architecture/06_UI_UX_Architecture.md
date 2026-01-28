<!-- anchor: ui-ux-architecture-village-storefront -->
# UI/UX Architecture: Village Storefront
**Status:** UI_REQUIRED

<!-- anchor: 1-design-system-specification -->
## 1. Design System Specification

<!-- anchor: 1-1-color-palette -->
### 1.1 Color Palette
The palette aligns with Tailwind tokens but is generated per tenant from stored branding data; fallback colors prioritize legibility on both storefront Qute pages and admin PrimeVue controls, with all variants defined as CSS variables exported to Tailwind and PrimeUI themes.

<!-- anchor: 1-1-1-core-brand-colors -->
#### 1.1.1 Core Brand Colors
- `color.brand.primary.50` (#F2F6FF) - ultra light wash for backgrounds on feature strips and skeleton loaders.
- `color.brand.primary.100` (#DCE7FF) - card hover background on storefront home sections.
- `color.brand.primary.200` (#BCCDFF) - table row stripe color inside admin dashboards.
- `color.brand.primary.300` (#8FAEFF) - focus outline for interactive tiles.
- `color.brand.primary.400` (#5C84F2) - default border for PrimeUI panels and menu underlines.
- `color.brand.primary.500` (#2E5AC9) - primary CTA fill, e.g., “Add to Cart” and “Save Product”.
- `color.brand.primary.600` (#1F479F) - CTA hover fill plus main admin top bar background.
- `color.brand.primary.700` (#143374) - active navigation indicator and POS header background.
- `color.brand.primary.800` (#0D2351) - modal header text on dark mode surfaces.
- `color.brand.primary.900` (#071631) - text for high contrast hero statements on light backgrounds.
- `color.brand.secondary.50` (#ECFCF7) - subtle accent for loyalty cards and success banners.
- `color.brand.secondary.100` (#C5F6E7) - pill backgrounds for filter chips.
- `color.brand.secondary.200` (#92EFD6) - hover fill for secondary ghost buttons.
- `color.brand.secondary.300` (#5FE5C3) - icon accent for onboarding checklists.
- `color.brand.secondary.400` (#34D9B0) - progress bar fill for onboarding steps.
- `color.brand.secondary.500` (#12C89A) - default accent button fill (e.g., “Invite Staff”).
- `color.brand.secondary.600` (#0EA781) - hover fill on accent CTAs.
- `color.brand.secondary.700` (#0A8969) - text color for accent badges on light backgrounds.
- `color.brand.secondary.800` (#066B52) - outlines for accent-focused components.
- `color.brand.secondary.900` (#034D3A) - accessible accent text on tinted surfaces.
- `color.brand.accent.50` (#FFF8E7) - highlight for subscription upsell cards.
- `color.brand.accent.100` (#FFEEC0) - background for discount badges.
- `color.brand.accent.200` (#FFD98A) - KPI sparkline highlight in dashboards.
- `color.brand.accent.300` (#FFC254) - loyalty point counter glow.
- `color.brand.accent.400` (#FFA82A) - state indicator for warnings that are non-blocking.
- `color.brand.accent.500` (#FF8A00) - “Pay Now” action color when Stripe Connect prompts user.
- `color.brand.accent.600` (#DB6C00) - hover or active accent.
- `color.brand.accent.700` (#B55000) - text for accent on tinted backgrounds.
- `color.brand.accent.800` (#8C3E00) - dark accent for icon outlines.
- `color.brand.accent.900` (#5A2A00) - deep accent for gradient anchors.
- `color.brand.gradient.sunrise` (linear #2E5AC9→#12C89A) - hero backgrounds and CTA ribbons.
- `color.brand.gradient.dusk` (linear #071631→#FF8A00) - platform admin hero with overlays for at-a-glance metrics.

<!-- anchor: 1-1-2-semantic-colors -->
#### 1.1.2 Semantic Colors
- `color.semantic.success.100` (#E6F7F1) - toast background for POS sync success.
- `color.semantic.success.200` (#B9EFD8) - inline alerts confirming payout submission.
- `color.semantic.success.500` (#1B9E66) - text/icon for success states.
- `color.semantic.success.600` (#107149) - accessible button focus outlines tied to success flows.
- `color.semantic.success.700` (#0A4B32) - dark success text on light backgrounds.
- `color.semantic.warning.100` (#FFF6E6) - background for expiring consignment notices.
- `color.semantic.warning.200` (#FFE4BF) - banner fill for low stock alerts.
- `color.semantic.warning.500` (#D9822B) - icon and text color for blocking warnings.
- `color.semantic.warning.600` (#A9601D) - hover/focus for warning-toned buttons.
- `color.semantic.warning.700` (#6C3F13) - warning text on tinted surfaces.
- `color.semantic.danger.100` (#FFEAEA) - background for refund failures.
- `color.semantic.danger.200` (#FFBDBD) - inline input error highlight.
- `color.semantic.danger.500` (#D64545) - destructive action buttons across admin and platform consoles.
- `color.semantic.danger.600` (#A52F2F) - hover states for destructive actions.
- `color.semantic.danger.700` (#701F1F) - text for serious alerts within audit logs.
- `color.semantic.info.100` (#E8F1FF) - background for tooltips describing Stripe Connect steps.
- `color.semantic.info.200` (#C5DEFF) - accent for headless API documentation callouts.
- `color.semantic.info.500` (#2561D4) - informational badge text.
- `color.semantic.info.600` (#1B4AB5) - focus ring color for informational contexts.
- `color.semantic.info.700` (#123489) - info icon color on tinted surfaces.
- `color.semantic.neutral.positive` (#1F2933) - standard high-contrast text color for data-dense tables.
- `color.semantic.neutral.muted` (#6B7280) - secondary text, subtitles, timestamp info.

<!-- anchor: 1-1-3-neutral-surfaces -->
#### 1.1.3 Neutral & Surface Tokens
- `color.surface.canvas` (#FFFFFF) - default background for storefront, admin, and platform consoles.
- `color.surface.canvas-alt` (#F7F8FA) - alternating rows, cards, and background sections.
- `color.surface.elevation-1` (#FFFFFF + shadow sm) - cards with subtle depth for balancing multi-column layout.
- `color.surface.elevation-2` (#FFFFFF + shadow md) - modals and floating panels.
- `color.surface.elevation-3` (#1C2533) - dark mode overlay for impersonation indicator.
- `color.surface.input` (#FFFFFF border #D1D5DB) - form fields and dropdown containers across Prime components.
- `color.surface.input-hover` (#F9FAFB border #9CA3AF) - interactive input backgrounds.
- `color.surface.control-active` (#E4EBFF) - highlight for selected navigation lists.
- `color.surface.chart-grid` (#E5E7EB) - gridlines for reporting charts.
- `color.surface.table-header` (#F3F4F6) - sticky header background for tables with filters.
- `color.surface.offline` (#FFF8E1) - POS offline banner background.
- `color.surface.mask` (rgba(7,22,49,0.68)) - overlay used for modals and off-canvas menus.

<!-- anchor: 1-1-4-tenant-theming -->
#### 1.1.4 Tenant Theming & Overrides
- Tenant branding feeds `tenant_theme` table; server renders CSS variables per tenant domain at request time to keep Qute pages theme-aware without FOUC.
- Merchants can override primary/secondary colors while semantic tokens remain platform-controlled to preserve accessibility.
- Admin SPA reads theme tokens through boot payload to skin PrimeVue surfaces consistently with storefront for brand employees.
- Platform admins always see platform defaults except when impersonating; in that case, a top banner shows both platform palette and tenant palette swatches with accessible contrast validation.
- Tailwind configuration merges base palette with tenant overrides so that utility classes remain stable even when colors shift per tenant.

<!-- anchor: 1-2-typography -->
### 1.2 Typography
The typographic system leverages `Inter` for UI and `Source Serif Pro` as optional accent for marketing copy, ensuring CSS subsets load via `font-display: swap` to meet Quarkus-rendered performance budgets while respecting Tailwind configuration derived from tenant tokens.

<!-- anchor: 1-2-1-type-families -->
#### 1.2.1 Type Families
- `font.family.primary`: Inter, sans-serif; applied to all interactive controls, navigation, and admin tables for clarity.
- `font.family.secondary`: Source Serif Pro, serif; limited to storefront storytelling blocks (hero copy, testimonials) with controlled usage to avoid performance hit.
- `font.family.mono`: JetBrains Mono, monospace; used for code samples within developer documentation and API keys shown to merchants.
- `font.family.numeric`: Space Grotesk tabular variant; loaded when data visualizations require consistent alignment for KPIs.

<!-- anchor: 1-2-2-type-scale -->
#### 1.2.2 Type Scale
- `type.scale.2xs` (10px/0.625rem, line 14px) - meta labels, helper text in dense forms.
- `type.scale.xs` (12px/0.75rem, line 16px) - table meta info, pill labels.
- `type.scale.sm` (14px/0.875rem, line 20px) - default table body text and filter controls.
- `type.scale.md` (16px/1rem, line 24px) - standard body copy and storefront product descriptions.
- `type.scale.lg` (18px/1.125rem, line 26px) - card titles and admin panel section headings.
- `type.scale.xl` (20px/1.25rem, line 28px) - marketing subheadings, checkout step names.
- `type.scale.2xl` (24px/1.5rem, line 32px) - hero supporting text and KPI callouts.
- `type.scale.3xl` (30px/1.875rem, line 36px) - storefront hero statements and platform-level alerts.
- `type.scale.4xl` (36px/2.25rem, line 40px) - premium hero headings for enterprise plan stores.
- `type.scale.5xl` (48px/3rem, line 52px) - limited to promotional hero with background gradient; lazy loaded fonts to avoid CLS.

<!-- anchor: 1-2-3-type-weights -->
#### 1.2.3 Font Weights & Styles
- `font.weight.light` (300) - long-form marketing text to reduce density.
- `font.weight.regular` (400) - default for paragraphs and data labels.
- `font.weight.medium` (500) - navigation, button text, interactive chips.
- `font.weight.semibold` (600) - table headers, card titles, small metrics.
- `font.weight.bold` (700) - hero lines, KPI numbers, impersonation banners.
- Emphasize `font-feature-settings: "tnum"` on numeric contexts such as price lists to reduce jitter across columns.

<!-- anchor: 1-2-4-typography-guidelines -->
#### 1.2.4 Typography Guidelines
- Maintain minimum 4.5:1 contrast for text under 18px; for larger display text, ensure at least 3:1 ratio.
- Multi-tenant theming re-validates color tokens with typography to prevent user-supplied colors from breaking legibility; admin screens show warnings when merchants select low-contrast combos.
- Use `text-balance` utility for hero copy to avoid ragged edges on narrow viewports.
- Input labels remain persistent above fields to avoid placeholder-only forms; helper text uses `type.scale.xs` with `color.semantic.neutral.muted`.
- For bilingual future support, adopt `font-family` fallback stacks per script; keep CSS ready for `font-display` toggles without code changes.

<!-- anchor: 1-2-5-typography-responsive -->
#### 1.2.5 Responsive Type Behavior
- Apply fluid type scaling between `sm` and `xl` breakpoints using clamp expressions so hero text shrinks gracefully on tablets.
- On POS offline mode, enlarge key line items to `type.scale.xl` to support stand-distance readability.
- Admin tables lock `type.scale.sm` even on large screens to maximize data density; rely on column resizing rather than font size increases.
- Mobile nav uses `type.scale.lg` for primary categories to maintain tap accuracy; subordinate items drop to `type.scale.md`.

<!-- anchor: 1-3-spacing-sizing -->
### 1.3 Spacing & Sizing
Spacing tokens align across Tailwind, PrimeUI, and POS experiences, ensuring consistent density whether screens are server-rendered or SPA-based.

<!-- anchor: 1-3-1-spacing-scale -->
#### 1.3.1 Spacing Scale
- `space.0` (0px) - used for tight icon stacking or borderless table edges.
- `space.1` (4px) - micro-gap between icon and label inside buttons.
- `space.1-5` (6px) - input label to helper text offset.
- `space.2` (8px) - chip padding, breadcrumb gaps, inline form groups.
- `space.2-5` (10px) - slider handle offsets.
- `space.3` (12px) - default gap between stacked buttons in dialogs.
- `space.3-5` (14px) - placeholder skeleton spacing.
- `space.4` (16px) - body text paragraph spacing, card interior padding.
- `space.5` (20px) - vertical spacing between sections in forms.
- `space.6` (24px) - default grid gap for multi-column layouts.
- `space.7` (28px) - checkout step separators.
- `space.8` (32px) - hero text to CTA spacing.
- `space.9` (36px) - data visualization margins.
- `space.10` (40px) - admin dashboard widget spacing on desktop.
- `space.12` (48px) - top/bottom padding for major sections.
- `space.16` (64px) - homepage hero vertical padding.
- `space.20` (80px) - wide-screen breathing room for marketing surfaces.
- `space.24` (96px) - reserved for event banners on store home.

<!-- anchor: 1-3-2-component-dimensions -->
#### 1.3.2 Component Dimensions
- Minimum touch target: 44x44px for all interactive elements, enforced via Tailwind utilities and Prime component props.
- Input heights: 40px default, 48px for checkout and POS forms, 56px for accessible buttons in POS.
- Modal widths: `sm` 320px, `md` 480px, `lg` 640px, `xl` 960px, with `max-w-screen` clamp to avoid edge collisions.
- Table rows: 52px standard, 64px for density-friendly toggles, 72px for POS line items.
- Sidebar widths: 272px on desktop, collapse to 80px icon rail, slide-in overlay on mobile.

<!-- anchor: 1-3-3-layout-grids -->
#### 1.3.3 Layout Grids
- Storefront home uses 12-column grid with 16px gutters; cards span multiples of 3 columns for symmetric layout.
- Category/product listing uses responsive grid: 2 columns on phones, 3 on tablets, 4-5 on widescreen with auto-fit to maintain 220px min card width.
- Admin uses 12-column fluid grid; KPI cards span 3 columns, tables span full width, side-panels span 4 columns on widescreen.
- POS layout uses 4-column flexible grid with vertical scroll region for cart contents and horizontal scroll for categories, ensuring offline compatibility.
- Platform admin dashboards allocate fixed 360px column for filters plus flexible data area.

<!-- anchor: 1-3-4-container-widths -->
#### 1.3.4 Container Maximum Widths
- Storefront text containers clamp to 720px to maintain readability.
- Checkout container max width 880px with center alignment; left column for form, right column for order summary using CSS grid.
- Admin detail pages use `max-w-6xl` with sticky action bar; editing experience uses 2-column layout on desktop, single column on mobile.
- Platform admin uses `max-w-7xl` to accommodate cross-tenant tables; virtualization keeps scroll smooth.

<!-- anchor: 1-4-component-tokens -->
### 1.4 Component Tokens
Tokens ensure consistency between Qute templates and PrimeUI/PrimeVue components after theme injection.

<!-- anchor: 1-4-1-border-radius -->
#### 1.4.1 Border Radius
- `radius.none` (0px) - tables, inline tags requiring flush edges.
- `radius.sm` (4px) - form inputs, chips, tooltips.
- `radius.md` (8px) - cards, dropdown menus, inline alerts.
- `radius.lg` (12px) - hero image containers, key CTA buttons.
- `radius.xl` (20px) - price badges, customer avatar frames.
- `radius.full` (9999px) - avatar, toggle knobs, progress dots.

<!-- anchor: 1-4-2-shadows -->
#### 1.4.2 Shadows & Elevation
- `shadow.none` - accessible focus states rely on outlines rather than drop shadows when in high-contrast mode.
- `shadow.xs` (0 1px 2px rgba(0,0,0,0.05)) - cards on light backgrounds.
- `shadow.sm` (0 1px 3px rgba(0,0,0,0.1)) - dropdowns, tooltips.
- `shadow.md` (0 4px 12px rgba(7,22,49,0.15)) - modals and floating panels.
- `shadow.lg` (0 12px 32px rgba(7,22,49,0.2)) - dialogues requiring focus, e.g., confirm payout.
- `shadow.inset` (inset 0 1px 2px rgba(0,0,0,0.06)) - input fields to create depth.

<!-- anchor: 1-4-3-transitions -->
#### 1.4.3 Transitions & Motion Tokens
- `motion.duration.fast` (120ms) - hover states, button feedback.
- `motion.duration.base` (200ms) - modal fade, accordion open/close.
- `motion.duration.slow` (320ms) - page-level transitions such as impersonation banner slide-in.
- `motion.curve.standard` (`cubic-bezier(0.2,0,0.38,0.9)`) - general UI interactions.
- `motion.curve.emphasized` (`cubic-bezier(0.34,1.56,0.64,1)`) - success toast pop, loyalty progression.
- `motion.curve.decelerate` (`cubic-bezier(0,0,0.2,1)`) - overlays.

<!-- anchor: 1-4-4-opacity-layering -->
#### 1.4.4 Opacity & Layering
- `overlay.opacity.light` (rgba(7,22,49,0.4)) - modal scrims.
- `overlay.opacity.heavy` (rgba(7,22,49,0.65)) - destructive confirmation to focus attention.
- `overlay.opacity.brand` (linear gradient overlay for hero images) - merges brand colors with imagery.
- `border.opacity.muted` (#0C1B33 at 8%) - card separators.
- `focus.ring` (2px solid `color.brand.primary.400` + 1px offset) - consistent accessible focus.

<!-- anchor: 1-5-iconography -->
### 1.5 Iconography
Use Phosphor Icons subset for both storefront micro-interactions and admin navigation to maintain brand coherence.

<!-- anchor: 1-5-1-icon-guidelines -->
#### 1.5.1 Icon Guidelines
- Standard stroke width 1.5px to align with typography weight.
- Icons default to `color.semantic.neutral.muted`; on active states they adopt `color.brand.primary.600`.
- Provide textual labels for all icons, especially in navigation rails; rely on tooltips only as supplemental.
- POS offline icon uses custom plugin to show connectivity status with animated pulse using `motion.duration.base`.
- Icon sprites pre-loaded for top nav to avoid reflow on route change.

<!-- anchor: 1-5-2-icon-usage -->
#### 1.5.2 Icon Usage Patterns
- Commerce actions (cart, checkout, gift card) share consistent icon family for recognition across tenant storefronts.
- Admin-specific actions (inventory, consignors, loyalty) map to unique glyphs documented with synonyms to support translation later.
- Platform admin icons integrate alert badges for impersonation to differentiate contexts.

<!-- anchor: 1-6-motion-guidelines -->
### 1.6 Motion & Micro-Interaction Guidelines
Motion is purposeful, communicates hierarchy, and respects reduced-motion settings detected via media queries.

<!-- anchor: 1-6-1-motion-principles -->
#### 1.6.1 Motion Principles
- Use fade + elevation when surfaces appear from the same origin; avoid slide-in from random directions.
- Keep motion symmetrical across storefront and admin; e.g., drawer slides from right with same duration.
- Provide `prefers-reduced-motion` overrides that remove translation but keep opacity changes for context.

<!-- anchor: 1-6-2-feedback-patterns -->
#### 1.6.2 Feedback Patterns
- Buttons show optimistic loading states using inline progress ring plus `aria-live="polite"` text updates.
- Drag-and-drop operations (menu ordering, product images) use subtle scaling to indicate draggable areas.
- Success toasts auto-dismiss after 4s but pause on hover; error toasts require explicit dismissal.

<!-- anchor: 1-7-imagery-media -->
### 1.7 Imagery & Media Treatment
Media guidelines tie into Cloudflare R2 processing outputs to ensure consistent aspect ratios.

<!-- anchor: 1-7-1-product-imagery -->
#### 1.7.1 Product Imagery
- Primary product gallery uses 4:5 ratio thumbnails with auto-centered cropping.
- Zoom overlay uses `color.surface.mask` to maintain focus while showing high-res variant from S3.
- Video thumbnails overlay play icon with `color.brand.primary.500` stroke to maintain contrast.

<!-- anchor: 1-7-2-hero-imagery -->
#### 1.7.2 Hero & Marketing Imagery
- Home hero uses gradient overlay tokens to ensure text legibility regardless of merchant uploaded imagery.
- Provide fallback illustration pack for merchants lacking imagery; lighten style to align with brand direction.
- Merchants can reorder hero modules; UI ensures alt text is mandatory for accessibility.

<!-- anchor: 1-7-3-consignor-media -->
#### 1.7.3 Consignor Portal Imagery
- Vendor avatars default to initials with gradient fill derived from vendor ID hash.
- Document attachments (contracts) show file-type icons with color-coded backgrounds for quick scanning.
- Portal uses data visualization placeholders for stores lacking historical data to avoid empty states.

<!-- anchor: 1-8-data-visualization -->
### 1.8 Data Visualization Tokens
Charts rely on C3/Chart.js wrappers styled with Tailwind tokens to align cross-surface.

<!-- anchor: 1-8-1-chart-palette -->
#### 1.8.1 Chart Palette
- `chart.series.1` = `color.brand.primary.500`
- `chart.series.2` = `color.brand.secondary.500`
- `chart.series.3` = `color.brand.accent.400`
- `chart.series.4` = `color.semantic.success.500`
- `chart.series.5` = `color.semantic.warning.500`
- `chart.series.6` = `color.semantic.info.500`
- Additional series derive from mixing neutrals at 65% opacity for background comparison areas.

<!-- anchor: 1-8-2-chart-components -->
#### 1.8.2 Chart Component Guidelines
- Axis labels use `type.scale.sm` with `color.semantic.neutral.muted`.
- Gridlines use `color.surface.chart-grid`.
- Tooltips adopt `radius.md`, `shadow.sm`, and respond to pointer and keyboard focus.
- Provide `aria-describedby` for charts summarizing insights, e.g., “Sales up 12% week over week.”

<!-- anchor: 1-9-design-token-delivery -->
### 1.9 Design Token Delivery & Governance
- Tokens stored per tenant with version history; UI surfaces show token version to align with deployment.
- Build pipeline exports JSON consumed by Tailwind config, PrimeVue theme file, and Qute inline `<style>` block.
- Admin UI includes preview mode to show how tokens affect storefront sections before publishing.
- Tokens include metadata for contrast scores to prevent saving inaccessible combinations; failing combos disable publish button.
- Platform admin can lock tokens for compliance-critical tenants (e.g., franchise) with read-only indicator.

<!-- anchor: 1-10-brand-governance -->
### 1.10 Brand Governance & Customization Limits
- Merchant-level customization limited to logo, primary/secondary colors, hero imagery, and navigation text; layout remains consistent to streamline upgrades.
- Addons (holiday themes) provided as feature flags; UI displays start/end dates and preview states.
- Platform marketing team can broadcast seasonal assets; stores opt-in via admin toggle, with preview cards showing impact.
- Impersonation mode surfaces a banner describing active theme and date published for debugging.

<!-- anchor: 2-component-architecture -->
## 2. Component Architecture

<!-- anchor: 2-1-overview -->
### 2.1 Overview
Village Storefront adopts an Atomic Design-informed component taxonomy spanning Qute-rendered storefront templates, PrimeUI-enhanced widgets, and Vue 3 admin modules. Each component declares props, state expectations, and accessibility contracts, and is registered with a design-token-aware theme wrapper to keep multi-tenant skins cohesive.

<!-- anchor: 2-2-atoms -->
### 2.2 Atoms
- **TextLabel** (`props: text, size, weight, color, as`) - renders `span` or heading with auto contrast validation; used across storefront and admin.
- **IconButton** (`props: icon, label, variant, density, loading`) - wraps Prime button with tooltip and keyboard shortcuts; ensures `aria-pressed` on toggle variant.
- **Badge** (`props: tone, icon, dismissible`) - communicates statuses such as “Scheduled”, “Low Stock”, or “POS Offline”.
- **InputField** (`props: label, helper, error, prefix, suffix, mask`) - shared markup for Prime `InputText`, supporting inline validation and `aria-live`.
- **SelectField** (`props: multi, searchable, virtualized`) - builds on Prime `Dropdown` and `MultiSelect` with consistent caret icon and keyboard support.
- **ToggleSwitch** (`props: checked, disabled, labelPlacement`) - accessible switch for feature flags, loyalty toggles, dark mode preview.
- **ProgressDot** (`props: state, tooltip, pulse`) - sequence indicators for checkout steps and onboarding tasks.
- **Avatar** (`props: imageUrl, initials, size, status`) - used for staff, customers, consignors with offline border indicator.
- **Divider** (`props: orientation, label`) - separates content sections; text-labeled variant used in checkout.
- **SkeletonBlock** (`props: width, height, radius`) - built to match target component shapes; readily reused in both UI surfaces.
- **Tag** (`props: tone, icon, removable`) - filter chips, product attribute tokens, inventory statuses.
- **Tooltip** (`props: content, placement, trigger`) - accessible with `aria-describedby` and focus trapping for interactive tooltips.
- **AlertIcon** (`props: tone, size`) - used in inline validation and toast headers.
- **CurrencyText** (`props: amount, currency, showSymbol, showMinor`) - ensures consistent money formatting and multi-currency display rules.
- **RatingStar** (`props: value, interactive`) - storefront review displays and admin QA features.

<!-- anchor: 2-3-molecules -->
### 2.3 Molecules
- **FormRow** - groups label, control, helper text, inline validation; handles optional indicators and required asterisks with `aria-required`.
- **SearchBar** - composed of InputField, SelectField, Tag list; supports voice input placeholder for future features.
- **QuantityStepper** - minus/plus buttons with InputField; ensures min/max enforcement and assists screen readers with `aria-valuenow`.
- **VariantSelector** - matrix of options (color, size, material) with swatch or text representation; multi-variant support up to 2k variants via virtualization.
- **MediaGallery** - main image, thumbnails, video player toggle, zoom modal; keyboard accessible with arrow navigation.
- **PriceStack** - displays base price, compare-at price, badges for sale or loyalty discounts, multi-currency dropdown.
- **ReviewSummary** - average rating, histogram, call-to-action button for writing a review.
- **CartMiniPanel** - slide-over with list of items, quick quantity controls, discount entry; uses Prime `Drawer`.
- **ShippingEstimator** - zip/country inputs, rate list, fallback for offline carriers.
- **ProgressTracker** - dynamic steps for checkout or onboarding with optional branching (digital vs. physical).
- **InventoryPillGroup** - shows per-location inventory status with color-coded badges and tooltips linking to transfer actions.
- **ConsignorCard** - vendor avatar, total owed, quick actions for payouts and communications.
- **PaymentMethodPicker** - list of saved cards, Stripe Link, gift cards, store credit chips with expand/collapse for new payment entry.
- **AuditLogItem** - timeline entry with impersonation badge, actor info, metadata expansion.
- **FeatureFlagToggle** - switch, description, scope labels, environment badges; ensures platform admin context is obvious.

<!-- anchor: 2-4-organisms -->
### 2.4 Organisms & Complex Regions
- **HeroBanner** - includes background image/video, gradient overlay, headline, CTA button group; supports multi-tenant theming and AB test flag.
- **ProductCard** - image, badges, rating, price, CTA; variant states for preorders, consignment, digital downloads, subscription availability.
- **CategoryFilterPanel** - accordion filters with checkboxes, range sliders, tag preview; accessible closing, mobile slide-in variant.
- **ProductDetail** - layout with sticky gallery, info column (description, specs, variant selector), meta (shipping, returns) using accordion.
- **CartPage** - table of items with editing controls, summary card, loyalty redemption, shipping estimator, gift options, cross-sell carousel.
- **CheckoutShell** - one-page layout splitting form steps and order recap; includes sticky progress tracker, error summary anchor.
- **AccountDashboard** - cards for orders, addresses, loyalty, saved methods; integrates session management widget.
- **AdminDashboard** - KPI grid, low stock list, open orders table, consignment alerts, recent activity feed; features per-widget loading skeletons.
- **ProductEditor** - multi-tab form (details, variants, media, SEO, consignment, inventory), sticky action bar with validation summary.
- **InventoryLocationBoard** - map/list hybrid showing on-hand quantities, transfer controls, filter for aging stock.
- **ConsignmentPortal** - vendor view for balances, statements, notifications, payout requests with status chips.
- **OrderTimeline** - vertical timeline of events, inline actions for refunds, restocking, re-ship; includes attachments component.
- **POSRegister** - layout with product search, cart summary, payment keypad, offline indicator, hardware status.
- **PlatformStoreDirectory** - table with filtering, plan badges, status toggles, impersonate button, metrics columns.
- **PlatformImpersonationBar** - global persistent bar showing acting user, tenant, ability to exit, reason field.

<!-- anchor: 2-5-templates -->
### 2.5 Template & Page Assemblies
- **Storefront Pages**: home, category, product, search, cart, checkout, account, loyalty, static content.
- **Admin Pages**: dashboard, products, variants matrix, inventory, consignors, orders, customers, loyalty, reports, settings, email templates, POS settings.
- **Platform Admin**: overview, store list, store detail, impersonation audit, system health, billing, support queue.
- Each template references organism components with layout wrappers and includes instrumentation IDs for analytics.

<!-- anchor: 2-6-component-handoff -->
### 2.6 Component Handoff & Documentation Strategy
- Storybook (for Vue) and Pattern Lab-like docs (for Qute fragments) share token data and embed accessibility checklists.
- Component spec includes schema: purpose, props, states, interactions, dependencies, analytics events, performance budgets (time to interactive, render weight).
- Each component references feature flags controlling its rollout; docs show dependency graph so release managers know which toggles to flip.

<!-- anchor: 2-7-component-states -->
### 2.7 Component State Matrix
- **Idle**: default appearance with accessible focus outlines.
- **Hover**: color shift + subtle elevation for CTAs.
- **Active**: depressed button state, pressed icon highlight.
- **Loading**: spinner or progress indicator replacing text but preserving button width.
- **Disabled**: reduced opacity plus explicit tooltip when disabled for permissions.
- **Error**: message with inline icon, `aria-live` to announce error context.
- For data components, states include **Empty**, **Partial**, **Overflow**, and **Offline**; each has content guidelines to keep user informed.

<!-- anchor: 2-8-component-hierarchy-diagram -->
### 2.8 Component Hierarchy Diagram (PlantUML)
~~~plantuml
@startuml
skinparam componentStyle rectangle
rectangle "Atoms" {
  [TextLabel]
  [IconButton]
  [Badge]
  [InputField]
  [SelectField]
  [ToggleSwitch]
  [Avatar]
  [CurrencyText]
}
rectangle "Molecules" {
  [FormRow] --> [InputField]
  [SearchBar] --> [InputField]
  [SearchBar] --> [IconButton]
  [VariantSelector] --> [Badge]
  [MediaGallery] --> [Avatar]
  [CartMiniPanel] --> [FormRow]
}
rectangle "Organisms" {
  [ProductCard] --> [MediaGallery]
  [ProductCard] --> [PriceStack]
  [CartPage] --> [CartMiniPanel]
  [CheckoutShell] --> [ProgressTracker]
  [ProductEditor] --> [FormRow]
  [AdminDashboard] --> [Badge]
}
rectangle "Templates" {
  [StorefrontHome] --> [ProductCard]
  [CheckoutPage] --> [CheckoutShell]
  [AdminProducts] --> [ProductEditor]
  [PlatformStores] --> [AdminDashboard]
}
@enduml
~~~

<!-- anchor: 2-9-component-accessibility -->
### 2.9 Component Accessibility Notes
- All interactive components expose keyboard shortcuts documented within tooltips and command palette.
- Components implement ARIA roles only when semantic HTML insufficient; avoid role duplication to maintain screen reader stability.
- Error messaging tied to inputs via `aria-describedBy` and includes actionable guidance (e.g., “Enter SKU with max 32 characters; 48 provided”).
- Data tables include caption, summary, and sticky column toggles accessible via keyboard.

<!-- anchor: 2-10-component-performance -->
### 2.10 Component Performance Budgets
- ProductCard: ≤35KB of combined images at initial load via responsive `<picture>`; lazy load additional media.
- CheckoutShell: initial interactive bundle ≤120KB gzipped; split shipping calculators and Stripe scripts behind intersection observers.
- AdminDashboard: virtualization ensures <16ms frame budget even with 200 rows; prefer server-driven pagination.
- POSRegister: offline bundle with caching strategy ensures <1s load on cold start; hardware connectors lazy-loaded.

<!-- anchor: 2-11-cross-context-components -->
### 2.11 Cross-Context Components
- Command palette (admin + platform) surfaces quick actions; uses search service, respects RBAC, and tracks usage analytics.
- Notification center: consistent list pattern for storefront accounts (order updates) and admin (system alerts); uses same molecule with props for content, icon, CTA.
- Session viewer: component showing active sessions, device info, revoke buttons; reused across customer self-serve and staff admin.

<!-- anchor: 2-12-storefront-component-inventory -->
### 2.12 Storefront Component Inventory
- **AnnouncementBar** - dismissible strip for shipping promos or alerts; auto-rotates messages when multiple exist.
  - Props: `tone`, `message`, `cta`, `dismissible`, `scheduling`.
  - States: standard, info, alert, impersonation (locks dismiss).
- **MegaMenu** - supports up to three levels of categories with featured image slots.
  - Props: `columns`, `featuredProduct`, `alignment`, `entryAnimations`.
  - Accessibility: arrow key navigation, `aria-expanded`, focus trapping per column.
- **CollectionCarousel** - horizontally scrollable cards with progress indicator.
  - Props: `items`, `cardsPerView`, `autoPlay`, `analyticsId`.
  - Responsive: uses CSS snap points on mobile, arrow controls on desktop.
- **StoryBlock** - mix of text/image layout; supports optional video, CTA pair.
  - Props: `layout` (image-left, image-right, split), `backgroundTone`, `eyebrow`.
  - Content guidelines enforce 2-3 sentences max for scannability.
- **TrustBadgeRow** - row of icons/text for shipping, returns, support.
  - Props: `items[] { icon, label, description }`.
  - Variation for B2C vs consignment stores toggles icons automatically.
- **ReviewCarousel** - loop of testimonials with rating highlight.
  - Props: `reviews`, `autoScroll`, `showAvatar`.
  - Includes `aria-roledescription="carousel"` and pause controls.
- **HeadlessEmbedTile** - embed for blog posts or external CMS sections.
  - Props: `src`, `height`, `fallback`, `lazy`.
  - Security: uses sandboxed iframe with allowed domains defined in settings.
- **GiftCardPurchaseForm** - amount selection, personalization, scheduling.
  - Props: `presets`, `customAmount`, `recipientFields`.
  - Validations cover amount ranges, email formats, message length.
- **LoyaltyPromoPanel** - shows current tier benefits and CTA to learn more.
  - Props: `tier`, `pointsToNext`, `rewards[]`.
  - Variation for guests encourages registration by showing potential savings.
- **InventoryNotice** - “Only X left” indicator with consignment-specific messaging.
  - Props: `quantity`, `threshold`, `consignment`.
  - Accessibility: includes screen reader only text describing urgency.
- **DigitalDownloadBadge** - indicates digital fulfillment and download size.
  - Props: `fileType`, `size`, `availability`.
  - Supports tooltip describing license rules.
- **SubscriptionPlanPicker** - cards listing plan intervals, price, benefits.
  - Props: `plans`, `selectedPlanId`, `showTrial`.
  - Multi-currency aware; shows price per billing cycle with `CurrencyText`.
- **ServiceBookingWidget** - date/time selector integrated with calendar API.
  - Props: `availability`, `duration`, `resource`.
  - Surface timezone info and reschedule policy inline.
- **SearchSuggestionsPanel** - shows trending queries, categories, products.
  - Props: `query`, `suggestions`, `history`.
  - Keyboard nav with `aria-activedescendant`.
- **MiniCartTrigger** - icon with badge; opens CartMiniPanel.
  - Props: `count`, `subtotal`, `ariaLabel`.
  - Provides `aria-live` polite updates for count changes.
- **FooterBuilder** - manages multi-column link lists, newsletter signup, policy links, social icons.
  - Props: `columns`, `logo`, `certifications`, `paymentIcons`.
  - Config UI ensures consistent order across tenants to maintain accessibility.
- **Breadcrumbs** - hierarchical navigation with separators.
  - Props: `items`, `truncate`, `mobileCollapse`.
  - Announces position to screen readers via `aria-current="page"`.
- **FacetTagStrip** - displays applied filters with remove buttons.
  - Props: `filters`, `clearAll`.
  - Keyboard accessible remove buttons with `aria-label`.
- **PromoCountdown** - timer for limited-time deals.
  - Props: `endTime`, `timezone`, `message`.
  - Honors reduced motion by switching to static display.
- **ProductComparisonTable** - comparison grid for up to four items.
  - Props: `products`, `attributes`.
  - On mobile, collapses into swipeable cards with sticky headers.
- **SearchResultBadge** - indicates consignment, digital, subscription status per result.
  - Props: `type`, `tone`, `tooltip`.
  - Works alongside rating icons without layout shift.
- **StaticPageHero** - large header for policy/about pages.
  - Props: `title`, `subtitle`, `backgroundType`.
  - Ensures high contrast for long-form content intros.
- **ReturnPolicyAccordion** - collapsible details for shipping/returns.
  - Props: `sections`, `defaultOpen`.
  - Maintains `aria-expanded` sync across toggle + icon.
- **GiftWrapSelector** - optional service cross-sell.
  - Props: `options`, `pricing`, `image`.
  - Inline preview updates order summary totals live.
- **StoreLocatorWidget** - map/list of physical locations.
  - Props: `locations`, `selected`, `showInventory`.
  - Tapping location updates shipping estimates for pickup options.
- **PartnerBanner** - highlights marketplaces or integrations.
  - Props: `logo`, `text`, `link`.
  - Presents FTC-compliant disclosure text automatically.

<!-- anchor: 2-13-admin-component-inventory -->
### 2.13 Admin Component Inventory
- **CommandPalette** - quick action overlay triggered via `⌘K`/`Ctrl+K`.
  - Data source: aggregated search endpoints for products, orders, customers, settings.
  - Security: respects RBAC; restricted actions hidden or disabled with tooltip.
- **FilterDrawer** - slide-out advanced filters for tables.
  - Props: `fields[]` with type definitions, `savedFilters`.
  - Includes preview chips and ability to share filter via URL query.
- **BulkActionBar** - sticky panel for multi-select actions.
  - Props: `selectedCount`, `actions`, `context`.
  - Displays background job indicators when actions queue async tasks.
- **TableToolbar** - search, columns, density toggle, export button.
  - Props: `resource`, `defaultView`, `viewSwitcher`.
  - Hooks into `reports` service for scheduled exports.
- **VariantMatrix** - spreadsheet-like grid for up to 2000 variants.
  - Features virtualization, inline editing, validation badges.
  - Supports keyboard shortcuts for navigation and duplication.
- **MediaUploader** - drag-and-drop with progress, asynchronous processing states.
  - Props: `accept`, `maxSize`, `variants`.
  - Shows processing timeline with background job references.
- **Timeline** - event log component reused for orders, products, audits.
  - Props: `events[]`, `filter`, `showActor`.
  - Supports attachments preview and comment threads.
- **InlineAlert** - page-level warnings for compliance tasks, shipping outages.
  - Props: `tone`, `title`, `description`, `actions`.
  - Dismissal recorded per user to avoid repeated noise.
- **MetricsCard** - KPI display with trend indicator and sparkline.
  - Props: `title`, `value`, `change`, `timeframe`.
  - Tooltips explain calculations and data freshness timestamp.
- **WizardStepper** - multi-step creation/edit flows (product, loyalty, feature flag).
  - Props: `steps`, `currentStep`, `onExit`.
  - Each step validates before enabling next with summary of errors.
- **AuditTrailModal** - overlay showing filtered events for selected entity.
  - Props: `resourceType`, `resourceId`.
  - Searchable, exportable, includes impersonation context.
- **RoleMatrix** - matrix of roles vs permissions with toggles.
  - Props: `roles`, `permissions`, `editable`.
  - Shows warnings when removing permissions required by security policy.
- **NotificationCenter** - list of alerts grouped by severity.
  - Props: `groups`, `markAll`.
  - Integrates with WebSocket feed for near real-time updates.
- **JobStatusPanel** - monitors DelayedJob queues.
  - Props: `queues`, `status`, `retry`.
  - Provides UI to retry or cancel stuck jobs with confirmation.
- **DomainManager** - list of domains, verification status, SSL info.
  - Props: `domains`, `certificateStatus`, `actions`.
  - Visual timeline of verification steps and expiration warnings.
- **CarrierCredentialForm** - per-carrier API key entry with validation.
  - Props: `carrier`, `fields`, `testConnection`.
  - Inline test action surfaces logs for debugging.
- **EmailTemplateEditor** - WYSIWYG with markdown toggle, preview, localization placeholders.
  - Props: `template`, `variables`, `subject`.
  - Enforces brand colors and includes accessible preview.
- **RevenueHeatmap** - interactive calendar view of sales.
  - Props: `data`, `granularity`, `timezone`.
  - Hover reveals exact metrics, includes colorblind-friendly palette.
- **POSHardwarePanel** - configure terminals, printers, drawers.
  - Props: `devices[]`, `status`, `lastSeen`.
  - Buttons for pairing/unpairing; offline states highlighted.
- **FeatureRolloutPanel** - list of flags with tenant overrides.
  - Props: `flags`, `tenantStatus`, `globalDefault`.
  - Displays experiment metrics if available.

<!-- anchor: 2-14-platform-component-inventory -->
### 2.14 Platform Admin Component Inventory
- **StoreHealthCard** - summarizes uptime, job queue, SSL state per store.
  - Props: `store`, `uptime`, `alerts`.
  - Includes quick link to impersonate or suspend.
- **BillingPanel** - shows plan, fees, revenue share.
  - Props: `plan`, `usage`, `stripeStatus`.
  - Offers CTA to adjust plan or send invoice.
- **SupportConversationPanel** - triage support tickets with impersonation shortcuts.
  - Props: `ticket`, `messages`, `attachments`.
  - Allows linking to audit logs or knowledge base.
- **ImpersonationAuditTable** - advanced filters (date, actor, tenant, target).
  - Props: `filters`, `columns`.
  - Export to CSV with reason/ticket for compliance.
- **SystemAlertFeed** - global incidents, rate limit warnings, FFmpeg failures.
  - Props: `alerts`, `severity`, `acknowledged`.
  - Includes runbook links and ability to assign owner.
- **TenantCommandPanel** - actions (suspend, resume, reset cache, rebuild theme).
  - Props: `tenant`, `commands`, `confirmation`.
  - Each action describes risk and rollback plan.
- **AnalyticsComparisonChart** - compare stores by metric over time.
  - Props: `selectedStores`, `metric`, `timeframe`.
  - Highlights outliers and integrates with filter chips.

<!-- anchor: 2-15-pos-components -->
### 2.15 POS-Specific Components
- **QuickProductGrid** - large buttons for frequently sold items; supports barcode scanning fallback.
  - Props: `products`, `categories`, `favorite`.
  - Offline ready with cached dataset.
- **CartSummarySticky** - always-visible panel showing subtotal, taxes, tendered amount, change due.
  - Props: `lineItems`, `paymentState`, `discounts`.
  - Buttons for hold/retrieve transaction.
- **PaymentPad** - numeric keypad, tender type selection (cash, card, split).
  - Props: `paymentMethods`, `amountDue`.
  - Haptic feedback support for tablet hardware.
- **CustomerLookupModal** - search by name, email, loyalty ID.
  - Props: `query`, `results`.
  - Displays loyalty balance and outstanding layaway items.
- **OfflineQueuePanel** - list of queued transactions with retry controls.
  - Props: `transactions`, `status`, `errors`.
  - Provides export + clear actions governed by permission.
- **HardwareStatusFooter** - icons for printer, cash drawer, barcode scanner, network.
  - Props: `devices`, `status`.
  - Animations reflect connecting/disconnecting states.

<!-- anchor: 2-16-component-analytics -->
### 2.16 Component Analytics Contracts
- Each interactive component emits events with `eventName`, `context`, `tenantId`, `userId`, `featureFlag`.
- Example: ProductCard `click_add_to_cart`, payload includes SKU, variantId, price, currency, source (grid, recommendation, search).
- CheckoutShell records timings per step (address, shipping, payment) to monitor drop-off.
- Admin ProductEditor logs validation errors to identify confusing fields; aggregated metrics surface in design reviews.
- Platform impersonation components annotate every action with `impersonationSessionId` for cross-linking with audit logs.

<!-- anchor: 3-application-structure -->
## 3. Application Structure & User Flows

<!-- anchor: 3-1-route-definitions -->
### 3.1 Route Definitions

<!-- anchor: 3-1-1-storefront-routes -->
#### 3.1.1 Storefront Routes
| Route | Description | Components | Access |
| --- | --- | --- | --- |
| `/` | Tenant-branded home with hero, collections, featured products, loyalty highlights | HeroBanner, ProductCardGrid, CategoryRail, AnnouncementBar | Public |
| `/category/:slug` | Hierarchical browsable listing with filters and breadcrumbs | CategoryFilterPanel, ProductCardGrid, Breadcrumbs, PaginationBar | Public |
| `/collections/:slug` | Curated grouping with storytelling sections | StoryBlock, ProductCardGrid, ContentBlock | Public |
| `/product/:slug` | Detailed product view with variants, reviews, consignment notes | ProductDetail, MediaGallery, VariantSelector, ReviewSummary, PriceStack | Public |
| `/search` | Keyword search with filters, auto-suggestions, trending queries | SearchBar, ResultList, EmptyStateSmartHints | Public |
| `/cart` | Persistent cart manager with discount codes and shipping estimator | CartPage, DiscountForm, ShippingEstimator, CrossSellCarousel | Auth optional |
| `/checkout` | One-page checkout with address validation, payment, summary | CheckoutShell, ProgressTracker, PaymentMethodPicker, AddressForm, ReviewOrder | Auth optional |
| `/account/login` | Customer login with social options, security cues | AuthForm, SocialLoginButtons, SecurityTipsList | Public |
| `/account/register` | Registration with marketing consent and password strength meter | AuthForm, PasswordMeter, TermsModal | Public |
| `/account` | Dashboard summarizing orders, loyalty, saved methods | AccountDashboard, OrderList, LoyaltyPanel, SessionManager | Auth |
| `/account/orders/:id` | Order detail with shipment tracking and returns | OrderTimeline, ShipmentTracker, ReturnCTA | Auth |
| `/account/addresses` | Address book manager with default toggles | AddressList, AddressModal, MapPreview | Auth |
| `/account/loyalty` | Loyalty ledger, reward redemption, tier progress | LoyaltyLedger, TierProgress, RewardRedeemForm | Auth |
| `/gift-cards` | Purchase and redeem gift cards | ProductCard, GiftCardForm, BalanceChecker | Public |
| `/pages/:slug` | Merchant-defined static pages (about, policies) | ContentBlock, FeedbackFooter | Public |
| `/consignor/login` | Vendor portal entry with branding | AuthForm, InfoPanel | Restricted |
| `/consignor/dashboard` | Balance overview, payouts, item statuses | ConsignmentPortal, PayoutList, NotificationCenter | Consignor |

<!-- anchor: 3-1-2-admin-routes -->
#### 3.1.2 Admin Routes (`/admin/*`)
| Route | Description | Key Modules | Access |
| --- | --- | --- | --- |
| `/admin` | Today view with KPIs, alerts, quick actions | AdminDashboard, TaskPanel, ActivityFeed | Owner/Admin |
| `/admin/products` | Product list with filters, bulk actions | ProductTable, FilterDrawer, BulkActionBar | Owner/Admin/Manager |
| `/admin/products/new` | Guided creation wizard | ProductEditor, VariantMatrix, MediaUploader | Owner/Admin/Manager |
| `/admin/products/:id` | Edit product with tabs and timeline | ProductEditor, AuditLogItem, PricingSimulator | Owner/Admin/Manager |
| `/admin/inventory/locations` | Multi-location overview | InventoryLocationBoard, MapWidget, TransferDrawer | Owner/Admin/Manager |
| `/admin/inventory/transfers` | Transfer initiation and tracking | TransferTable, TransferDetail, BarcodePrintPanel | Owner/Admin/Manager |
| `/admin/orders` | Order listing, search, filtering | OrderTable, StatusTabs, TagFilters | Owner/Admin/Manager/Staff (read) |
| `/admin/orders/:id` | Order detail and fulfillment actions | OrderTimeline, ShipmentCreator, RefundModal | Owner/Admin/Manager/Staff (limited) |
| `/admin/customers` | Customer directory | CustomerTable, SegmentFilters, ImportExport | Owner/Admin/Manager |
| `/admin/customers/:id` | Profile with orders, loyalty, sessions | ProfileHeader, LoyaltyPanel, SessionViewer | Owner/Admin/Manager |
| `/admin/consignors` | Vendor management | ConsignorTable, CommissionEditor, PayoutQueue | Owner/Admin |
| `/admin/consignors/:id` | Vendor detail & payouts | ConsignorCard, ContractViewer, StatementGenerator | Owner/Admin |
| `/admin/loyalty` | Program configuration & ledger | LoyaltyConfigurator, TierDesigner, LedgerTable | Owner/Admin |
| `/admin/reports` | Catalog of reports + scheduler | ReportGallery, ScheduleForm, HistoryList | Owner/Admin |
| `/admin/settings/store` | Store profile, branding, domains | SettingsNav, BrandEditor, DomainManager | Owner |
| `/admin/settings/payments` | Stripe Connect onboarding | PaymentConfigPanel, StripeStatusCard | Owner |
| `/admin/settings/shipping` | Carrier credentials & profiles | CarrierEditor, RatePreview, ZoneTable | Owner/Admin |
| `/admin/settings/staff` | Staff and permissions | StaffTable, RoleMatrix, InviteModal | Owner |
| `/admin/settings/feature-flags` | Tenant-level toggles | FeatureFlagToggleList, ExperimentDetail | Owner/Admin |
| `/admin/pos` | POS setup | POSRegisterPreview, HardwareConfig, OfflinePolicyEditor | Owner/Admin |

<!-- anchor: 3-1-3-platform-routes -->
#### 3.1.3 Platform Admin Routes (`/platform/*`)
| Route | Description | Components | Access |
| --- | --- | --- | --- |
| `/platform` | Platform KPIs, store health, alerts | PlatformDashboard, StoreMetricGrid, JobStatusFeed | Super Admin |
| `/platform/stores` | All stores table with filters | PlatformStoreDirectory, PlanFilterBar, StatusChips | Super Admin |
| `/platform/stores/:id` | Store detail, tenant context, impersonation controls | StoreProfileCard, BillingPanel, TenantActions, ImpersonationBar | Super Admin |
| `/platform/impersonation/logs` | Audit of impersonation sessions | AuditTable, FilterDrawer, ExportButton | Super Admin/Support |
| `/platform/sessions` | Cross-store session analytics | SessionHeatmap, GeoList, RiskAlert | Security |
| `/platform/reports` | Platform-level exports | ReportGallery, ScheduleForm, HistoryList | Super Admin |
| `/platform/users` | Platform staff management | StaffTable, RoleMatrix, 2FAStatusList | Super Admin |
| `/platform/support` | Support queue with impersonation shortcuts | SupportInbox, ConversationPanel, QuickActions | Support |
| `/platform/system-health` | Worker/job health, FFmpeg status, rate limit monitors | SystemStatusCards, WorkerTimeline, AlertBanner | SRE |

<!-- anchor: 3-1-4-headless-api -->
#### 3.1.4 Headless & Embedded Widgets
- `/api/v1/catalog/products` - consumed by embedded experiences; responses include pagination metadata aligning with UI virtualization.
- `/api/v1/cart` - polled by mini cart widget; UI caches ETag for efficient updates.
- `/api/v1/customers/sessions` - supports session manager components; ensures JWT scope `account:sessions`.
- `/embeds/cart/status.js` - script for external CMS showing cart count; uses same tokens and accessible markup.

<!-- anchor: 3-2-critical-user-journeys -->
### 3.2 Critical User Journeys (PlantUML)

<!-- anchor: 3-2-1-checkout-flow -->
#### 3.2.1 Customer Checkout Flow
~~~plantuml
@startuml
start
:View CartPage;
if (Logged in?) then (yes)
  :Prefill addresses;
else (no)
  :Offer guest checkout + login prompt;
endif
:Enter shipping address;
:Validate via postal API;
if (Validation success?) then (yes)
  :Select shipping rate;
else (no)
  :Show inline error + manual override;
endif
:Apply discounts + loyalty;
:Render payment form (Stripe Elements);
:Submit payment;
if (Payment approved?) then (yes)
  :Create order + send confirmation;
  :Show Order Confirmation with upsell;
else (no)
  :Display error + support contact;
endif
stop
@enduml
~~~
- Each step corresponds to UI modules (AddressForm, ShippingEstimator, PaymentMethodPicker) with instrumentation events (`checkout_step_completed`).
- Errors trigger inline summary pinned at top with anchor links to problematic fields.

<!-- anchor: 3-2-2-consignor-payout-flow -->
#### 3.2.2 Consignor Payout Flow
~~~plantuml
@startuml
start
:Consignor logs into portal;
:View balance overview;
if (Meets payout threshold?) then (yes)
  :Click "Request Payout";
  :Confirm Stripe Connect details;
  :Submit payout request;
  :Admin receives notification;
  :Admin reviews and approves;
  :Status updates + email notification;
else (no)
  :Show progress meter + inventory tips;
endif
stop
@enduml
~~~
- Portal surfaces payout threshold, eligible inventory, and statement downloads with consistent components and empty-state messaging.

<!-- anchor: 3-2-3-platform-impersonation-flow -->
#### 3.2.3 Platform Impersonation & Exit Flow
~~~plantuml
@startuml
start
:Platform admin selects store from directory;
:Click "Impersonate";
:Enter reason + ticket link;
:Confirm MFA prompt;
:Switch to tenant admin view with impersonation bar;
if (Need customer-level impersonation?) then (yes)
  :Launch customer finder modal;
  :Impersonate customer -> storefront view;
  :Perform troubleshooting;
  :Log actions in audit stream;
else (no)
  :Complete admin tasks;
endif
:Exit impersonation via banner button;
:Return to platform console with summary toast;
stop
@enduml
~~~
- Banners persist across all routes, include countdown timer for inactivity, and provide quick link to audit log entry.

<!-- anchor: 3-3-flow-details -->
### 3.3 Flow Details & Edge States
- **Checkout**: fallback path for shipping carriers offline uses table-rate card with disclaimers; UI logs event and displays info tooltip linking to support article.
- **Account Recovery**: login pages include `Forgot password` verifying email with rate limit messaging; UI ensures cross-tenant CSRF token resets after each attempt.
- **Gift Card Purchase**: after checkout, confirmation page shows shareable link, print option, and `Add to Wallet` button; ensures digital products flagged for limited downloads.
- **Subscription Enrollment**: flows use progressive disclosure; when selecting plan, UI fetches proration info from Stripe via backend and displays schedule in timeline component.

<!-- anchor: 3-4-pos-offline-flow -->
### 3.4 POS Offline Flow UX
- Offline indicator pinned to POS header with pulsing icon; tooltips explain automatic retry behavior.
- Transaction queue list shows local timestamp, staff initials, expected sync order; uses `OfflineBadge`.
- When connection restored, UI transitions queued items to `Syncing` state with progress bar; errors produce inline fix suggestions (e.g., re-swipe, capture signature).
- Staff can export offline queue as encrypted file for support; UI warns about sensitive data handling.

<!-- anchor: 3-5-consignment-experience -->
### 3.5 Consignment Experience Touchpoints
- Admin dashboard tile surfaces incoming consignor shipments with CTA to intake wizard.
- Intake wizard includes barcode scanning component, cost basis entry, expiration policies, and summary step with vendor confirmation.
- Email templates preview component ensures vendor communications reflect brand tokens.
- Consignor portal shows timeline for each item (received, on floor, sold, expired) with color-coded statuses and filter chips.

<!-- anchor: 3-6-contextual-navigation -->
### 3.6 Contextual Navigation & Orientation
- Storefront nav supports up to two levels of custom links; mobile uses accordion to maintain tap targets.
- Admin navigation uses collapsible sidebar with section grouping (Commerce, Customers, Consignment, Insights, Settings); badges show new features via feature flag metadata.
- Platform admin navigation highlights current tenant context with pill showing store slug; top bar features global search and quick filters (Plan, Status, Region).
- Breadcrumb component used across admin and storefront account pages to reduce disorientation.

<!-- anchor: 3-7-empty-states -->
### 3.7 Empty State & Upsell Strategy
- Each list/table includes first-time empty illustrations referencing merchant vertical (sourced from tokenized asset library).
- Provide actionable next steps (import products, connect Stripe, invite staff) with CTA buttons, linking to setup wizard.
- Display metrics about benefits of completing steps, e.g., “Stores with shipping rates configured convert 17% better.”
- For platform admin, empty states supply ops health tips or links to documentation to maintain productivity even without data.

<!-- anchor: 3-8-notification-strategy -->
### 3.8 Notification & Messaging Touchpoints
- Toast center limited to three concurrent notifications to prevent overload; queue others with progress indicator.
- Email + in-app notifications share consistent taxonomy (Alert, Reminder, Success, Info) to ensure user recognizes severity.
- Admin and platform consoles include inbox panel summarizing pending validations, shipments, payouts; each entry uses Molecule patterns defined earlier.
- Customers opt into SMS/email from account page; UI surfaces consent history and allows revocation.

<!-- anchor: 3-9-flow-narratives -->
### 3.9 Additional User Flow Narratives
- **Merchant Onboarding**: wizard guides through subdomain selection, branding, product import, payment connection, shipping setup; progress tracker persists across sessions and surfaces unresolved tasks on admin home.
- **Bulk Product Import**: user uploads CSV via MediaUploader; UI displays schema validation preview, mapping assistance, background job status with toast updates linking to Report History.
- **Inventory Transfer**: manager selects origin/destination via InventoryLocationBoard, chooses variants with inline stock levels, prints barcode packing slips, and monitors transfer timeline.
- **Consignor Contract Renewal**: admin receives alert, opens ConsignorCard, reviews proposed commission changes, e-signs document using embedded viewer, and sends confirmation to vendor.
- **Customer Loyalty Redemption**: logged-in customer sees points in cart summary, selects redemption slider, UI recalculates totals and warns if redemption affects shipping threshold.
- **Platform Incident Response**: SRE sees alert, opens SystemHealth, inspects JobStatusPanel, impersonates tenant to verify checkout, toggles kill switch if needed, posts update via NotificationCenter.
- **POS Offline Recovery**: staff completes offline sale, sees queue entry, once network restored, queue auto-syncs; UI shows success or prompts manual resolution with reasons (duplicate, amount mismatch).
- **Gift Card Balance Check**: user enters code on dedicated form; UI fetches via API, shows balance, and offers CTA to redeem in cart.
- **Session Revocation**: customer enters account security page, sees list of devices, clicks revoke; modal confirms action and describes effect on that device, UI updates list instantly.

<!-- anchor: 3-10-information-architecture -->
### 3.10 Information Architecture Considerations
- Content grouped by user intent: Discover (home, category, search), Decide (product, reviews, policies), Commit (cart, checkout), Retain (account, loyalty, consignor portal).
- Admin organizes around Operate (orders, inventory), Grow (products, marketing, loyalty), Govern (consignment, reports, settings).
- Platform console centers on Monitor (health, stores), Intervene (impersonation, suspend), Support (tickets, reports).
- Navigation labels stay consistent across contexts; e.g., “Inventory” always refers to stock levels, never purchase orders.

<!-- anchor: 3-11-error-resilience -->
### 3.11 Error Handling & Resilience UX
- Inline errors describe issue + remediation (e.g., “Shipping carrier FedEx is offline. Try UPS or retry in 5 minutes.”).
- Toasts include `View Details` button linking to logs or help articles.
- Retry patterns: show spinner, after three attempts reveal fallback path, log telemetry event.
- Maintenance windows: storefront shows friendly page with countdown and email capture; admin/platform show skeleton plus message with Slack/Statuspage links.
- Rate limit messaging: uses neutral tone, shows next available time and contact for higher limits.

<!-- anchor: 3-12-personalization -->
### 3.12 Personalization Hooks
- Storefront recommends products based on browsing history stored per customer; UI includes “Because you viewed X” section.
- Loyalty program surfaces tailored perks; UI shows upcoming tier benefits and targeted promotions.
- Admin dashboards allow saved views per user; default view flagged in toolbar.
- Platform admin can bookmark stores; quick access list appears in header.

<!-- anchor: 4-cross-cutting-concerns -->
## 4. Cross-Cutting Concerns

<!-- anchor: 4-1-state-management -->
### 4.1 State Management
- Storefront Qute pages rely on server-rendered HTML backed by Caffeine-cached data; progressive enhancement via small Alpine/PrimeUI scripts handles cart mini-panel and checkout interactions.
- Admin Vue SPA uses Pinia for global state with modules: `auth`, `tenant`, `featureFlags`, `catalog`, `orders`, `consignment`, `inventory`, `loyalty`, `reports`, `notifications`.
- Pinia stores differentiate between server state (synced via REST) and UI state (filters, sort, selection). Server state caches include TTL metadata and ETag to avoid stale writes.
- Platform admin extends same store with `platform` namespace covering stores, impersonation, health metrics; watchers ensure impersonation context invalidates caches.
- POS offline state stored in IndexedDB with encryption; UI exposes sync queues and conflict resolution modals.

<!-- anchor: 4-1-1-state-patterns -->
#### 4.1.1 State Patterns
- Use `queryKey = [tenantId, resource, params]` to align fetch caching across admin modules; failing request surfaces inline error with retry CTA.
- Derived state (computed totals, filter counts) memoized to avoid recalculations when data sets large.
- Action logging middleware records when destructive actions triggered, capturing impersonation context for audit surfaces.

<!-- anchor: 4-2-responsive-design -->
### 4.2 Responsive Design (Mobile-First)
- Breakpoints follow Tailwind defaults: `sm` 640px, `md` 768px, `lg` 1024px, `xl` 1280px, `2xl` 1536px; POS uses custom `pos` breakpoint at 900px for tablet heuristics.
- Layout patterns:
  - **Stacked-first**: forms render as single column on mobile, expand to two columns on `lg`.
  - **Priority+ navigation**: header shows top nav links up to available width; overflow collapses into “More” menu.
  - **Hybrid tables**: on mobile, rows collapse into cards with key-value pairs; actions accessible via overflow menu.
  - **Sticky footers**: checkout and POS use sticky summary/payout bars on mobile for quick access.
- Responsive assets: `<picture>` elements supply WebP and fallback JPEG, with sizes attribute tuned per layout; background images use `object-fit: cover`.
- Touch gestures: carousels and filter chips respond to horizontal swipes with momentum but keep arrow controls for desktop.

<!-- anchor: 4-3-accessibility -->
### 4.3 Accessibility (WCAG 2.1 AA)
- Semantic HTML prioritized; ARIA applied sparingly to complement semantics.
- Color contrast validated per tenant using automated script; UI warns if user-selected colors break 4.5:1 ratio.
- Keyboard navigation: skip links at top of every page, focus traps in modals/drawers, logical tab order, visible focus rings.
- Screen reader support: descriptive labels for inputs, accessible form errors, `aria-live` for asynchronous updates (cart changes, POS sync, notifications).
- Motion preferences respected; reduce animations and disable auto-playing videos when `prefers-reduced-motion` true.
- Multi-language future-proofing: ensure dynamic content uses `lang` attributes when store-level languages introduced.
- Testing: integrate axe-core and Storybook accessibility scans in CI; manual audits for checkout, POS, impersonation flows prior to release.

<!-- anchor: 4-4-performance -->
### 4.4 Performance & Optimization
- Budgets: storefront LCP < 2s on 3G, admin interactive < 3.5s, POS offline startup < 1s, platform dashboards < 2.5s.
- Strategies:
  - Code-splitting per route via Vite dynamic imports; admin modules loaded when navigating to domain-specific views.
  - Prefetch critical data server-side for storefront to minimize hydration overhead; use JSON script tag for inline data.
  - Lazy load heavy dependencies (FFmpeg status charts, map widgets, Stripe libraries) when components enter viewport.
  - Use IntersectionObserver for infinite scroll; virtualization for tables >200 rows using Prime `VirtualScroller`.
  - Optimize images through backend resizing to defined tokens; use `loading="lazy"` for non-critical images.
  - Cache HEAD responses for assets with hashed filenames; configure HTTP/2 push only for hero CSS/JS.

<!-- anchor: 4-5-backend-integration -->
### 4.5 Backend Integration Patterns
- Authentication flows rely on JWT cookies for storefront (httpOnly) and Bearer tokens for admin/platform; UI refreshes tokens via silent refresh endpoints.
- API communication uses typed clients generated from OpenAPI; error responses follow RFC 7807 and UI surfaces `message`, `traceId`.
- Feature flags delivered via `/api/v1/flags` endpoint cached for 5 minutes; UI listens for SSE updates in admin to reflect toggles instantly.
- Stripe Connect onboarding screens embed Stripe-hosted components within modals; UI handles return states via query params and toasts.
- Shipping/address integrations: forms display spinner + inline messaging while backend queries carriers; errors present fallback manual entry.

<!-- anchor: 4-6-localization-multicurrency -->
### 4.6 Localization & Multi-Currency Display
- Currency formatting uses Money value object; UI accepts pre-formatted amounts but also stores integer minor units for calculations.
- Multi-currency toggle on storefront uses drop-down near price stack; caches FX rates for 24h and indicates last updated timestamp.
- Date/time formatting uses tenant timezone with fallback to UTC; admin and platform surfaces show timezone inline for clarity.
- Content placeholders and error messages follow consistent casing and punctuation to simplify translation later.

<!-- anchor: 4-7-security-trust -->
### 4.7 Security, Trust, and Compliance Signals
- Checkout displays SSL badge, privacy summary, and Stripe branding to build trust.
- Admin sessions show device info and IP; suspicious login alerts tinted with warning color and include quick revoke buttons.
- Impersonation indicator persists across contexts, includes reason and timer, and disables destructive actions when justification missing.
- Sensitive actions (payouts, refunds, feature toggles) require confirmation modals with textual summary of impact; modals highlight audit logging info.

<!-- anchor: 4-8-observability -->
### 4.8 Observability & Analytics Hooks
- Data attributes `data-telemetry-id` attached to critical elements for frontend analytics correlation with backend traces.
- Event taxonomy defined: `view_*`, `click_*`, `complete_*`, `error_*`, `impersonation_*` to align with backend instrumentation.
- Admin dashboards show `last_refreshed` timestamp referencing reporting ETL schedule; tooltips explain data freshness.
- UI logs include tenant ID, user ID, feature flag context to help Security/Support debug issues quickly.

<!-- anchor: 4-9-feature-flags -->
### 4.9 Feature Flag Exposure
- Feature flag drawer accessible from admin header shows current toggles, description, owner, risk level.
- Beta components display `Beta` pill automatically; tooltips include instructions for feedback.
- When feature disabled, UI gracefully hides components but leaves placeholder text describing upgrade path or timeline.
- Platform admin can override tenant flag values with immediate effect; UI logs change plus reason.

<!-- anchor: 4-10-testing -->
### 4.10 UI Testing Strategy
- Component tests via Vitest + Testing Library covering states, accessibility attributes.
- End-to-end flows automated with Playwright for storefront checkout, admin product CRUD, consignment payouts, impersonation.
- Visual regression using Chromatic/Storybook for Vue components; Qute templates snapshot tested via Percy pipeline.
- POS offline scenarios scripted with Cypress in service worker-enabled environment to verify queue handling.

<!-- anchor: 4-11-accessibility-checklists -->
### 4.11 Module-Specific Accessibility Checklists
- **Checkout**: ensure form fields grouped with `<fieldset>`, provide summary of payment methods accepted, voiceover-friendly instructions.
- **POS**: large font mode toggle, screen reader off by default but accessible summary view for blind staff, audio cues for transactions.
- **Consignor portal**: ensure table virtualization still exposes content to screen readers by using aria-rowcount and aria-posinset.
- **Platform admin**: impersonation banner includes `role="status"` and is programmatically focusable when session starts.

<!-- anchor: 4-12-internationalization -->
### 4.12 Internationalization Readiness
- All copy stored in message catalogs with keys; even though v1 is English-only, UI uses translation helper to avoid concatenated strings.
- Date/time strings use Intl API with locale fallback; format tokens stored centrally.
- Directionality tested for RTL languages to ensure components mirror correctly once translations added.
- Currency/number formatting uses `Intl.NumberFormat` with options derived from tenant base currency.

<!-- anchor: 4-13-product-education -->
### 4.13 Product Education & Onboarding Content
- In-app walkthroughs triggered for first-time merchants; built with Shepherd.js-style overlay but uses design tokens for theming.
- Knowledge base links appear in context-sensitive help icons; tooltips show summary plus “Learn more”.
- Release highlights card displayed after update; includes changelog snippet and CTA to documentation.
- Admin search surfaces docs results alongside entities for faster problem solving.

<!-- anchor: 4-14-audit-compliance -->
### 4.14 Audit & Compliance Visualization
- Sensitive actions display inline audit log snippet showing last change and actor.
- Platform admin views include compliance badges (PCI, SOC readiness) tied to store configuration status.
- Reports labeled with retention requirements; UI warns before deletion or export expiration.
- Consignor contracts show version history and digital signature status with timeline.

<!-- anchor: 4-15-data-privacy-ui -->
### 4.15 Data Privacy UX
- Customer account page includes “Download Data” request button with status tracking; progress indicator shows job completion.
- Delete account flow presents irreversible consequences, data retention timeline, and contact support link.
- Admin views mask sensitive values (SSN/EIN) with reveal-on-click requiring confirmation.
- Logs referencing customer data display hashed IDs to staff; full details accessible only via privileged modal requiring justification.

<!-- anchor: 5-tooling-dependencies -->
## 5. Tooling & Dependencies

<!-- anchor: 5-1-core-dependencies -->
### 5.1 Core Dependencies
- Storefront: Qute templates + Tailwind CSS + PrimeUI JS widgets (cart, checkout) + Alpine-like micro interactions.
- Admin SPA: Vue 3 + Vite + TypeScript + Pinia + PrimeVue + Tailwind CSS + Vue Router + Axios (generated clients).
- Platform admin shares admin codebase but includes additional layout modules and RBAC guard components.
- POS offline: Vite PWA plugin, Workbox for service workers, IndexedDB wrapper for offline queue.
- Data viz: Chart.js + PrimeVue charts, MapLibre for location views, ECharts optional for advanced analytics (feature flagged).
- Iconography: Phosphor Icons, custom vendor icons set.

<!-- anchor: 5-2-dev-tooling -->
### 5.2 Development Tooling
- Tailwind config generated via script reading design tokens from Postgres; stored temporarily then removed after build per compliance.
- ESLint + Prettier enforced for Vue/JS; Stylelint for CSS modules; Qute templates linted via custom rule set verifying anchor comments and accessibility attributes.
- Storybook for Vue components, Fractal/Pattern Lab for Qute fragments; both pipelines include snapshot + accessibility tests.
- GitHub Actions pipeline runs `npm run lint`, `npm run test`, `npm run build`, verifying no type errors and coverage thresholds.
- Cypress/Playwright test suites run nightly and on release candidates with multi-tenant fixture data.

<!-- anchor: 5-3-testing-qa -->
### 5.3 Testing & QA Routines
- Visual diff tests for theming: pipeline builds multiple tenant themes (default, high-contrast, holiday) and compares key pages.
- Manual QA checklist for checkout includes cross-browser (Chrome, Safari, Firefox, Edge) and device variations.
- Accessibility audits executed before releasing major features; results stored in Confluence with remediation owners.
- Load testing for cart/checkout flows ensures UI gracefully handles delayed API responses by showing skeleton/progress states.

<!-- anchor: 5-4-delivery-pipeline -->
### 5.4 Delivery & Theming Pipeline
- Quinoa plugin compiles Vue admin assets and packages into Quarkus resources; hashed filenames referenced in templates with SRI attributes.
- Tailwind JIT builds per environment; tokens injected via env-specific JSON; build script ensures no tenant secrets leak into static output.
- CSS-critical path extracted for storefront hero and checkout to reduce blocking resources; served inline with degrade fallback.
- Service workers pre-cache essential assets for POS and admin offline views; update prompts show version difference and release notes.

<!-- anchor: 5-5-content-governance -->
### 5.5 Content & Localization Governance
- Rich text fields sanitized server-side; UI restricts formatting options to headings, lists, links, images with alt text.
- Markdown preview component ensures merchant policies render consistently; includes accessibility hints.
- Content versioning tracked with change history in admin; UI shows last updated timestamp and author for each policy page.
- Future localization pipeline will export JSON via API; UI already structures copy references through message catalogs.

<!-- anchor: 5-6-design-ops -->
### 5.6 DesignOps & Collaboration
- Figma library mirrors code structure; tokens synchronized via design tokens plugin to keep parity with Tailwind config.
- Designers annotate flows with KPIs, feature flag dependencies, and risk notes in Figma to accelerate dev handoff.
- Weekly design crits review instrumentation dashboards to ensure UX decisions grounded in observed behavior.
- UX researchers feed insights into backlog via shared Notion board linked to component documentation.

<!-- anchor: 5-7-ci-observability -->
### 5.7 CI/CD Observability Hooks
- GitHub Actions publishes Lighthouse scores for storefront and admin preview builds; thresholds enforced.
- Visual regression pipeline uploads diffs to PR comments; developers must approve before merge.
- Build artifacts include asset manifest, token snapshots, Storybook static site, PlantUML diagrams exported as PNG/SVG for documentation.
- Deployment notifications posted to Slack with release notes referencing UI-impacting changes.

<!-- anchor: 5-8-content-testing -->
### 5.8 Content Testing & Experimentation
- Experiment framework toggles variant markup via feature flags; UI logs variant impression/click metrics.
- Merchants can create simple content experiments (two hero headlines); admin UI ensures sample size/time recommended before concluding.
- Copy testing integrated with internal review tool; ensures regulatory disclaimers remain present on checkout pages.
- Experiment results visualized as line charts with statistical significance indicator and guidance on next steps.

<!-- anchor: 6-component-specification-appendix -->
## 6. Component Specification Appendix

<!-- anchor: 6-1-component-props-matrix -->
### 6.1 Component Props Matrix
| Component | Key Props | Notes |
| --- | --- | --- |
| HeroBanner | `title`, `subtitle`, `ctaPrimary`, `ctaSecondary`, `media`, `gradient` | Accepts image/video; ensures `alt` text required. |
| AnnouncementBar | `message`, `tone`, `dismissible`, `schedule`, `link` | Stores dismiss state per session to respect privacy. |
| ProductCard | `product`, `ctaType`, `badgeList`, `priceVariant`, `trackingId` | Lazy loads secondary image on hover; supports quick add. |
| MediaGallery | `mediaItems`, `initialIndex`, `zoom`, `videoPoster`, `arModel` | Keyboard accessible; uses pointer gestures for zoom. |
| VariantSelector | `options`, `selection`, `disabledOptions`, `layout` | Supports swatches, dropdowns, and matrix; 2-stage saga for large sets. |
| PriceStack | `price`, `compareAt`, `currency`, `discount`, `loyaltyApplied` | Displays savings message and multi-currency toggle. |
| CartMiniPanel | `items`, `subtotal`, `actions`, `featureFlags` | Slide-over integrates with focus trap and ESC close. |
| CheckoutShell | `steps`, `currentStep`, `errors`, `summary`, `flags` | Provides step analytics hooks and inline validation summary. |
| AddressForm | `fields`, `addressType`, `validation`, `prefill` | Supports auto-complete and manual entry fallback. |
| PaymentMethodPicker | `methods`, `selected`, `addMethod`, `giftCards`, `storeCredit` | Works with Stripe Link and platform gift cards. |
| LoyaltyPanel | `points`, `tier`, `history`, `cta` | Card gradient reflects tier color; ADA-compliant text. |
| ConsignorCard | `vendor`, `balance`, `commission`, `actions` | Highlights urgent items needing action. |
| OrderTimeline | `events`, `attachments`, `actions`, `permissions` | Allows inline notes with mention support. |
| ProductEditor | `tabs`, `product`, `validationState`, `autosave` | Sticky action bar shows errors count per tab. |
| InventoryLocationBoard | `locations`, `metrics`, `filters`, `mapData` | Map component optional; list view fallback for low bandwidth. |
| ReportGallery | `reports`, `categories`, `search`, `schedule` | Each tile shows data freshness and expected runtime. |
| FeatureFlagToggleList | `flags`, `tenant`, `permissions`, `audience` | Supports staging -> production promotion workflow. |
| PlatformStoreDirectory | `stores`, `columns`, `filters`, `actions` | Virtualized table; includes impersonate button per row. |
| POSRegister | `catalog`, `cart`, `customer`, `status` | Offline mode toggles available actions automatically. |
| OfflineQueuePanel | `transactions`, `syncState`, `retry`, `export` | Entries clickable for detail view and manual correction. |

<!-- anchor: 6-2-accessibility-scorecard -->
### 6.2 Accessibility Scorecard
| Module | Primary Risks | Mitigation |
| --- | --- | --- |
| Checkout | Form complexity, payment iframe focus, address validation errors | Use semantic fields, `aria-live` for errors, Stripe's accessibility guidelines, manual QA per release. |
| POS | Touch targets, glare, offline messaging | Large buttons, high-contrast mode, offline summary readouts, voice cues optional. |
| Consignor Portal | Dense tables, document downloads | Provide responsive tables, accessible download buttons, `aria-sort` on columns. |
| Admin Reports | Charts needing textual summaries | Include data tables, `aria-describedby` with summary text, allow CSV export. |
| Platform Console | Impersonation clarity, action confirmation | Persistent banner with `role=status`, double-confirmation on destructive commands, audit log link. |
| Loyalty Panel | Color-coded tiers, animated progress | Provide textual equivalents, limit motion for reduced-motion users. |
| Command Palette | Keyboard navigation, announcements | Trap focus, describe results count, highlight matched characters. |

<!-- anchor: 6-3-responsive-behavior-index -->
### 6.3 Responsive Behavior Index
| Component | Mobile Behavior | Tablet Behavior | Desktop Behavior |
| --- | --- | --- | --- |
| ProductCardGrid | 2 columns, infinite scroll, condensed badges | 3 columns with sticky filter bar | 4-5 columns with hover quick-add |
| CheckoutShell | Steps stacked, summary collapsible accordion | Two-column layout with sticky summary | Two-column layout with persistent progress tracker |
| AdminSidebar | Hidden behind hamburger, slide-in overlay | Collapsible panel with icons + labels | Fixed 272px column with tooltips |
| OrderTable | Cards; actions in overflow menu | Table with horizontal scroll for extra columns | Full table with column chooser |
| MediaGallery | Swipe gestures, bottom thumbnail rail | Thumbnail column toggled | Grid of thumbnails plus vertical zoom panel |
| InventoryBoard | List-first view with location filter dropdown | Split view map/list | Dual-column map + list with filter rail |
| POSRegister | Vertical stacking, large buttons | Mixed layout; cart and keypad side-by-side | Landscape layout with search, grid, cart simultaneously visible |
| PlatformDashboard | Cards stacked, minimal charts | Two-column layout | Masonry grid with additional widgets |

<!-- anchor: 6-4-user-flow-steps -->
### 6.4 User Flow Step Tables
| Flow | Step | UI Notes |
| --- | --- | --- |
| Merchant Onboarding | Profile setup | Collect business name, timezone, supported vertical; show progress 1/5 |
| Merchant Onboarding | Branding | Upload logo, select colors, preview; warn if contrast fails |
| Merchant Onboarding | Product import | Provide sample CSV, show validation preview, asynchronous job status |
| Merchant Onboarding | Payments | Launch Stripe Connect onboarding, show checklist verifying completion |
| Merchant Onboarding | Shipping | Configure base address, carriers, fallback rates; highlight impact on conversion |
| Consignor Payout | Review balance | Show available vs pending; include tooltip explaining calculations |
| Consignor Payout | Request payout | Validate amount, show Stripe Connect status, require confirm checkbox |
| Consignor Payout | Admin approval | Admin sees request with vendor notes, can approve/decline with reason |
| Consignor Payout | Notification | Portal and email show payout timeline, estimated date, reference ID |
| Impersonation | Select store | Platform table row action; modal collects reason/ticket |
| Impersonation | MFA confirm | Prompt ensures secure action; inline doc link to policy |
| Impersonation | Session indicator | Banner displays context, timer, exit CTA |
| Impersonation | Action logging | Inline toast reminds that actions logged; link to audit |
| Impersonation | Exit summary | Modal summarizing actions, allow adding resolution note |

<!-- anchor: 6-5-multitenant-scenarios -->
### 6.5 Multi-Tenant Variation Scenarios
| Scenario | UI Behavior | Notes |
| --- | --- | --- |
| Holiday Theme Enabled | Storefront loads seasonal hero, accent palette shifts to holiday colors, admin shows reminder banner | Feature flag controlled per tenant with scheduled deactivation |
| Consignment Only Store | Product detail includes consignor info card, checkout messaging emphasizes consignor terms | Admin hides irrelevant features (subscriptions) to reduce clutter |
| Digital Goods Only | Shipping steps condensed, checkout hides shipping address, order confirmation links to downloads | Download badge ensures customers aware of expiration |
| High-Risk Tenant (suspended) | Storefront shows suspension page, admin access limited to billing and support, platform view highlights compliance tasks | Visual cues in platform directory to prioritize follow-up |
| Multi-location Inventory heavy | Listing and product pages show location selector, admin inventory board defaults to map view | Performance optimized via virtualization |

<!-- anchor: 6-6-notification-taxonomy -->
### 6.6 Notification Taxonomy
| Type | Tone | Delivery | Example |
| --- | --- | --- | --- |
| Alert | Danger/Warning | In-app banner + email | Carrier outage impacting rates |
| Reminder | Info | In-app notification + digest | Renew SSL certificate |
| Success | Success | Toast + optional email | Stripe payout completed |
| Nudges | Accent | In-app card | Enable loyalty to boost repeat sales |
| Compliance | Neutral/Danger | Modal + email + platform alert | Missing consignment documentation |
| Offline | Warning | POS banner + push notification | POS terminal lost connection |

<!-- anchor: 6-7-content-guidelines -->
### 6.7 Content Voice & Tone Guidelines
| Context | Voice Principles | Example Copy |
| --- | --- | --- |
| Storefront marketing | Warm, confident, merchant-specific | "Discover handmade goods curated for your lifestyle." |
| Checkout helpers | Reassuring, concise | "We'll only use this address for shipping updates." |
| Admin warnings | Direct, solution-oriented | "USPS credentials expired. Update them to keep shipping live." |
| Consignor portal | Transparent, collaborative | "You're $120 away from your next payout." |
| Platform alerts | Authoritative, actionable | "FFmpeg worker lag detected. Scale worker pool or pause uploads." |
| POS offline | Calm, instructive | "You're offline. Sales are saved and will sync once reconnected." |

<!-- anchor: 6-8-analytics-taxonomy -->
### 6.8 Analytics Event Taxonomy
| Event | Trigger | Payload | Purpose |
| --- | --- | --- | --- |
| `view_product` | Product detail load | tenantId, productId, variantCount, source | Measure engagement per merchandising slot |
| `add_to_cart` | Add action via ProductCard or ProductDetail | cartId, productId, qty, price, channel | Track conversion funnels |
| `checkout_step_completed` | Step submission success | stepName, durationMs, errorCount | Identify friction points |
| `checkout_error` | API or validation failure | stepName, errorCode, severity | Alert support, monitor reliability |
| `loyalty_redeem` | Points applied | customerId, pointsUsed, balanceAfter | Understand loyalty ROI |
| `consignor_payout_requested` | Vendor action | consignorId, amount, balanceRemaining | Monitor vendor engagement |
| `impersonation_started` | Platform action | actorId, tenantId, reasonCode | Audit support interventions |
| `impersonation_action` | Each API call during impersonation | actionType, targetId | Provide compliance visibility |
| `pos_offline_sale` | Offline checkout | storeId, amount, items, deviceId | Track offline operations |
| `feature_flag_toggled` | Admin change | flagKey, newState, actor | Manage rollout risk |

<!-- anchor: 6-9-ui-metrics -->
### 6.9 UI Metrics & KPIs
- **Cart Abandonment Rate**: tracked via `checkout_step_completed` events; UI displays drop-off by step.
- **Inventory Adjustment Latency**: measures time between physical count entry and UI reflection; ensures real-time sense for staff.
- **Consignor Portal Engagement**: monitors login frequency, payout requests, statement downloads.
- **Platform Support Efficiency**: number of impersonation sessions resolved per day; UI surfaces benchmark vs SLA.
- **POS Offline Duration**: average offline session length; UI highlight stores exceeding thresholds.

<!-- anchor: 6-10-glossary -->
### 6.10 Glossary of UI Terms
- **Tenant Theme Bundle**: JSON payload containing color, typography, spacing overrides for a store; consumed by Tailwind build.
- **Context Ribbon**: thin bar above header providing status info (impersonation, offline, scheduled maintenance).
- **Action Shelf**: sticky bar at bottom/top of edit pages containing primary/secondary buttons and validation summary.
- **Smart Empty State**: placeholder that uses telemetry or configuration to propose next steps (e.g., import data, connect Stripe).
- **Insight Tile**: mini card showing metric, sparkline, context pill; used extensively on dashboards.
- **Quick Peek Modal**: lightweight detail view triggered from lists; supports inline editing for simple attributes.

<!-- anchor: 6-11-persona-guidelines -->
### 6.11 Persona-Specific UX Guidelines
- **Store Owner**: prioritize cross-surface continuity between storefront and admin; highlight KPIs, show warnings about compliance.
- **Staff**: streamline operations tasks; minimize access to sensitive settings; emphasize workflow cues (fulfillment, POS, inventory).
- **Consignor**: provide clarity around financial status, highlight trust; keep UI minimal with focus on balances and item status.
- **Platform Super Admin**: emphasize situational awareness with consistent color-coded statuses and quick drill-down to stores.
- **Customer**: maintain frictionless discovery-to-checkout journey with high trust signals, transparent policies, and responsive design.

<!-- anchor: 6-12-future-enhancements -->
### 6.12 Future Enhancement Slots
- Reserve header slot for personalization modules (e.g., AI recommendations future) without disrupting layout.
- Provide placeholder route for `/account/subscriptions` expansion; UI currently shows “Coming soon” with ability to register interest.
- Build scaffolding for multilingual toggle; UI includes icon hidden behind feature flag.
- Headless API docs page includes stub sections for GraphQL or webhooks to prevent documentation restructure later.

<!-- anchor: 6-13-device-matrix -->
### 6.13 Device Testing Matrix
| Device | OS | Browser/App | Key Scenarios |
| --- | --- | --- | --- |
| iPhone 14 | iOS 17 | Safari | Storefront browsing, checkout, account management |
| Pixel 7 | Android 14 | Chrome | Storefront, PWA behavior, push notifications |
| iPad Air | iPadOS 17 | Safari app mode | POS register, admin quick edits |
| Surface Pro | Windows 11 | Edge | Admin dashboard, reporting, consignment workflows |
| MacBook Pro | macOS Sonoma | Chrome/Safari | Admin, platform console, Storybook review |
| Zebra Tablet | Android | WebView App | POS offline, barcode scanning integration |
| Galaxy Tab | Android | Chrome | Consignor portal, training scenarios |

<!-- anchor: 6-14-error-message-patterns -->
### 6.14 Error Message Patterns
| Context | Message Structure | Example |
| --- | --- | --- |
| Form validation | `{Issue}. {Guidance}.` | "SKU is required. Enter up to 32 characters." |
| API failure | `{Action} couldn't complete because {reason}. {Next step}.` | "Saving product couldn't complete because Stripe is offline. Try again in a few minutes or contact support." |
| Permission denied | `You need {role}. {Link to request access}.` | "You need Owner role to configure Stripe. Request access from an owner." |
| Rate limit | `Too many {action}. {Wait time}.` | "Too many login attempts. Try again in 2 minutes." |
| Offline | `{Feature} unavailable offline. {Alternative}.` | "Shipping rate lookup unavailable offline. Use manual rate." |

<!-- anchor: 6-15-keyboard-shortcuts -->
### 6.15 Keyboard Shortcut Catalog
| Shortcut | Context | Action |
| --- | --- | --- |
| `⌘K` / `Ctrl+K` | Admin/Platform | Open Command Palette |
| `?` | Admin/Platform | Show keyboard shortcut reference modal |
| `Shift + /` | Storefront account | Focus search input |
| `⌘S` / `Ctrl+S` | Admin forms | Trigger save draft (debounced) |
| `Shift + L` | Admin dashboard | Toggle dark/high-contrast preview |
| `Shift + P` | POS | Pause offline queue processing |
| `Ctrl + Shift + F` | Admin tables | Open FilterDrawer |
| `Shift + R` | Platform console | Refresh store health metrics |
| `Alt + Arrow` | Carousels | Navigate slides |

<!-- anchor: 6-16-setup-wizard -->
### 6.16 Setup Wizard Module Checklist
- Module 1: Store identity (logo, colors, tagline) with live preview.
- Module 2: Catalog seed (sample products or CSV import) with success metrics.
- Module 3: Payments (Stripe Connect) with status card and help links.
- Module 4: Shipping & tax basics with recommended carriers and fallback.
- Module 5: Consignment enablement where applicable, including vendor onboarding.
- Module 6: Launch checklist summarizing outstanding tasks; shareable PDF for stakeholders.

<!-- anchor: 6-17-session-management -->
### 6.17 Session Management UI Details
- Session list displays device name, browser, approximate location, last activity, and indicator if session is impersonated.
- Each entry has actions: “Revoke”, “View Activity”, “Report Suspicious”.
- Customers see educational copy explaining why multiple sessions may appear and how to secure account.
- Admins can filter sessions by role, device type, or activity threshold; results exportable for compliance.
- Notifications triggered when new location detected; includes CTA to review sessions.

<!-- anchor: 6-18-search-tuning -->
### 6.18 Search Relevance & Merchandising Controls
- Merchants adjust boost rules (e.g., promote collections, demote out-of-stock) via slider-based UI with preview search results.
- Synonym management UI allows adding term pairs with context (global vs category-specific).
- Zero-result fallback builder lets merchants configure fallback collections or contact prompts.
- Analytics panel shows top search queries, conversion, and zero-result counts to guide adjustments.

<!-- anchor: 6-19-reporting-ui -->
### 6.19 Reporting UX Details
- ReportGallery tiles list runtime, size estimate, and permissions so staff knows what to expect.
- Scheduling form includes timezone selection, recurrence, recipients, and data retention rules.
- Generated reports display freshness timestamp, filters summary, and download/explore actions.
- Chart panels include annotation controls so merchants can tag campaigns or events.

<!-- anchor: 6-20-support-experience -->
### 6.20 Support & Help Surface
- Admin header contains help icon -> opens panel with search, suggested articles, contact options.
- Platform support queue integrates with impersonation; each ticket card shows severity, SLA timer, and quick action buttons.
- Customer self-service pages include contextual FAQs; selection updates answer area without leaving page.
- On error pages, provide status link and, when appropriate, chat widget connection.

<!-- anchor: 6-21-hardware-pairing -->
### 6.21 Hardware Pairing Flow
- Setup wizard guides through powering device, connecting to network, entering pairing code.
- UI includes troubleshooting accordion with steps for connection issues.
- After pairing, device card shows firmware version, last heartbeat, and location assignment.
- Firmware updates triggered via UI, showing progress bar and fail-safe instructions.

<!-- anchor: 6-22-media-pipeline-status -->
### 6.22 Media Pipeline Status Indicators
| Status | Color | Description | UI Location |
| --- | --- | --- | --- |
| Uploaded | Neutral | File stored, awaiting processing | Media library list |
| Processing | Info | Variant generation or ffmpeg job running | Media cards show spinner + ETA |
| Completed | Success | All variants ready | Thumbnails selectable, CTA to copy URL |
| Failed | Danger | Processing error, includes log link | Inline alert with retry button |
| Archived | Neutral-muted | Asset moved to cold storage | Badge indicates retrieval time |
| Purged | Danger-muted | Asset removed after retention | Disabled card with info tooltip |

<!-- anchor: 6-23-shipping-integration-ui -->
### 6.23 Shipping & Fulfillment UI Patterns
- Rate comparison table shows carriers, services, rates, delivery estimate, and badges for negotiated rates.
- Label generation modal previews label, allows thermal or PDF selection, and logs cost before confirming.
- Shipment tracking widget displays timeline with statuses pulled via webhook; includes manual update option.
- Split shipment builder allows dragging items into packages, ensures weight/dimension sum displayed.

<!-- anchor: 6-24-loyalty-ui -->
### 6.24 Loyalty & Rewards UI Details
- Tier cards show benefits, multipliers, expiration rules; customers see progress ring with `pointsToNext`.
- Admin loyalty configurator includes slider for earning rate, redemption value, expiration; UI calculates projected liability.
- Redeem modal surfaces recommended redemption values and warns about minimum purchase rules.
- Loyalty ledger table filters by order, adjustment reason, staff actor; exportable for compliance.

<!-- anchor: 6-25-gift-card-ui -->
### 6.25 Gift Card & Store Credit UX
- Checkout payment section lists gift card balance first; UI ensures partial redemption with residual balance shown.
- Admin gift card detail page displays activity timeline, status (active, redeemed, expired), and reissue controls.
- Store credit ledger accessible in customer profile; staff can add notes for manual adjustments.
- Customer account includes gift card balance checker and ability to add cards to account for future auto-suggest.

<!-- anchor: 6-26-returns-ui -->
### 6.26 Returns & Refunds UX
- Customer portal shows order items with return eligibility badges; selecting item opens reason picker and instructions.
- Admin return authorization screen shows policy summary, restocking fee calculator, and recommended action steps.
- UI enforces capturing restock decision and inventory adjustment automatically upon completion.
- Notifications inform customers when return received and refund processed; includes expectation for refund timeline.

<!-- anchor: 6-27-audit-log-ui -->
### 6.27 Audit Log UX Details
- Timeline entries include actor avatar, role, impersonation badge, and diff summary.
- Filter chips allow narrowing by entity type, action type, date range, impersonation context.
- Export function warns about sensitive data and enforces RBAC check.
- Inline search supports queries by ID or keyword; results highlight matches.

<!-- anchor: 6-28-release-controls -->
### 6.28 Release Controls & Messaging
- Feature release center shows upcoming releases, impacted modules, and readiness checklist.
- Tenants can opt in/out via toggle with explanation, screenshot, and risk notes.
- Release notes accessible within admin; highlights new UI patterns and includes “Give feedback” link.
- Emergency rollback instructions surfaced within release center, referencing feature flags and contact.

<!-- anchor: 6-29-notification-preferences -->
### 6.29 Notification Preference Center
- Customers manage email/SMS toggles categorized by transactional, marketing, loyalty; each includes description and regulatory note.
- Admin staff configure alert subscriptions (inventory, payouts, jobs) with severity thresholds.
- Platform SREs assign incident channels (Slack, email) within UI tied to Ops on-call rotation.
- Preference changes logged for compliance with timestamp and actor.

<!-- anchor: 6-30-headless-docs -->
### 6.30 Headless API Documentation UX
- Interactive docs embed OpenAPI-generated try-it console; respects tenant auth tokens.
- Code samples offered in cURL, JavaScript, Python; syntax highlighting matches brand palette.
- Rate limit info shown per endpoint with tooltips describing bucket sizes.
- Webhook guides include sequence diagrams and sample payload diff to highlight required fields.
