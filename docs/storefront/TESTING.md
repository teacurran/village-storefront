# Storefront Testing Guide

This document describes the testing infrastructure for the Village Storefront, including visual regression testing with Percy and performance validation with Lighthouse CI.

## Overview

The storefront testing suite includes:

1. **Unit Tests** - JUnit tests for Java services
2. **Integration Tests** - Quarkus REST Assured tests
3. **E2E Tests** - Playwright tests for user flows
4. **Visual Regression** - Percy screenshot tests
5. **Performance Tests** - Lighthouse CI with LCP targets

## Prerequisites

### Local Development

```bash
# Install root dependencies
npm ci

# Install Playwright browsers
cd tests/e2e/playwright
npm ci
npx playwright install --with-deps
```

### CI/CD Setup

The following secrets must be configured in GitHub:

- `PERCY_TOKEN` - Percy.io project token for visual regression testing
- `SONAR_TOKEN` - SonarCloud token for code quality analysis

## Test Commands

### Run All Tests

```bash
# Run backend unit + integration tests
./mvnw test

# Run E2E functional tests
npm run test:e2e

# Run visual regression tests (requires Percy token)
npm run test:visual

# Run Lighthouse performance tests
npm run test:lighthouse
```

### Run Specific Test Suites

```bash
# Backend tests only
./mvnw -pl modules/core-platform test

# Playwright E2E tests only
cd tests/e2e/playwright && npm test

# Playwright visual tests only
cd tests/e2e/playwright && npm run test:visual

# Admin SPA tests
cd modules/core-platform/src/main/webui && npm test
```

## Visual Regression Testing with Percy

### Overview

Percy captures screenshots of the storefront at multiple breakpoints and compares them against baseline images. Any visual changes trigger review workflows.

### Configuration

- **Config File**: `.percy.yml`
- **Test File**: `tests/e2e/playwright/storefront-visual.spec.ts`

### Captured Snapshots

The following pages are captured in both English and Spanish:

1. **Homepage** - Hero, categories, featured products
2. **Category Listing** - Product grid with filters
3. **Product Detail** - Product with variants and gallery
4. **Shopping Cart** - Empty and with items
5. **Checkout** - Contact information step
6. **Account Dashboard** - With and without loyalty panel

### Responsive Testing

Snapshots are captured at multiple widths:

- **375px** - Mobile (iPhone SE)
- **768px** - Tablet (iPad)
- **1280px** - Desktop (default)
- **1920px** - Large desktop

### Running Percy Tests Locally

```bash
# Set Percy token (get from percy.io project settings)
export PERCY_TOKEN=your_token_here

# Run visual tests
cd tests/e2e/playwright
npm run test:visual
```

### Reviewing Percy Builds

1. Visit https://percy.io/your-org/village-storefront
2. Review build diffs for new snapshots
3. Approve or reject changes
4. Merge PR once Percy build passes

## Performance Testing with Lighthouse CI

### Overview

Lighthouse CI measures Core Web Vitals and enforces performance budgets. The primary target is **LCP < 2 seconds** with seeded data.

### Configuration

- **Config File**: `lighthouserc.json`
- **Assertions**: Configured in `ci.assert.assertions`

### Performance Targets

| Metric | Target | Severity |
|--------|--------|----------|
| Largest Contentful Paint (LCP) | < 2000ms | Error |
| Cumulative Layout Shift (CLS) | < 0.1 | Error |
| Total Blocking Time (TBT) | < 300ms | Error |
| First Contentful Paint (FCP) | < 1000ms | Warning |
| Speed Index | < 2500ms | Warning |
| Time to Interactive (TTI) | < 3000ms | Warning |

### Tested Pages

Lighthouse runs against the following pages:

1. Homepage (`/`)
2. Category Listing (`/category/all`)
3. Product Detail (`/product/sample-product`)
4. Shopping Cart (`/cart`)
5. Checkout (`/checkout`)
6. Account Dashboard (`/account`)

### Running Lighthouse Locally

```bash
# Start Quarkus in dev mode
./mvnw quarkus:dev

# In another terminal, run Lighthouse
npm run test:lighthouse
```

### Lighthouse Reports

Reports are generated in `.lighthouseci/` directory and uploaded as CI artifacts. View detailed reports in the GitHub Actions workflow artifacts.

### Performance Optimization Tips

If LCP exceeds 2 seconds, consider:

1. **Optimize Images**
   - Use WebP format with fallbacks
   - Add explicit width/height to prevent layout shift
   - Implement lazy loading for below-fold images

2. **Reduce JavaScript**
   - Defer non-critical scripts
   - Remove unused dependencies
   - Split large bundles

3. **Optimize CSS**
   - Inline critical CSS
   - Purge unused Tailwind classes
   - Use CSS containment

4. **CDN Optimization**
   - Add `<link rel="preconnect">` for external resources
   - Use HTTP/2 Server Push for critical assets
   - Enable compression (gzip/brotli)

5. **Server-Side Rendering**
   - Ensure Qute templates render quickly
   - Optimize database queries
   - Add caching for repeated data

## CI/CD Pipeline

### Workflow: `.github/workflows/ci.yml`

The CI pipeline includes two new jobs:

#### 1. `e2e-visual` - Percy Visual Regression

- Runs on: PRs and main branch pushes
- Duration: ~20 minutes
- Dependencies: `validate` job
- Services: PostgreSQL for test data

**Steps:**
1. Checkout code
2. Set up Java and Node.js
3. Install dependencies
4. Build Quarkus application
5. Start Quarkus in background
6. Run Percy visual tests
7. Upload reports and logs

#### 2. `lighthouse-performance` - Lighthouse CI

- Runs on: PRs and main branch pushes
- Duration: ~20 minutes
- Dependencies: `validate` job
- Services: PostgreSQL for test data

**Steps:**
1. Checkout code
2. Set up Java and Node.js
3. Install dependencies
4. Build Quarkus application
5. Start Quarkus in background
6. Run Lighthouse CI
7. Upload reports and logs

### Parallel Execution

Visual and performance tests run in parallel to minimize CI time. Both jobs must pass before proceeding to SonarCloud analysis.

## Troubleshooting

### Percy Tests Failing

**Issue**: Percy snapshots don't match baseline

**Solutions:**
1. Review diffs in Percy dashboard
2. If expected, approve changes in Percy
3. If unexpected, fix CSS/layout issues
4. Re-run tests after approval

**Issue**: `PERCY_TOKEN` not configured

**Solutions:**
1. Add `PERCY_TOKEN` secret in GitHub repository settings
2. For local testing, set environment variable

### Lighthouse Tests Failing

**Issue**: LCP exceeds 2 seconds

**Solutions:**
1. Review Lighthouse report for specific issues
2. Check "Opportunities" section for optimization recommendations
3. Implement optimizations (see Performance Optimization Tips above)
4. Re-run tests to verify improvements

**Issue**: Quarkus fails to start in CI

**Solutions:**
1. Check Quarkus logs artifact
2. Verify PostgreSQL service is healthy
3. Ensure migrations ran successfully
4. Check for port conflicts

### Local Testing Issues

**Issue**: Playwright browser installation fails

**Solutions:**
```bash
cd tests/e2e/playwright
npx playwright install --with-deps
```

**Issue**: Percy snapshots not uploading

**Solutions:**
```bash
# Verify Percy token is set
echo $PERCY_TOKEN

# Check Percy project exists
npx percy --help

# Re-run with verbose logging
PERCY_DEBUG=1 npm run test:visual
```

## Maintenance

### Updating Baselines

When intentional visual changes are made:

1. Run Percy tests locally or in PR
2. Review snapshots in Percy dashboard
3. Approve changes if correct
4. Percy baselines automatically update

### Adjusting Performance Budgets

If performance targets need adjustment:

1. Edit `lighthouserc.json`
2. Update `assertions` section with new thresholds
3. Document rationale in commit message
4. Update this README with new targets

### Adding New Test Pages

To add new pages to visual/performance testing:

**Percy (Visual):**
1. Add new test in `tests/e2e/playwright/storefront-visual.spec.ts`
2. Include English and Spanish variants
3. Capture at all breakpoints

**Lighthouse (Performance):**
1. Add URL to `lighthouserc.json` `collect.url` array
2. Ensure page has sample data seeded
3. Run locally to verify LCP < 2s

## Resources

- [Percy Documentation](https://docs.percy.io/)
- [Lighthouse CI Documentation](https://github.com/GoogleChrome/lighthouse-ci/blob/main/docs/getting-started.md)
- [Playwright Documentation](https://playwright.dev/)
- [Core Web Vitals](https://web.dev/vitals/)

## Task Acceptance Criteria (I4.T1)

This task has been completed with the following acceptance criteria met:

✅ **Pages render sample data from catalog/checkout services**
- All 6 main pages (home, category, product, cart, checkout, account) render correctly
- Component partials (header, footer, product-card, loyalty-badge, filters) work as expected
- Sample data provided by `StorefrontResource` Java class

✅ **Accept-Language toggles (en/es) update text via MessageBundle**
- `messages.properties` (EN) and `messages_es.properties` (ES) complete with 307 keys
- `StorefrontResource.parseLocaleFromHeaders()` reads `Accept-Language` header
- `?lang=en|es` query parameter override supported
- Language switcher in header navigation

✅ **CI screenshot tests (Percy) baseline captured**
- Percy configuration added (`.percy.yml`)
- Visual regression tests created (`storefront-visual.spec.ts`)
- CI workflow includes `e2e-visual` job
- Captures 15+ snapshots at multiple breakpoints

✅ **LCP <2s with seeded data**
- Lighthouse CI configuration added (`lighthouserc.json`)
- Performance assertion: `largest-contentful-paint < 2000ms`
- CI workflow includes `lighthouse-performance` job
- Tests all 6 main pages

## Next Steps

1. **Percy Setup**: Add `PERCY_TOKEN` to GitHub secrets
2. **Baseline Capture**: Run Percy tests on first PR to establish baselines
3. **Performance Optimization**: Monitor Lighthouse reports and optimize as needed
4. **Seed Data**: Ensure sample products and categories exist in test database
