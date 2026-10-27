package com.dwp.services.people.hr.performance.compensationplanning;

/** SQL templates for compensation cycle, proposal, and command evidence persistence. */
final class CompensationPlanningSql {

    static final String RECEIPT_BY_IDEMPOTENCY_1 = """
                WHERE receipt.tenant_id = :tenantId
                  AND receipt.subject_principal_public_id = :subjectId
                  AND receipt.originating_action = :originatingAction
                  AND receipt.idempotency_key = :idempotencyKey
                """;

    static final String RECEIPT_BY_ID_2 = """
                WHERE receipt.tenant_id = :tenantId
                  AND receipt.command_receipt_id = :receiptId
                """;

    static final String CYCLE_ARTIFACTS_FOR_RECEIPT_3 = """
                SELECT guideline.public_id AS guideline_id,
                       population.public_id AS population_id
                  FROM hris_performance.prf_command_receipts receipt
                  JOIN hris_performance.prf_per_artifact_versions guideline
                    ON guideline.tenant_id = receipt.tenant_id
                   AND guideline.public_id = receipt.result_ref
                   AND guideline.artifact_type = 'COMPENSATION_GUIDELINES'
                   AND guideline.owner_aggregate_type = 'COMPENSATION_CYCLE'
                   AND guideline.owner_aggregate_public_id = :cycleId
                  JOIN hris_performance.prf_per_artifact_versions population
                    ON population.tenant_id = guideline.tenant_id
                   AND population.owner_aggregate_type = 'COMPENSATION_CYCLE'
                   AND population.owner_aggregate_public_id =
                       guideline.owner_aggregate_public_id
                   AND population.artifact_type = 'COMPENSATION_POPULATION'
                   AND population.artifact_revision = guideline.artifact_revision
                 WHERE receipt.tenant_id = :tenantId
                   AND receipt.command_receipt_id = :receiptId
                   AND receipt.receipt_state = 'SUCCEEDED'
                """;

    static final String PROPOSAL_REVISION_FOR_RECEIPT_4 = """
                SELECT revision.public_id, revision.revision_no,
                       revision.typed_content_artifact_public_id,
                       revision.content_digest
                  FROM hris_performance.prf_cmp_proposal_revisions revision
                  JOIN hris_performance.prf_cmp_proposals proposal
                    ON proposal.tenant_id = revision.tenant_id
                   AND proposal.prf_cmp_proposals_internal_id =
                       revision.compensation_proposal_id
                  JOIN hris_performance.prf_cmp_cycles cycle
                    ON cycle.tenant_id = proposal.tenant_id
                   AND cycle.prf_cmp_cycles_internal_id =
                       proposal.compensation_cycle_id
                  JOIN hris_performance.prf_command_receipts receipt
                    ON receipt.tenant_id = revision.tenant_id
                   AND receipt.command_receipt_id = :receiptId
                   AND receipt.result_ref = revision.public_id
                   AND receipt.receipt_state = 'SUCCEEDED'
                 WHERE revision.tenant_id = :tenantId
                   AND cycle.public_id = :cycleId
                   AND proposal.public_id = :proposalId
                """;

    static final String RESERVE_RECEIPT_5 = """
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version, request_hash,
                    population_scope_digest, field_policy_revision, purpose_code,
                    authorization_revision, receipt_state)
                VALUES (
                    :receiptId, :tenantId, :idempotencyKey, :operationId,
                    :originatingAction, :actorId, :subjectId,
                    :aggregateId, :expectedVersion, :requestHash,
                    :scopeDigest, :fieldPolicyRevision, :purposeCode,
                    :authorizationRevision, 'ACCEPTED')
                ON CONFLICT DO NOTHING
                """;

    static final String COMPLETE_RECEIPT_6 = """
                UPDATE hris_performance.prf_command_receipts
                   SET applied_aggregate_version = :appliedVersion,
                       receipt_state = 'SUCCEEDED',
                       result_ref = :resultRef,
                       completed_at = :completedAt
                 WHERE tenant_id = :tenantId
                   AND command_receipt_id = :receiptId
                   AND receipt_state = 'ACCEPTED'
                """;

    static final String INSERT_OUTBOX_7 = """
                    INSERT INTO hris_performance.prf_outbox_events (
                        event_id, tenant_id, command_receipt_id, aggregate_type,
                        aggregate_id, aggregate_version, event_type, payload,
                        delivery_state, delivery_attempts, available_at, created_at)
                    VALUES (
                        :eventId, :tenantId, :receiptId, :aggregateType,
                        :aggregateId, :aggregateVersion, :eventType,
                        CAST(:payload AS JSONB), 'PENDING', 0, :occurredAt, :occurredAt)
                    """;

    static final String INSERT_CYCLE_8 = """
                INSERT INTO hris_performance.prf_cmp_cycles (
                    tenant_id, public_id, aggregate_version,
                    created_at, created_by, updated_at, correlation_id,
                    cycle_code, guideline_artifact_public_id,
                    population_artifact_public_id, effective_from, effective_to, status)
                VALUES (
                    :tenantId, :cycleId, 1,
                    :now, :subjectId, :now, :correlationId,
                    :cycleCode, :guidelineArtifactId,
                    :populationArtifactId, :effectiveFrom, :effectiveTo, 'DRAFT')
                """;

    static final String CYCLE_9 = """
                SELECT prf_cmp_cycles_internal_id, public_id, aggregate_version,
                       created_at, created_by, updated_at, correlation_id,
                       cycle_code, guideline_artifact_public_id,
                       population_artifact_public_id, effective_from, effective_to, status
                  FROM hris_performance.prf_cmp_cycles
                 WHERE tenant_id = :tenantId AND public_id = :cycleId
                """;

    static final String CYCLES_10 = """
                SELECT prf_cmp_cycles_internal_id, public_id, aggregate_version,
                       created_at, created_by, updated_at, correlation_id,
                       cycle_code, guideline_artifact_public_id,
                       population_artifact_public_id, effective_from, effective_to, status
                  FROM hris_performance.prf_cmp_cycles
                 WHERE tenant_id = :tenantId
                 ORDER BY updated_at DESC, public_id
                """;

    static final String REVISE_CYCLE_11 = """
                UPDATE hris_performance.prf_cmp_cycles
                   SET aggregate_version = aggregate_version + 1,
                       updated_at = :now,
                       correlation_id = :correlationId,
                       guideline_artifact_public_id = :guidelineArtifactId,
                       population_artifact_public_id = :populationArtifactId,
                       effective_from = :effectiveFrom,
                       effective_to = :effectiveTo
                 WHERE tenant_id = :tenantId AND public_id = :cycleId
                   AND aggregate_version = :expectedVersion
                   AND status IN ('DRAFT', 'OPEN')
                """;

    static final String APPEND_BUDGET_12 = """
                UPDATE hris_performance.prf_cmp_cycles
                   SET aggregate_version = aggregate_version + 1,
                       status = 'OPEN', updated_at = :now,
                       correlation_id = :correlationId
                 WHERE tenant_id = :tenantId AND public_id = :cycleId
                   AND aggregate_version = :expectedVersion
                   AND status IN ('DRAFT', 'OPEN')
                """;

    static final String APPEND_BUDGET_13 = """
                SELECT COALESCE(MAX(entry_sequence), 0) + 1
                  FROM hris_performance.prf_cmp_budget_ledger
                 WHERE tenant_id = :tenantId
                   AND compensation_cycle_id = :cycleInternalId
                """;

    static final String APPEND_BUDGET_14 = """
                INSERT INTO hris_performance.prf_cmp_budget_ledger (
                    tenant_id, public_id, aggregate_version,
                    created_at, created_by, updated_at, correlation_id,
                    compensation_cycle_id, org_public_id, currency_code,
                    amount, entry_sequence, entry_type, source_entry_public_id)
                VALUES (
                    :tenantId, :entryId, 1,
                    :now, :subjectId, :now, :correlationId,
                    :cycleInternalId, :organizationId, :currency,
                    :amount, :entrySequence, :entryType, :sourceEntryId)
                """;

    static final String BUDGET_ENTRIES_15 = """
                SELECT public_id, entry_sequence, org_public_id, currency_code,
                       amount, entry_type, source_entry_public_id, created_at
                  FROM hris_performance.prf_cmp_budget_ledger
                 WHERE tenant_id = :tenantId
                   AND compensation_cycle_id = :cycleInternalId
                 ORDER BY entry_sequence
                """;

    static final String BUDGET_TOTALS_16 = """
                SELECT org_public_id, currency_code,
                       SUM(CASE WHEN entry_type = 'ALLOCATE'
                           THEN amount ELSE -amount END) AS total
                  FROM hris_performance.prf_cmp_budget_ledger
                 WHERE tenant_id = :tenantId
                   AND compensation_cycle_id = :cycleInternalId
                 GROUP BY org_public_id, currency_code
                """;

    static final String PROJECTION_WATERMARK_17 = """
                SELECT GREATEST(
                           cycle.updated_at,
                           COALESCE((
                               SELECT MAX(ledger.created_at)
                                 FROM hris_performance.prf_cmp_budget_ledger ledger
                                WHERE ledger.tenant_id = cycle.tenant_id
                                  AND ledger.compensation_cycle_id =
                                      cycle.prf_cmp_cycles_internal_id
                           ), cycle.updated_at),
                           COALESCE((
                               SELECT MAX(proposal.updated_at)
                                 FROM hris_performance.prf_cmp_proposals proposal
                                WHERE proposal.tenant_id = cycle.tenant_id
                                  AND proposal.compensation_cycle_id =
                                      cycle.prf_cmp_cycles_internal_id
                           ), cycle.updated_at)
                       ) AS projection_watermark
                  FROM hris_performance.prf_cmp_cycles cycle
                 WHERE cycle.tenant_id = :tenantId
                   AND cycle.prf_cmp_cycles_internal_id = :cycleInternalId
                """;

    static final String PROPOSAL_18 = """
                SELECT proposal.prf_cmp_proposals_internal_id,
                       proposal.public_id, proposal.aggregate_version,
                       proposal.created_at, proposal.created_by,
                       proposal.updated_at, proposal.correlation_id,
                       proposal.compensation_cycle_id,
                       proposal.worker_public_id, proposal.assignment_public_id,
                       proposal.component_code, proposal.currency_code,
                       proposal.amount, proposal.effective_from, proposal.effective_to,
                       proposal.current_revision, proposal.status,
                       proposal.supersedes_proposal_public_id,
                       revision.public_id AS revision_public_id,
                       revision.created_by AS revision_created_by,
                       revision.typed_content_artifact_public_id,
                       revision.content_digest
                  FROM hris_performance.prf_cmp_proposals proposal
                  JOIN hris_performance.prf_cmp_cycles cycle
                    ON cycle.tenant_id = proposal.tenant_id
                   AND cycle.prf_cmp_cycles_internal_id = proposal.compensation_cycle_id
                  JOIN hris_performance.prf_cmp_proposal_revisions revision
                    ON revision.tenant_id = proposal.tenant_id
                   AND revision.compensation_proposal_id =
                       proposal.prf_cmp_proposals_internal_id
                   AND revision.revision_no = proposal.current_revision
                 WHERE proposal.tenant_id = :tenantId
                   AND cycle.public_id = :cycleId
                   AND proposal.public_id = :proposalId
                """;

    static final String PROPOSAL_BY_ID_19 = """
                SELECT proposal.prf_cmp_proposals_internal_id,
                       proposal.public_id, proposal.aggregate_version,
                       proposal.created_at, proposal.created_by,
                       proposal.updated_at, proposal.correlation_id,
                       proposal.compensation_cycle_id,
                       proposal.worker_public_id, proposal.assignment_public_id,
                       proposal.component_code, proposal.currency_code,
                       proposal.amount, proposal.effective_from, proposal.effective_to,
                       proposal.current_revision, proposal.status,
                       proposal.supersedes_proposal_public_id,
                       revision.public_id AS revision_public_id,
                       revision.created_by AS revision_created_by,
                       revision.typed_content_artifact_public_id,
                       revision.content_digest,
                       cycle.public_id AS cycle_public_id
                  FROM hris_performance.prf_cmp_proposals proposal
                  JOIN hris_performance.prf_cmp_cycles cycle
                    ON cycle.tenant_id = proposal.tenant_id
                   AND cycle.prf_cmp_cycles_internal_id = proposal.compensation_cycle_id
                  JOIN hris_performance.prf_cmp_proposal_revisions revision
                    ON revision.tenant_id = proposal.tenant_id
                   AND revision.compensation_proposal_id =
                       proposal.prf_cmp_proposals_internal_id
                   AND revision.revision_no = proposal.current_revision
                 WHERE proposal.tenant_id = :tenantId
                   AND proposal.public_id = :proposalId
                """;

    static final String PROPOSALS_20 = """
                SELECT proposal.prf_cmp_proposals_internal_id,
                       proposal.public_id, proposal.aggregate_version,
                       proposal.created_at, proposal.created_by,
                       proposal.updated_at, proposal.correlation_id,
                       proposal.compensation_cycle_id,
                       proposal.worker_public_id, proposal.assignment_public_id,
                       proposal.component_code, proposal.currency_code,
                       proposal.amount, proposal.effective_from, proposal.effective_to,
                       proposal.current_revision, proposal.status,
                       proposal.supersedes_proposal_public_id,
                       revision.public_id AS revision_public_id,
                       revision.created_by AS revision_created_by,
                       revision.typed_content_artifact_public_id,
                       revision.content_digest
                  FROM hris_performance.prf_cmp_proposals proposal
                  JOIN hris_performance.prf_cmp_proposal_revisions revision
                    ON revision.tenant_id = proposal.tenant_id
                   AND revision.compensation_proposal_id =
                       proposal.prf_cmp_proposals_internal_id
                   AND revision.revision_no = proposal.current_revision
                 WHERE proposal.tenant_id = :tenantId
                   AND proposal.compensation_cycle_id = :cycleInternalId
                 ORDER BY proposal.public_id
                """;

    static final String PROPOSAL_MAKER_IDS_21 = """
                SELECT maker_id
                  FROM (
                        SELECT proposal.created_by AS maker_id
                          FROM hris_performance.prf_cmp_proposals proposal
                         WHERE proposal.tenant_id = :tenantId
                           AND proposal.compensation_cycle_id = :cycleInternalId
                        UNION
                        SELECT revision.created_by AS maker_id
                          FROM hris_performance.prf_cmp_proposal_revisions revision
                          JOIN hris_performance.prf_cmp_proposals proposal
                            ON proposal.tenant_id = revision.tenant_id
                           AND proposal.prf_cmp_proposals_internal_id =
                               revision.compensation_proposal_id
                         WHERE proposal.tenant_id = :tenantId
                           AND proposal.compensation_cycle_id = :cycleInternalId
                        UNION
                        SELECT receipt.subject_principal_public_id AS maker_id
                          FROM hris_performance.prf_command_receipts receipt
                          JOIN hris_performance.prf_cmp_cycles cycle
                            ON cycle.tenant_id = receipt.tenant_id
                           AND cycle.prf_cmp_cycles_internal_id = :cycleInternalId
                         WHERE receipt.tenant_id = :tenantId
                           AND receipt.originating_action = 'hcm.compensation.plan.propose'
                           AND receipt.receipt_state = 'SUCCEEDED'
                           AND (
                                receipt.aggregate_id = cycle.public_id
                                OR EXISTS (
                                    SELECT 1
                                      FROM hris_performance.prf_cmp_proposals proposal
                                     WHERE proposal.tenant_id = receipt.tenant_id
                                       AND proposal.compensation_cycle_id = :cycleInternalId
                                       AND proposal.public_id = receipt.aggregate_id
                                )
                           )
                       ) makers
                 ORDER BY maker_id
                """;

    static final String INSERT_PROPOSAL_22 = """
                INSERT INTO hris_performance.prf_cmp_proposals (
                    tenant_id, public_id, aggregate_version,
                    created_at, created_by, updated_at, correlation_id,
                    compensation_cycle_id, worker_public_id, assignment_public_id,
                    component_code, currency_code, amount, effective_from, effective_to,
                    current_revision, status, supersedes_proposal_public_id)
                VALUES (
                    :tenantId, :proposalId, 1,
                    :now, :subjectId, :now, :correlationId,
                    :cycleInternalId, :workerId, :assignmentId,
                    :componentCode, :currency, :amount, :effectiveFrom, :effectiveTo,
                    1, 'DRAFT', :supersedesProposalId)
                RETURNING prf_cmp_proposals_internal_id
                """;

    static final String UPDATE_PROPOSAL_23 = """
                UPDATE hris_performance.prf_cmp_proposals
                   SET aggregate_version = aggregate_version + 1,
                       updated_at = :now, correlation_id = :correlationId,
                       component_code = :componentCode,
                       currency_code = :currency, amount = :amount,
                       effective_from = :effectiveFrom, effective_to = :effectiveTo,
                       current_revision = :revision
                 WHERE tenant_id = :tenantId
                   AND prf_cmp_proposals_internal_id = :proposalInternalId
                   AND aggregate_version = :expectedVersion
                   AND status = 'DRAFT'
                """;

    static final String SUBMIT_PROPOSAL_24 = """
                UPDATE hris_performance.prf_cmp_proposals
                   SET aggregate_version = aggregate_version + 1,
                       status = 'SUBMITTED', updated_at = :now,
                       correlation_id = :correlationId
                 WHERE tenant_id = :tenantId
                   AND prf_cmp_proposals_internal_id = :proposalInternalId
                   AND aggregate_version = :expectedVersion
                   AND status = 'DRAFT'
                """;

    static final String SUBMIT_PROPOSAL_25 = """
                UPDATE hris_performance.prf_cmp_cycles
                   SET aggregate_version = aggregate_version + 1,
                       status = 'PROPOSAL_REVIEW', updated_at = :now,
                       correlation_id = :correlationId
                 WHERE tenant_id = :tenantId
                   AND prf_cmp_cycles_internal_id = :cycleInternalId
                   AND aggregate_version = :cycleVersion
                   AND status IN ('OPEN', 'PROPOSAL_REVIEW')
                """;

    static final String APPROVE_26 = """
                UPDATE hris_performance.prf_cmp_cycles
                   SET aggregate_version = aggregate_version + 1,
                       status = 'APPROVED', updated_at = :now,
                       correlation_id = :correlationId
                 WHERE tenant_id = :tenantId
                   AND prf_cmp_cycles_internal_id = :cycleInternalId
                   AND aggregate_version = :expectedVersion
                   AND status = 'PROPOSAL_REVIEW'
                """;

    static final String APPROVE_27 = """
                UPDATE hris_performance.prf_cmp_proposals
                   SET aggregate_version = aggregate_version + 1,
                       status = 'FROZEN', updated_at = :now,
                       correlation_id = :correlationId
                 WHERE tenant_id = :tenantId
                   AND compensation_cycle_id = :cycleInternalId
                   AND status = 'SUBMITTED'
                """;

    static final String CANCEL_28 = """
                UPDATE hris_performance.prf_cmp_cycles
                   SET aggregate_version = aggregate_version + 1,
                       status = 'CANCELLED', updated_at = :now,
                       correlation_id = :correlationId
                 WHERE tenant_id = :tenantId
                   AND prf_cmp_cycles_internal_id = :cycleInternalId
                   AND aggregate_version = :expectedVersion
                   AND status IN ('DRAFT', 'OPEN', 'PROPOSAL_REVIEW')
                """;

    static final String CANCEL_29 = """
                UPDATE hris_performance.prf_cmp_proposals
                   SET aggregate_version = aggregate_version + 1,
                       status = 'CANCELLED', updated_at = :now,
                       correlation_id = :correlationId
                 WHERE tenant_id = :tenantId
                   AND compensation_cycle_id = :cycleInternalId
                   AND status IN ('DRAFT', 'SUBMITTED')
                """;

    static final String RECEIPTS_30 = """
                SELECT receipt.command_receipt_id, receipt.idempotency_key,
                       receipt.command_type, receipt.originating_action,
                       receipt.subject_principal_public_id, receipt.aggregate_id,
                       receipt.expected_aggregate_version,
                       receipt.applied_aggregate_version, receipt.request_hash,
                       receipt.population_scope_digest,
                       receipt.field_policy_revision, receipt.purpose_code,
                       receipt.authorization_revision,
                       receipt.receipt_state, receipt.result_ref,
                       receipt.accepted_at, receipt.completed_at,
                       outbox.payload
                  FROM hris_performance.prf_command_receipts receipt
                  LEFT JOIN hris_performance.prf_outbox_events outbox
                    ON outbox.tenant_id = receipt.tenant_id
                   AND outbox.command_receipt_id = receipt.command_receipt_id
                """;

    static final String INSERT_REVISION_31 = """
                INSERT INTO hris_performance.prf_cmp_proposal_revisions (
                    tenant_id, public_id, aggregate_version,
                    created_at, created_by, updated_at, correlation_id,
                    compensation_proposal_id, revision_no,
                    typed_content_artifact_public_id, content_digest,
                    exception_approval_receipt_public_id)
                VALUES (
                    :tenantId, :revisionId, 1,
                    :now, :subjectId, :now, :correlationId,
                    :proposalInternalId, :revision,
                    :artifactId, :digest, NULL)
                """;
}
