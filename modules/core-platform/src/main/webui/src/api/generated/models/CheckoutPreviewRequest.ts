/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { Address } from './Address';
export type CheckoutPreviewRequest = {
    lineItems: Array<{
        variantId: string;
        quantity: number;
    }>;
    shippingAddress?: Address;
    /**
     * Promotional/discount code
     */
    promoCode?: string;
};

