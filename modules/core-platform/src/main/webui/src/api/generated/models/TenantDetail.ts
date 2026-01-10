/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { TenantSummary } from './TenantSummary';
export type TenantDetail = (TenantSummary & {
    customDomains?: Array<{
        domain?: string;
        verified?: boolean;
    }>;
    settings?: Record<string, any>;
    updatedAt?: string;
});

