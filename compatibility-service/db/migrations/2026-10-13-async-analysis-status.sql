-- compatibility_analyses: status, failure_reason, completed_at for asynchronous analysis.
--
-- Hibernate's ddl-auto: update can add the new columns but cannot drop NOT NULL, so this runs by
-- hand, before deploying the compatibility-service build that runs analyses through Kafka (commit
-- 19c9254). Every existing row came from the synchronous service, so each one is a finished
-- analysis: they are backfilled as COMPLETED, with completed_at = created_at. Scores become
-- nullable because PENDING, IN_PROGRESS and FAILED analyses have none.
--
-- Stop the old build first: it inserts rows without status, which is NOT NULL after this.
-- Already applied locally; do not re-run it there (the UPDATE would mark every PENDING and
-- FAILED row COMPLETED).
--
-- One transaction: if any statement fails, the table is left exactly as it was.

BEGIN;

ALTER TABLE compatibility_analyses
    ADD COLUMN status varchar(20),
    ADD COLUMN failure_reason varchar(255),
    ADD COLUMN completed_at timestamp(6) with time zone;

UPDATE compatibility_analyses
    SET status = 'COMPLETED', completed_at = created_at;

ALTER TABLE compatibility_analyses
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN skills_overlap DROP NOT NULL,
    ALTER COLUMN experience_match DROP NOT NULL,
    ALTER COLUMN keyword_coverage DROP NOT NULL,
    ALTER COLUMN semantic_similarity DROP NOT NULL;

COMMIT;
