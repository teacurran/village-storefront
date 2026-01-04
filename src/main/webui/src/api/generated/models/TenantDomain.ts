/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
/**
 * **Optional override** for tenant resolution in testing/development.
 * Production requests should rely on the HTTP `Host` header for automatic resolution.
 *
 * Useful for:
 * - Multi-tenant testing (switch tenants without DNS changes)
 * - Platform admin impersonation
 * - Local development with ngrok/tunnels
 *
 */
export type TenantDomain = string;
