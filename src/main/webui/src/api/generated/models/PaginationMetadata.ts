/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
/**
 * Pagination metadata for paginated list responses.
 * Includes navigation helpers (hasNext, hasPrev) to simplify client logic.
 *
 */
export type PaginationMetadata = {
    /**
     * Current page number (1-indexed)
     */
    page: number;
    /**
     * Items per page
     */
    pageSize: number;
    /**
     * Total number of items across all pages
     */
    totalItems: number;
    /**
     * Total number of pages
     */
    totalPages: number;
    /**
     * Whether there is a next page
     */
    hasNext?: boolean;
    /**
     * Whether there is a previous page
     */
    hasPrev?: boolean;
};

