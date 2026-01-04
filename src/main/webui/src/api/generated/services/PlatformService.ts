/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { CreateTenantRequest } from '../models/CreateTenantRequest';
import type { PaginationMetadata } from '../models/PaginationMetadata';
import type { TenantDetail } from '../models/TenantDetail';
import type { TenantMetadata } from '../models/TenantMetadata';
import type { TenantSummary } from '../models/TenantSummary';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class PlatformService {
    /**
     * Resolve current tenant metadata
     * Returns metadata about the currently resolved tenant based on the Host header.
     * Useful for headless clients to verify tenant context and retrieve theme settings.
     *
     * **No authentication required** - tenant resolution happens before authentication.
     *
     * @returns TenantMetadata Tenant successfully resolved
     * @throws ApiError
     */
    public static resolveTenant(): CancelablePromise<TenantMetadata> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/tenants/resolve',
            errors: {
                404: `No tenant found for this domain/subdomain`,
            },
        });
    }
    /**
     * List all tenants (platform admin only)
     * **TODO:** Implement tenant listing for platform administrators.
     *
     * Returns paginated list of all tenants with status, creation date, and billing info.
     * **Restricted to platform administrators only** (not accessible to store owners).
     *
     * @param page Page number for pagination (1-indexed)
     * @param pageSize Number of items per page (max 100)
     * @param status Filter by tenant status
     * @returns any Tenant list retrieved
     * @throws ApiError
     */
    public static listTenants(
        page: number = 1,
        pageSize: number = 20,
        status?: 'active' | 'suspended' | 'deleted',
    ): CancelablePromise<{
        data: Array<TenantSummary>;
        pagination: PaginationMetadata;
    }> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/platform/tenants',
            query: {
                'page': page,
                'pageSize': pageSize,
                'status': status,
            },
            errors: {
                403: `Forbidden (requires platform admin role)`,
            },
        });
    }
    /**
     * Create new tenant (platform admin only)
     * **TODO:** Implement tenant provisioning.
     *
     * Creates a new tenant (merchant store) with subdomain and initial settings.
     * Atomically provisions:
     * - Tenant record
     * - Default store settings
     * - Initial admin user account
     *
     * **Restricted to platform administrators only.**
     *
     * @param requestBody
     * @returns TenantDetail Tenant created successfully
     * @throws ApiError
     */
    public static createTenant(
        requestBody: CreateTenantRequest,
    ): CancelablePromise<TenantDetail> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/platform/tenants',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Validation error (subdomain taken, invalid format, etc.)`,
                403: `Forbidden (requires platform admin role)`,
            },
        });
    }
}
