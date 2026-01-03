/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { Money } from './Money';
export type ProductSummary = {
    id: string;
    sku: string;
    name: string;
    /**
     * Short description (max 200 chars for listing)
     */
    description?: string;
    price: Money;
    status: ProductSummary.status;
    /**
     * Primary product image URL
     */
    imageUrl?: string;
    /**
     * Aggregate stock availability (true if any variant in stock)
     */
    inStock?: boolean;
};
export namespace ProductSummary {
    export enum status {
        ACTIVE = 'active',
        DRAFT = 'draft',
        ARCHIVED = 'archived',
    }
}

