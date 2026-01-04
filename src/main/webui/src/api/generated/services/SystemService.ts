/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { HealthResponse } from '../models/HealthResponse';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class SystemService {
    /**
     * Health check endpoint
     * Returns the service health status. Used by Kubernetes liveness/readiness probes.
     * No authentication required.
     *
     * @returns HealthResponse Service is healthy and ready to accept requests
     * @throws ApiError
     */
    public static healthCheck(): CancelablePromise<HealthResponse> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/health',
        });
    }
}
