-- Support owner-scoped Activity substring search without scanning every retained event.
-- The indexed expression must remain byte-for-byte equivalent to ActivityRepository's
-- normalized search document apart from the repository's table alias.

SET lock_timeout = '5s';
SET statement_timeout = '30min';

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- A failed concurrent build can leave an INVALID index. Because Flyway does not
-- record a failed migration, the retry must remove that artifact first.
DROP INDEX CONCURRENTLY IF EXISTS idx_activity_search_text_trgm;

CREATE INDEX CONCURRENTLY idx_activity_search_text_trgm
    ON wrk_activity_events USING GIN ((
        lower(
            COALESCE(title_ko, '') || ' ' ||
            COALESCE(title_en, '') || ' ' ||
            COALESCE(summary_ko, '') || ' ' ||
            COALESCE(summary_en, '') || ' ' ||
            COALESCE(object_label_ko, '') || ' ' ||
            COALESCE(object_label_en, '')
        )
    ) gin_trgm_ops);

COMMENT ON INDEX idx_activity_search_text_trgm IS
    'Trigram index for locale-independent Activity title, summary and object-label substring search.';

-- Do not leak Activity-specific timeouts into later migrations on the reused Flyway session.
RESET lock_timeout;
RESET statement_timeout;
