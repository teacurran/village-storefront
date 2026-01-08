/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { Money } from './Money';
export type CheckoutPreview = {
    subtotal: Money;
    discounts?: Array<{
        code?: string;
        amount?: Money;
    }>;
    tax: Money;
    shipping: Money;
    total: Money;
    currency: string;
    /**
     * Calculated line items with final pricing
     */
    lineItems?: Array<{
        variantId?: string;
        quantity?: number;
        unitPrice?: Money;
        lineTotal?: Money;
    }>;
};

