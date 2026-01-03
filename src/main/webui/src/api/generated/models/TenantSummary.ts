/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type TenantSummary = {
    id: string;
    subdomain: string;
    name: string;
    status: TenantSummary.status;
    createdAt: string;
    /**
     * Billing plan tier
     */
    planTier?: string;
};
export namespace TenantSummary {
    export enum status {
        ACTIVE = 'active',
        SUSPENDED = 'suspended',
        DELETED = 'deleted',
    }
}

