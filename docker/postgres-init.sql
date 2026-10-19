-- Runs once, on first start of an empty data volume (docker-entrypoint-initdb.d).
-- The connecting role (POSTGRES_USER) is a superuser and becomes the owner of each database.
-- Tables are created by each service's Flyway migrations on startup.
CREATE DATABASE callback_auth;
CREATE DATABASE callback_jdresume;
CREATE DATABASE callback_compatibility;
CREATE DATABASE callback_question;
CREATE DATABASE callback_session;

-- compatibility-service's pgvector store (initialize-schema: true) creates the vector_store
-- table but needs the extension available; create it here so the service never needs it itself.
\connect callback_compatibility
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
