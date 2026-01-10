/* generated using openapi-typescript-codegen -- do no edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type UserSummary = {
    /**
     * User ID
     */
    id: string;
    /**
     * User email address
     */
    email: string;
    /**
     * User full name
     */
    name?: string;
    /**
     * User roles within tenant
     */
    roles: Array<'customer' | 'admin' | 'vendor' | 'platform_admin'>;
};

