-- Presence is an ephemeral Redis lease. Directory rows remain useful identity projections,
-- but must not claim that seeded or previously copied availability is current.
UPDATE msg_people_snapshot
   SET presence_state = 'UNKNOWN',
       updated_at = CURRENT_TIMESTAMP
 WHERE presence_state <> 'UNKNOWN';

COMMENT ON COLUMN msg_people_snapshot.presence_state IS
    'Compatibility projection only; API presence is resolved from the runtime Redis lease authority.';
