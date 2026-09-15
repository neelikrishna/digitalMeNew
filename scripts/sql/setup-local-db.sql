-- One-time setup for a local PostgreSQL install (no Docker).
--
-- Run as the postgres superuser, passing the app password as a variable so it
-- is never written into this file:
--
--   & "C:\Program Files\PostgreSQL\17\bin\psql.exe" -U postgres -d postgres ^
--       -v app_password="'your-password-here'" ^
--       -f scripts/sql/setup-local-db.sql
--
-- The same password goes into DB_PASSWORD in your .env file.

-- Least-privilege application role: can log in, owns nothing by default,
-- and is not a superuser.
CREATE ROLE digitalself_app WITH LOGIN PASSWORD :app_password;

CREATE DATABASE digitalself OWNER digitalself_app;

-- pgcrypto is required for gen_random_uuid(); it ships with a stock install.
\connect digitalself
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Flyway (running as digitalself_app) creates the tables it owns.
GRANT ALL ON SCHEMA public TO digitalself_app;
