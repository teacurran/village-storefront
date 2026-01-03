/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type TenantMetadata = {
    /**
     * Tenant ID
     */
    id: string;
    /**
     * Tenant subdomain
     */
    subdomain: string;
    /**
     * Store name (display)
     */
    name: string;
    /**
     * Tenant status
     */
    status: TenantMetadata.status;
    /**
     * Verified custom domains for this tenant
     */
    customDomains?: Array<string>;
    /**
     * Tenant-specific settings (theme, locale, features)
     */
    settings?: Record<string, any>;
};
export namespace TenantMetadata {
    /**
     * Tenant status
     */
    export enum status {
        ACTIVE = 'active',
        SUSPENDED = 'suspended',
        DELETED = 'deleted',
    }
}

