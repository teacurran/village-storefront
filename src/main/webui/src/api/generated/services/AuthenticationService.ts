/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AuthTokenResponse } from '../models/AuthTokenResponse';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class AuthenticationService {
    /**
     * Authenticate user and obtain JWT tokens
     * Authenticates a user (customer, admin, or vendor) and returns access + refresh tokens.
     * Tenant context is automatically resolved from the Host header before authentication.
     *
     * **Token Claims:**
     * - `sub`: User ID (UUID)
     * - `tenant_id`: Tenant ID (UUID)
     * - `roles`: Array of role strings (admin, customer, vendor)
     * - `exp`: Expiration timestamp
     *
     * **Session Logging:**
     * All login attempts are logged to the database for audit/compliance purposes.
     *
     * @param requestBody
     * @returns AuthTokenResponse Authentication successful, tokens issued
     * @throws ApiError
     */
    public static login(
        requestBody: {
            email: string;
            password: string;
        },
    ): CancelablePromise<AuthTokenResponse> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/auth/login',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                401: `Invalid credentials or account locked`,
                404: `Tenant not found (invalid subdomain/domain)`,
            },
        });
    }
    /**
     * Refresh access token using refresh token
     * Exchanges a valid refresh token for a new access token + refresh token pair.
     * Refresh tokens are single-use; the old refresh token is invalidated upon successful refresh.
     *
     * @param requestBody
     * @returns AuthTokenResponse Token refresh successful
     * @throws ApiError
     */
    public static refreshToken(
        requestBody: {
            /**
             * The refresh token obtained from login
             */
            refreshToken: string;
        },
    ): CancelablePromise<AuthTokenResponse> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/auth/refresh',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                401: `Invalid or expired refresh token`,
            },
        });
    }
    /**
     * Invalidate current session
     * Logs out the current user by invalidating the refresh token.
     * Access tokens cannot be invalidated (stateless JWT), but expire in 15 minutes.
     *
     * @returns void
     * @throws ApiError
     */
    public static logout(): CancelablePromise<void> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/auth/logout',
            errors: {
                401: `Invalid or missing authentication token`,
            },
        });
    }
}
