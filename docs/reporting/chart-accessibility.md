# Chart Accessibility Guide

## Overview

This document outlines the accessibility standards and testing procedures for Chart.js visualizations in the Village Storefront reporting dashboard.

## WCAG 2.1 AA Compliance Checklist

### Visual Requirements

- [ ] **Sufficient Color Contrast**: All chart elements meet minimum 4.5:1 contrast ratio
- [ ] **Non-Color Indicators**: Information is not conveyed by color alone (use patterns, labels, text)
- [ ] **Text Alternatives**: Charts include text descriptions for screen readers
- [ ] **Responsive Design**: Charts scale appropriately on mobile, tablet, and desktop

### Keyboard Navigation

- [ ] **Tab Navigation**: All interactive chart elements are reachable via Tab key
- [ ] **Focus Indicators**: Visible focus outline when navigating charts with keyboard
- [ ] **Arrow Key Support**: Use arrow keys to cycle through data points (when applicable)
- [ ] **Escape Key**: Close tooltips/overlays with Escape key

### Screen Reader Support

- [ ] **ARIA Labels**: All chart canvas elements have descriptive `aria-label` attributes
- [ ] **Role Attributes**: Charts marked with `role="img"` for screen readers
- [ ] **Data Tables**: Alternative data table provided (hidden visually, available to screen readers)
- [ ] **Announcements**: Dynamic chart updates announced to screen readers

## Implementation Guidelines

### Chart.js ARIA Attributes

All Chart.js components must include:

```vue
<canvas
  aria-label="Descriptive label explaining the chart data and purpose"
  role="img"
  tabindex="0"
/>
```

**Examples:**
- `aria-label="Sales trend chart showing revenue from January 1 to January 10, 2026"`
- `aria-label="Inventory aging chart displaying slowest moving items by days in stock"`

### Color Palette

Use high-contrast color schemes:

```javascript
const colors = {
  primary: '#3b82f6',     // Blue - revenue, positive trends
  success: '#10b981',     // Green - completed, healthy
  warning: '#f59e0b',     // Amber - pending, caution
  danger: '#ef4444',      // Red - failed, slow movers
  neutral: '#6b7280',     // Gray - neutral data
}
```

### Keyboard Navigation Pattern

```vue
<template>
  <canvas
    ref="chartCanvas"
    @keydown="handleKeyDown"
    tabindex="0"
  />
</template>

<script setup lang="ts">
function handleKeyDown(event: KeyboardEvent) {
  switch (event.key) {
    case 'ArrowRight':
      // Navigate to next data point
      break
    case 'ArrowLeft':
      // Navigate to previous data point
      break
    case 'Escape':
      // Close tooltip
      break
  }
}
</script>
```

### Alternative Data Table

Provide hidden data table for screen readers:

```vue
<div class="sr-only" aria-label="Sales data table">
  <table>
    <thead>
      <tr>
        <th>Period</th>
        <th>Revenue</th>
        <th>Orders</th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="item in salesData" :key="item.id">
        <td>{{ formatDate(item.periodStart) }}</td>
        <td>{{ formatCurrency(item.totalAmount) }}</td>
        <td>{{ item.orderCount }}</td>
      </tr>
    </tbody>
  </table>
</div>
```

## Testing Procedures

### Manual Testing Checklist

#### Keyboard Navigation Test

1. Open reporting dashboard in browser
2. Press Tab key to navigate to chart
3. Verify visible focus outline appears
4. Press Arrow keys to navigate data points (if applicable)
5. Verify tooltip opens/closes with keyboard

**Expected Result**: All chart interactions work without mouse

#### Screen Reader Test

**Tools**: NVDA (Windows), JAWS (Windows), VoiceOver (macOS)

1. Enable screen reader
2. Navigate to reporting dashboard
3. Tab to chart element
4. Verify screen reader announces:
   - Chart description from `aria-label`
   - Chart type (e.g., "graphic, line chart")
   - Data values when focused

**Expected Result**: Screen reader provides meaningful context about chart

#### Color Contrast Test

**Tools**: Chrome DevTools Lighthouse, WAVE Browser Extension

1. Run Lighthouse accessibility audit
2. Check "Elements with insufficient color contrast" report
3. Verify all chart text/lines meet 4.5:1 ratio

**Expected Result**: No contrast errors in Lighthouse report

#### Mobile Responsiveness Test

1. Open dashboard on mobile device (or Chrome DevTools device emulation)
2. Verify charts scale to fit screen width
3. Check touch interactions (tap to show tooltip)
4. Verify labels remain readable

**Expected Result**: Charts usable on small screens without horizontal scroll

### Automated Testing

#### Vitest Unit Tests

```typescript
it('renders chart with accessibility attributes', async () => {
  const wrapper = mount(SalesLineChart, {
    props: { salesData: mockSalesData }
  })

  const canvas = wrapper.find('canvas')
  expect(canvas.attributes('aria-label')).toBeTruthy()
  expect(canvas.attributes('role')).toBe('img')
  expect(canvas.attributes('tabindex')).toBe('0')
})
```

#### Visual Regression Testing (Percy)

```javascript
// cypress/e2e/reporting-accessibility.cy.ts
it('maintains chart accessibility on visual changes', () => {
  cy.visit('/admin/reports')
  cy.injectAxe()
  cy.checkA11y('.chart-container', {
    rules: {
      'color-contrast': { enabled: true },
      'aria-allowed-attr': { enabled: true },
      'aria-required-attr': { enabled: true },
    }
  })
  cy.percySnapshot('Reporting Dashboard - Charts')
})
```

## Common Accessibility Issues & Fixes

### Issue 1: Missing ARIA Labels

❌ **Problem**: Chart canvas has no aria-label
```vue
<canvas />
```

✅ **Fix**: Add descriptive aria-label
```vue
<canvas aria-label="Sales trend chart showing revenue over time" role="img" />
```

### Issue 2: Low Color Contrast

❌ **Problem**: Gray text on light background (2.3:1 ratio)
```javascript
const chartOptions = {
  plugins: {
    legend: {
      labels: { color: '#d1d5db' } // Too light
    }
  }
}
```

✅ **Fix**: Use darker color (4.5:1+ ratio)
```javascript
const chartOptions = {
  plugins: {
    legend: {
      labels: { color: '#374151' } // Sufficient contrast
    }
  }
}
```

### Issue 3: No Keyboard Focus Indicator

❌ **Problem**: Chart canvas not focusable
```vue
<canvas />
```

✅ **Fix**: Add tabindex and focus styles
```vue
<canvas tabindex="0" />

<style>
canvas:focus {
  outline: 2px solid #3b82f6;
  outline-offset: 2px;
}
</style>
```

### Issue 4: Information Conveyed by Color Alone

❌ **Problem**: Red/green chart lines without labels
```javascript
datasets: [
  { label: '', data: revenue, borderColor: 'red' },
  { label: '', data: costs, borderColor: 'green' }
]
```

✅ **Fix**: Add legend with text labels
```javascript
datasets: [
  { label: 'Revenue', data: revenue, borderColor: '#ef4444' },
  { label: 'Costs', data: costs, borderColor: '#10b981' }
]
```

## Resources

- [WCAG 2.1 Guidelines](https://www.w3.org/WAI/WCAG21/quickref/)
- [Chart.js Accessibility Plugin](https://www.chartjs.org/docs/latest/configuration/accessibility.html)
- [WebAIM Contrast Checker](https://webaim.org/resources/contrastchecker/)
- [NVDA Screen Reader](https://www.nvaccess.org/download/)
- [axe DevTools](https://www.deque.com/axe/devtools/)

## Acceptance Criteria

All reporting charts must meet:

✅ WCAG 2.1 AA compliance (minimum 4.5:1 contrast)
✅ Keyboard navigable (Tab, Arrow keys)
✅ Screen reader compatible (ARIA labels, role attributes)
✅ Mobile responsive (scales to 320px width)
✅ Automated tests pass (Vitest, Cypress, Percy)
✅ Manual accessibility audit (Lighthouse score >90)

## Maintenance

- Review chart accessibility quarterly
- Update ARIA labels when chart data structure changes
- Test with new Chart.js versions for accessibility regressions
- Monitor user feedback for accessibility issues
