/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
/**
 * Monetary value with currency. Amount stored as string to preserve precision.
 * Always includes exactly 2 decimal places (e.g., "10.00", not "10").
 *
 */
export type Money = {
    /**
     * Decimal amount as string (avoids floating-point precision issues)
     */
    amount: string;
    /**
     * ISO 4217 currency code
     */
    currency: string;
};

