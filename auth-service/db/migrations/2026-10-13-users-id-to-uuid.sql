-- users.id: bigint identity -> uuid.
--
-- Hibernate's ddl-auto: update cannot change a column's type, so this runs by hand, before
-- deploying the auth-service build whose User.id is a UUID. Old numeric ids are discarded, not
-- converted: nothing references them (no foreign keys to users; other services key on the email
-- in the JWT subject). The guard below stops the migration if that is no longer true.
--
-- One transaction: if any statement fails, the table is left exactly as it was.

BEGIN;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE contype = 'f' AND confrelid = 'users'::regclass) THEN
        RAISE EXCEPTION 'users is referenced by a foreign key; those columns must be migrated too';
    END IF;
END $$;

-- gen_random_uuid() is volatile, so adding the column evaluates it once per existing row.
-- The default stays for manual inserts; the application generates its own ids.
ALTER TABLE users ADD COLUMN new_id uuid NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE users DROP CONSTRAINT users_pkey;
ALTER TABLE users DROP COLUMN id;               -- also drops the identity sequence users_id_seq
ALTER TABLE users RENAME COLUMN new_id TO id;
ALTER TABLE users ADD CONSTRAINT users_pkey PRIMARY KEY (id);

COMMIT;
