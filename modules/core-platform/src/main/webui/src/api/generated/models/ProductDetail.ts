/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ProductSummary } from './ProductSummary';
import type { ProductVariant } from './ProductVariant';
export type ProductDetail = (ProductSummary & {
    /**
     * Full product description (Markdown supported)
     */
    longDescription?: string;
    /**
     * All product image URLs
     */
    images?: Array<string>;
    /**
     * Product variants (size, color, etc.)
     */
    variants?: Array<ProductVariant>;
    /**
     * Category slugs
     */
    categories?: Array<string>;
    /**
     * Product tags
     */
    tags?: Array<string>;
    createdAt?: string;
    updatedAt?: string;
});

