/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
/**
 * RFC 7807 Problem Details for HTTP APIs.
 * Standard error response format used across all endpoints.
 *
 * **Common Error Types:**
 * - `validation-error`: Input validation failed (400)
 * - `authentication-required`: Missing or invalid auth token (401)
 * - `forbidden`: Insufficient permissions (403)
 * - `not-found`: Resource not found (404)
 * - `conflict`: Resource conflict (409)
 * - `rate-limit-exceeded`: Too many requests (429)
 * - `internal-error`: Unexpected server error (500)
 *
 */
export type ProblemDetails = {
    /**
     * URI reference identifying the problem type
     */
    type: string;
    /**
     * Short, human-readable summary of the problem
     */
    title: string;
    /**
     * HTTP status code
     */
    status: number;
    /**
     * Human-readable explanation specific to this occurrence
     */
    detail?: string;
    /**
     * URI reference identifying the specific occurrence
     */
    instance?: string;
    /**
     * Field-level validation errors (key = field name, value = error messages)
     */
    errors?: Record<string, Array<string>>;
};

