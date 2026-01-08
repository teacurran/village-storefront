/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { UserSummary } from './UserSummary';
export type AuthTokenResponse = {
    /**
     * JWT access token (short-lived, 15 min)
     */
    accessToken: string;
    /**
     * JWT refresh token (long-lived, 30 days)
     */
    refreshToken: string;
    /**
     * Access token expiry in seconds
     */
    expiresIn: number;
    /**
     * Token type (always "Bearer")
     */
    tokenType: string;
    user?: UserSummary;
};

