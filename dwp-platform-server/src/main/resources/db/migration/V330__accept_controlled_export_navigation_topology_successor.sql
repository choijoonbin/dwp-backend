-- R7-C1 migration lease: append-only controlled-export navigation topology successor.
-- Historical V329 rows remain valid and immutable; new authoring uses the exact R6 successor.

ALTER TABLE hris_navigation_policy_v2_revisions
    DROP CONSTRAINT ck_hris_navigation_policy_v2_revision;

ALTER TABLE hris_navigation_policy_v2_revisions
    ADD CONSTRAINT ck_hris_navigation_policy_v2_revision CHECK (
        revision > 0 AND aggregate_version >= 0
        AND lifecycle_state IN (
            'DRAFT', 'VALIDATED', 'PENDING_APPROVAL', 'APPROVED', 'PUBLISHED')
        AND rollout_mode IN ('OFF', 'SHADOW', 'ENFORCED')
        AND (
            (topology_revision = 'HRIS-IA-98-WB7-G46-N97-R5'
             AND topology_sha256 =
                 '4d27b3b8958d591968ed42e7ccd6387664fff66a364adfc83a8a5b7f223a6d8a')
            OR
            (topology_revision = 'HRIS-IA-99-WB7-G46-N98-R6'
             AND topology_sha256 =
                 '4ac4b5411d804888ff1a4bd0373c70419b698a0abe43d0de43bd646966cfe166')
        )
        AND ia_register_sha256 =
            'ab506a2bb2f80c8ab81a4b9d575374d6bda83b312d11dce07495d4812bdf4686'
        AND shell_register_sha256 =
            'f85ee40a66ce4100c5578fd3ca96bc89b652b0762e19d74cc7aeb76338009000'
        AND task_group_register_sha256 =
            'f0b1ebb316e4d5944aac90002e144f25d8c1318216b6733e674cdc8c5042872b'
        AND generated_ia_sha256 =
            '584cc32f845720ad06fb1bdcea7c967026c0e3a648f70e0215fd8a95a6b055bd');
