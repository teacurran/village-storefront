/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { Money } from './Money';
export type OrderCreatedResponse = {
    orderId: string;
    /**
     * Human-readable order number
     */
    orderNumber: string;
    status: OrderCreatedResponse.status;
    total: Money;
    createdAt: string;
    /**
     * Estimated delivery date
     */
    estimatedDelivery?: string;
};
export namespace OrderCreatedResponse {
    export enum status {
        PENDING = 'pending',
        PROCESSING = 'processing',
        SHIPPED = 'shipped',
        DELIVERED = 'delivered',
        CANCELLED = 'cancelled',
    }
}

