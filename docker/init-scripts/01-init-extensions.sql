-- PostgreSQL Initialization Script
-- Runs automatically when the database is first created
-- Creates necessary extensions and sets up initial configuration

-- Enable UUID extension for generating UUIDs
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Enable pgcrypto for cryptographic functions (used for password hashing)
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Enable pg_trgm for fuzzy text search (product search, autocomplete)
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- Log extension setup
DO $$
BEGIN
    RAISE NOTICE 'PostgreSQL extensions initialized successfully';
    RAISE NOTICE '  - uuid-ossp: UUID generation';
    RAISE NOTICE '  - pgcrypto: Cryptographic functions';
    RAISE NOTICE '  - pg_trgm: Trigram text search';
END
$$;
