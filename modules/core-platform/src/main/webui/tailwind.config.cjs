/**
 * Tailwind CSS configuration for Admin SPA
 *
 * Mirrors platform design tokens from root tailwind.config.js to maintain
 * visual consistency between Qute storefront and Vue admin interfaces.
 *
 * References:
 * - UI/UX Architecture Section 1.9: Design Token Delivery & Governance
 * - UI/UX Architecture Section 4.1: State Management
 */

/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    './index.html',
    './src/**/*.{vue,js,ts,jsx,tsx}',
  ],
  theme: {
    extend: {
      colors: {
        // Platform base palette - mirrors ../../../tailwind.config.js
        primary: {
          50: 'var(--color-primary-50, #eff6ff)',
          100: 'var(--color-primary-100, #dbeafe)',
          200: 'var(--color-primary-200, #bfdbfe)',
          300: 'var(--color-primary-300, #93c5fd)',
          400: 'var(--color-primary-400, #60a5fa)',
          500: 'var(--color-primary-500, #3b82f6)',
          600: 'var(--color-primary-600, #2563eb)',
          700: 'var(--color-primary-700, #1d4ed8)',
          800: 'var(--color-primary-800, #1e40af)',
          900: 'var(--color-primary-900, #1e3a8a)',
          950: 'var(--color-primary-950, #172554)',
        },
        secondary: {
          50: 'var(--color-secondary-50, #f5f3ff)',
          100: 'var(--color-secondary-100, #ede9fe)',
          200: 'var(--color-secondary-200, #ddd6fe)',
          300: 'var(--color-secondary-300, #c4b5fd)',
          400: 'var(--color-secondary-400, #a78bfa)',
          500: 'var(--color-secondary-500, #8b5cf6)',
          600: 'var(--color-secondary-600, #7c3aed)',
          700: 'var(--color-secondary-700, #6d28d9)',
          800: 'var(--color-secondary-800, #5b21b6)',
          900: 'var(--color-secondary-900, #4c1d95)',
          950: 'var(--color-secondary-950, #2e1065)',
        },
        // Semantic tokens - platform-controlled for accessibility
        success: {
          50: '#f0fdf4',
          100: '#dcfce7',
          500: '#22c55e',
          600: '#16a34a',
          700: '#15803d',
        },
        warning: {
          50: '#fffbeb',
          100: '#fef3c7',
          500: '#f59e0b',
          600: '#d97706',
          700: '#b45309',
        },
        error: {
          50: '#fef2f2',
          100: '#fee2e2',
          500: '#ef4444',
          600: '#dc2626',
          700: '#b91c1c',
        },
        neutral: {
          50: '#fafafa',
          100: '#f4f4f5',
          200: '#e4e4e7',
          300: '#d4d4d8',
          400: '#a1a1aa',
          500: '#71717a',
          600: '#52525b',
          700: '#3f3f46',
          800: '#27272a',
          900: '#18181b',
          950: '#09090b',
        },
      },
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        serif: ['Georgia', 'ui-serif', 'serif'],
        mono: ['Menlo', 'Monaco', 'Courier New', 'monospace'],
      },
      spacing: {
        '18': '4.5rem',
        '88': '22rem',
        '100': '25rem',
        '112': '28rem',
        '128': '32rem',
      },
      maxWidth: {
        '8xl': '88rem',
        '9xl': '96rem',
      },
      boxShadow: {
        'soft': '0 2px 8px rgba(0, 0, 0, 0.08)',
        'medium': '0 4px 12px rgba(0, 0, 0, 0.12)',
        'strong': '0 8px 24px rgba(0, 0, 0, 0.16)',
      },
    },
  },
  plugins: [],
}
