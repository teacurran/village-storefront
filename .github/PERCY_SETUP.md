# Percy Visual Regression Setup Guide

This guide explains how to set up Percy.io for visual regression testing in the Village Storefront project.

## Prerequisites

- GitHub repository admin access
- Percy.io account (free tier available)

## Step 1: Create Percy Project

1. Visit [percy.io](https://percy.io) and sign up or log in
2. Create a new project:
   - Name: `village-storefront`
   - Integration: GitHub
   - Framework: Playwright
3. Note your project token (shown on project settings page)

## Step 2: Add Percy Token to GitHub

1. Go to GitHub repository settings
2. Navigate to: **Settings** > **Secrets and variables** > **Actions**
3. Click **New repository secret**
4. Add secret:
   - **Name**: `PERCY_TOKEN`
   - **Value**: `<your-percy-token>`
5. Click **Add secret**

## Step 3: Verify CI Integration

1. Create a test PR or push to main branch
2. Check GitHub Actions workflow
3. Verify `e2e-visual` job runs successfully
4. Check Percy dashboard for new build

## Step 4: Review and Approve Baselines

1. Visit Percy dashboard: https://percy.io/your-org/village-storefront
2. Review first build with 15+ snapshots
3. Approve all snapshots to set baselines
4. Future PRs will compare against these baselines

## Percy Dashboard

### Build Status

- **✅ Approved**: All snapshots match baseline (no changes detected)
- **⏳ Pending**: New snapshots need review
- **❌ Failed**: Visual changes detected, review required

### Reviewing Changes

1. Click on build in Percy dashboard
2. Review each snapshot with visual diffs
3. Approve changes if expected, or reject if bugs
4. Add comments to specific snapshots for team communication

### Baseline Management

- **Approved builds** automatically update baselines
- **Rejected builds** do not update baselines
- Baselines are branch-specific (main branch = production baseline)

## Local Testing

To run Percy tests locally:

```bash
# Set Percy token
export PERCY_TOKEN=your_token_here

# Install dependencies
cd tests/e2e/playwright
npm ci
npx playwright install --with-deps chromium

# Start Quarkus (in another terminal)
cd ../../..
./mvnw quarkus:dev

# Run Percy tests
cd tests/e2e/playwright
npm run test:visual
```

## Troubleshooting

### Error: "PERCY_TOKEN not set"

**Solution**: Export token in environment:
```bash
export PERCY_TOKEN=your_token_here
```

### Error: "Percy build failed"

**Solution**: Check Percy dashboard for specific error message. Common issues:
- Network timeout (retry build)
- Invalid token (verify token in GitHub secrets)
- Snapshot width/height invalid (check `.percy.yml`)

### Snapshots don't match

**Solution**: Review diffs in Percy dashboard:
1. If expected changes, approve in Percy
2. If unexpected, fix CSS/layout and re-run tests

### CI job fails with Percy error

**Solution**: Check CI logs:
1. Verify Quarkus started successfully
2. Check Playwright browser installation
3. Verify Percy token is set in GitHub secrets

## Percy Configuration

### `.percy.yml`

```yaml
version: 2
snapshot:
  widths: [375, 768, 1280, 1920]  # Responsive breakpoints
  min-height: 1024
  enable-javascript: true
  percy-css: |
    /* Hide dynamic content */
    [data-percy-hide] { display: none !important; }
```

### Test File

`tests/e2e/playwright/storefront-visual.spec.ts` - Visual regression test suite

## Snapshot Coverage

The following pages are captured in English and Spanish:

1. Homepage - Hero, categories, featured products
2. Category Listing - Product grid with filters
3. Product Detail - Product with variants
4. Shopping Cart - Empty and with items
5. Checkout - Contact information step
6. Account Dashboard - With/without loyalty panel

Plus component-specific tests:
- Header navigation (desktop + mobile)
- Product card grid
- Mini cart dropdown
- Footer

## Best Practices

### When to Approve Changes

✅ **Approve** when:
- Intentional design changes
- Expected layout updates
- New features added

### When to Reject Changes

❌ **Reject** when:
- Unintentional visual bugs
- Broken layouts
- Missing content
- Wrong colors/fonts

### Handling False Positives

If dynamic content causes false positives:

1. Add `data-percy-hide` attribute to element:
   ```html
   <span data-percy-hide>Dynamic timestamp</span>
   ```

2. Or update `.percy.yml` percy-css to hide:
   ```css
   [data-timestamp] { opacity: 0 !important; }
   ```

## Resources

- [Percy Documentation](https://docs.percy.io/)
- [Percy Playwright Integration](https://docs.percy.io/docs/playwright)
- [Percy Best Practices](https://docs.percy.io/docs/best-practices)

## Support

For issues with Percy setup:

1. Check Percy dashboard for error messages
2. Review CI logs in GitHub Actions
3. Consult `docs/storefront/TESTING.md` for troubleshooting
4. Contact DevOps team if token issues persist

---

**Last Updated**: 2026-01-09
**Related Task**: I4.T1
