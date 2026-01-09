/**
 * Tailwind CSS configuration for Village Storefront
 *
 * This configuration merges base design tokens with tenant-specific overrides.
 * Tenant theme tokens are loaded at build time from the database and projected
 * into CSS custom properties for dynamic theming.
 *
 * References:
 * - Blueprint Section 2.0: Standard Kit (Tailwind + PrimeUI)
 * - UI/UX Architecture Section 1.1.4: Tenant Theming & Overrides
 * - UI/UX Architecture Section 1.9: Design Token Delivery & Governance
 */

/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    './src/main/resources/templates/**/*.{html,qute}',
    './modules/core-platform/src/main/resources/templates/**/*.{html,qute}',
  ],
  theme: {
    extend: {
      colors: {
        // Platform base palette - can be overridden per tenant
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
        accent: {
          50: 'var(--color-accent-50, #fff7ed)',
          100: 'var(--color-accent-100, #ffedd5)',
          200: 'var(--color-accent-200, #fed7aa)',
          300: 'var(--color-accent-300, #fdba74)',
          400: 'var(--color-accent-400, #fb923c)',
          500: 'var(--color-accent-500, #f97316)',
          600: 'var(--color-accent-600, #ea580c)',
          700: 'var(--color-accent-700, #c2410c)',
          800: 'var(--color-accent-800, #9a3412)',
          900: 'var(--color-accent-900, #7c2d12)',
          950: 'var(--color-accent-950, #431407)',
        },
        // Semantic tokens - platform-controlled for accessibility
        success: {
          50: 'var(--color-success-50, #f0fdf4)',
          100: 'var(--color-success-100, #dcfce7)',
          500: 'var(--color-success-500, #22c55e)',
          600: 'var(--color-success-600, #16a34a)',
          700: 'var(--color-success-700, #15803d)',
        },
        warning: {
          50: 'var(--color-warning-50, #fffbeb)',
          100: 'var(--color-warning-100, #fef3c7)',
          500: 'var(--color-warning-500, #f59e0b)',
          600: 'var(--color-warning-600, #d97706)',
          700: 'var(--color-warning-700, #b45309)',
        },
        error: {
          50: 'var(--color-error-50, #fef2f2)',
          100: 'var(--color-error-100, #fee2e2)',
          500: 'var(--color-error-500, #ef4444)',
          600: 'var(--color-error-600, #dc2626)',
          700: 'var(--color-error-700, #b91c1c)',
        },
        info: {
          50: 'var(--color-info-50, #eff6ff)',
          100: 'var(--color-info-100, #dbeafe)',
          500: 'var(--color-info-500, #3b82f6)',
          600: 'var(--color-info-600, #2563eb)',
          700: 'var(--color-info-700, #1d4ed8)',
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
        surface: {
          canvas: 'var(--color-surface-canvas, #ffffff)',
          'canvas-alt': 'var(--color-surface-canvas-alt, #f7f8fa)',
          'elevation-1': 'var(--color-surface-elevation-1, #ffffff)',
          'elevation-2': 'var(--color-surface-elevation-2, #f9fafb)',
          'elevation-3': 'var(--color-surface-elevation-3, #f3f4f6)',
          overlay: 'var(--color-surface-overlay, rgba(0, 0, 0, 0.5))',
        },
      },
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        serif: ['Source Serif Pro', 'Georgia', 'ui-serif', 'serif'],
        mono: ['JetBrains Mono', 'Menlo', 'Monaco', 'Courier New', 'monospace'],
        numeric: ['Space Grotesk', 'Inter', 'sans-serif'],
      },
      fontSize: {
        '2xs': ['0.625rem', '0.875rem'], // 10px/14px
        'xs': ['0.75rem', '1rem'], // 12px/16px
        'sm': ['0.875rem', '1.25rem'], // 14px/20px
        'base': ['1rem', '1.5rem'], // 16px/24px
        'lg': ['1.125rem', '1.625rem'], // 18px/26px
        'xl': ['1.25rem', '1.75rem'], // 20px/28px
        '2xl': ['1.5rem', '2rem'], // 24px/32px
        '3xl': ['1.875rem', '2.25rem'], // 30px/36px
        '4xl': ['2.25rem', '2.5rem'], // 36px/40px
        '5xl': ['3rem', '3.25rem'], // 48px/52px
        '6xl': ['3.75rem', '1'],
        '7xl': ['4.5rem', '1'],
        '8xl': ['6rem', '1'],
        '9xl': ['8rem', '1'],
      },
      spacing: {
        '0.5': '0.125rem', // 2px
        '1': '0.25rem', // 4px
        '1.5': '0.375rem', // 6px
        '2': '0.5rem', // 8px
        '2.5': '0.625rem', // 10px
        '3': '0.75rem', // 12px
        '3.5': '0.875rem', // 14px
        '4': '1rem', // 16px
        '5': '1.25rem', // 20px
        '6': '1.5rem', // 24px
        '7': '1.75rem', // 28px
        '8': '2rem', // 32px
        '9': '2.25rem', // 36px
        '10': '2.5rem', // 40px
        '11': '2.75rem', // 44px
        '12': '3rem', // 48px
        '14': '3.5rem', // 56px
        '16': '4rem', // 64px
        '18': '4.5rem',
        '20': '5rem', // 80px
        '24': '6rem', // 96px
        '28': '7rem',
        '32': '8rem',
        '36': '9rem',
        '40': '10rem',
        '44': '11rem',
        '48': '12rem',
        '52': '13rem',
        '56': '14rem',
        '60': '15rem',
        '64': '16rem',
        '72': '18rem',
        '80': '20rem',
        '88': '22rem',
        '96': '24rem',
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
        'xl': '0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04)',
      },
      borderRadius: {
        'none': '0',
        'sm': '0.25rem', // 4px
        'DEFAULT': '0.5rem', // 8px
        'md': '0.5rem', // 8px
        'lg': '0.75rem', // 12px
        'xl': '1rem', // 16px
        '2xl': '1.25rem', // 20px
        '3xl': '1.5rem', // 24px
        'full': '9999px',
      },
      transitionDuration: {
        'fast': '120ms',
        'DEFAULT': '200ms',
        'base': '200ms',
        'slow': '320ms',
      },
      transitionTimingFunction: {
        'standard': 'cubic-bezier(0.2, 0, 0.38, 0.9)',
        'emphasized': 'cubic-bezier(0.34, 1.56, 0.64, 1)',
        'decelerate': 'cubic-bezier(0, 0, 0.2, 1)',
      },
    },
  },
  plugins: [],
}
