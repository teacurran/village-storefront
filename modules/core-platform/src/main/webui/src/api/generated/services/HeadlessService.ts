/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { CheckoutCommitRequest } from '../models/CheckoutCommitRequest';
import type { CheckoutPreview } from '../models/CheckoutPreview';
import type { CheckoutPreviewRequest } from '../models/CheckoutPreviewRequest';
import type { OrderCreatedResponse } from '../models/OrderCreatedResponse';
import type { PaginationMetadata } from '../models/PaginationMetadata';
import type { ProductDetail } from '../models/ProductDetail';
import type { ProductSummary } from '../models/ProductSummary';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class HeadlessService {
    /**
     * List products (catalog listing)
     * **TODO:** Implement product catalog listing with filtering, sorting, and pagination.
     *
     * Returns paginated list of active products for the current tenant. Supports:
     * - Filtering by category, tags, price range, availability
     * - Sorting by name, price, created date, popularity
     * - Search by product name, SKU, description
     *
     * **Authentication:** Optional (public catalog browsing, auth required for customer-specific pricing)
     *
     * @param page Page number for pagination (1-indexed)
     * @param pageSize Number of items per page (max 100)
     * @param category Filter by category slug
     * @param minPrice Minimum price filter (in tenant's default currency)
     * @param maxPrice Maximum price filter
     * @param search Search query (matches name, SKU, description)
     * @param sort Sort order
     * @returns any Product list successfully retrieved
     * @throws ApiError
     */
    public static listProducts(
        page: number = 1,
        pageSize: number = 20,
        category?: string,
        minPrice?: number,
        maxPrice?: number,
        search?: string,
        sort: 'name' | 'price_asc' | 'price_desc' | 'created_desc' | 'popularity' = 'name',
    ): CancelablePromise<{
        data: Array<ProductSummary>;
        pagination: PaginationMetadata;
    }> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/catalog/products',
            query: {
                'page': page,
                'pageSize': pageSize,
                'category': category,
                'minPrice': minPrice,
                'maxPrice': maxPrice,
                'search': search,
                'sort': sort,
            },
            errors: {
                404: `Tenant not found`,
            },
        });
    }
    /**
     * Get product details
     * **TODO:** Implement product detail retrieval.
     *
     * Returns full product details including variants, pricing, inventory, images.
     * Public endpoint (no auth required for browsing).
     *
     * @param productId Product UUID
     * @returns ProductDetail Product details retrieved
     * @throws ApiError
     */
    public static getProduct(
        productId: string,
    ): CancelablePromise<ProductDetail> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/catalog/products/{productId}',
            path: {
                'productId': productId,
            },
            errors: {
                404: `Product not found`,
            },
        });
    }
    /**
     * Preview checkout totals (cart calculation)
     * **TODO:** Implement checkout preview/calculation.
     *
     * Calculates order totals including:
     * - Line item subtotals (product price × quantity)
     * - Discounts (promo codes, customer-specific pricing)
     * - Taxes (based on shipping address)
     * - Shipping costs (based on carrier rates)
     *
     * **Idempotent:** Safe to call multiple times with same cart state.
     * Does not create order or charge payment method.
     *
     * @param requestBody
     * @param xIdempotencyKey Idempotency key for safe retries of non-idempotent operations (POST, DELETE).
     * If a request with the same idempotency key is replayed within 24 hours,
     * the original response is returned (no duplicate side effects).
     *
     * **Recommended:** Always generate a unique UUID v4 per logical operation.
     * Store the key client-side and reuse on retry.
     *
     * @returns CheckoutPreview Checkout preview calculated
     * @throws ApiError
     */
    public static previewCheckout(
        requestBody: CheckoutPreviewRequest,
        xIdempotencyKey?: string,
    ): CancelablePromise<CheckoutPreview> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/checkout/preview',
            headers: {
                'X-Idempotency-Key': xIdempotencyKey,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid cart state (out of stock, invalid promo code, etc.)`,
            },
        });
    }
    /**
     * Complete checkout and create order
     * **TODO:** Implement order creation and payment processing.
     *
     * Atomically:
     * 1. Creates order record
     * 2. Charges payment method via Stripe
     * 3. Reduces inventory
     * 4. Enqueues fulfillment job
     *
     * **Idempotent:** Uses `X-Idempotency-Key` header to prevent duplicate orders
     * if client retries due to network failure.
     *
     * **Requires authentication:** Must be logged-in customer or use valid API key.
     *
     * @param requestBody
     * @param xIdempotencyKey Idempotency key for safe retries of non-idempotent operations (POST, DELETE).
     * If a request with the same idempotency key is replayed within 24 hours,
     * the original response is returned (no duplicate side effects).
     *
     * **Recommended:** Always generate a unique UUID v4 per logical operation.
     * Store the key client-side and reuse on retry.
     *
     * @returns OrderCreatedResponse Order successfully created and payment charged
     * @throws ApiError
     */
    public static commitCheckout(
        requestBody: CheckoutCommitRequest,
        xIdempotencyKey?: string,
    ): CancelablePromise<OrderCreatedResponse> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/checkout/commit',
            headers: {
                'X-Idempotency-Key': xIdempotencyKey,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request (validation errors, out of stock, etc.)`,
                402: `Payment required (payment method declined)`,
                409: `Duplicate idempotency key (order already created)`,
            },
        });
    }
}
