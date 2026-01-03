/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { Address } from './Address';
import type { CheckoutPreviewRequest } from './CheckoutPreviewRequest';
export type CheckoutCommitRequest = (CheckoutPreviewRequest & {
    billingAddress: Address;
    /**
     * Stripe payment method ID (from Stripe Elements)
     */
    paymentMethodId: string;
});

