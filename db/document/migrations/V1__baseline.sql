-- Baseline migration (project foundation). Creates no tables.
-- Establishes least privilege for the runtime role (decision: separate migrator and runtime roles, Step 07b).
-- Runs as the migrator role, which owns this database.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO ${app_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${app_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO ${app_role};
