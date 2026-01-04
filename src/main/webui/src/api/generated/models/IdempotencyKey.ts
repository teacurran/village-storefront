/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
/**
 * Idempotency key for safe retries of non-idempotent operations (POST, DELETE).
 * If a request with the same idempotency key is replayed within 24 hours,
 * the original response is returned (no duplicate side effects).
 *
 * **Recommended:** Always generate a unique UUID v4 per logical operation.
 * Store the key client-side and reuse on retry.
 *
 */
export type IdempotencyKey = string;
