/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { Money } from './Money';
export type ProductVariant = {
    id: string;
    sku: string;
    price: Money;
    /**
     * Available inventory quantity
     */
    stock: number;
    /**
     * Variant option values (e.g., {"color": "Red", "size": "Large"})
     */
    options?: Record<string, string>;
};

