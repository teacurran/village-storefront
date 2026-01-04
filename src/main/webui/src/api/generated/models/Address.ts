/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
/**
 * Physical address for shipping/billing
 */
export type Address = {
    /**
     * Street address line 1
     */
    line1: string;
    /**
     * Street address line 2 (apt, suite, etc.)
     */
    line2?: string;
    city: string;
    /**
     * State/province/region code
     */
    state?: string;
    postalCode: string;
    /**
     * ISO 3166-1 alpha-2 country code
     */
    country: string;
    /**
     * Contact phone number
     */
    phone?: string;
};

