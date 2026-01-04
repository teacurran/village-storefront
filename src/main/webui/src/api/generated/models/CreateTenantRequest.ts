/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type CreateTenantRequest = {
    /**
     * Tenant subdomain (lowercase alphanumeric + hyphens, 3-63 chars)
     */
    subdomain: string;
    /**
     * Store display name
     */
    name: string;
    /**
     * Email for initial admin user account
     */
    adminEmail: string;
    /**
     * Password for initial admin user (if not provided, generated and emailed)
     */
    adminPassword?: string;
    /**
     * Initial tenant settings (optional)
     */
    settings?: Record<string, any>;
};

