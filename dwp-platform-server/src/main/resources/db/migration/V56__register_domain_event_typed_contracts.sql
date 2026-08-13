INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('CORE.DOMAIN_EVENT.BEGIN_STATE', 'dwp-core',
     'Domain event begin state',
     'Consumer acquisition outcome before a domain event handler is invoked.',
     'SYSTEM', 'TYPED_CONTRACT', 'DomainEventInboxRepository.BeginState', 'STATE_MACHINE'),
    ('CORE.DOMAIN_EVENT.FAILURE_STATE', 'dwp-core',
     'Domain event failure state',
     'Retry eligibility outcome after a domain event handler fails.',
     'SYSTEM', 'TYPED_CONTRACT', 'DomainEventInboxRepository.FailureState', 'STATE_MACHINE'),
    ('CORE.DOMAIN_EVENT.ORDERING_DECISION', 'dwp-core',
     'Domain event ordering decision',
     'Deterministic aggregate sequence decision for an inbound domain event.',
     'SYSTEM', 'TYPED_CONTRACT', 'DomainEventOrderingPolicy.Decision', 'PROTOCOL'),
    ('CORE.DOMAIN_EVENT.DELIVERY_STATE', 'dwp-core',
     'Domain event delivery state',
     'Public consumer outcome spanning processing, deduplication, retry, and quarantine.',
     'SYSTEM', 'TYPED_CONTRACT', 'IdempotentDomainEventConsumer.DeliveryState', 'STATE_MACHINE');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('CORE.DOMAIN_EVENT.BEGIN_STATE', 'ACQUIRED', 'Acquired',
     '{"ko":"처리권 획득","en":"Acquired"}', 10, '{"handlerInvoked":true}'),
    ('CORE.DOMAIN_EVENT.BEGIN_STATE', 'DUPLICATE', 'Duplicate',
     '{"ko":"중복","en":"Duplicate"}', 20, '{"terminal":true}'),
    ('CORE.DOMAIN_EVENT.BEGIN_STATE', 'OUT_OF_ORDER', 'Out of order',
     '{"ko":"순서 대기","en":"Out of order"}', 30, '{"deferred":true}'),
    ('CORE.DOMAIN_EVENT.BEGIN_STATE', 'DEFERRED', 'Deferred',
     '{"ko":"지연됨","en":"Deferred"}', 40, '{"deferred":true}'),
    ('CORE.DOMAIN_EVENT.BEGIN_STATE', 'BUSY', 'Busy',
     '{"ko":"처리 중","en":"Busy"}', 50, '{"leaseHeld":true}'),
    ('CORE.DOMAIN_EVENT.BEGIN_STATE', 'DEAD', 'Dead letter',
     '{"ko":"데드 레터","en":"Dead letter"}', 60, '{"terminal":true}'),
    ('CORE.DOMAIN_EVENT.BEGIN_STATE', 'PAYLOAD_CONFLICT', 'Payload conflict',
     '{"ko":"페이로드 충돌","en":"Payload conflict"}', 70, '{"quarantine":true}'),

    ('CORE.DOMAIN_EVENT.FAILURE_STATE', 'RETRYABLE', 'Retryable',
     '{"ko":"재시도 가능","en":"Retryable"}', 10, '{"terminal":false}'),
    ('CORE.DOMAIN_EVENT.FAILURE_STATE', 'DEAD', 'Dead letter',
     '{"ko":"데드 레터","en":"Dead letter"}', 20, '{"terminal":true}'),

    ('CORE.DOMAIN_EVENT.ORDERING_DECISION', 'ACCEPT', 'Accept',
     '{"ko":"수락","en":"Accept"}', 10, '{"processable":true}'),
    ('CORE.DOMAIN_EVENT.ORDERING_DECISION', 'DUPLICATE', 'Duplicate',
     '{"ko":"중복","en":"Duplicate"}', 20, '{"terminal":true}'),
    ('CORE.DOMAIN_EVENT.ORDERING_DECISION', 'OUT_OF_ORDER', 'Out of order',
     '{"ko":"순서 대기","en":"Out of order"}', 30, '{"deferred":true}'),

    ('CORE.DOMAIN_EVENT.DELIVERY_STATE', 'PROCESSED', 'Processed',
     '{"ko":"처리 완료","en":"Processed"}', 10, '{"terminal":true,"successful":true}'),
    ('CORE.DOMAIN_EVENT.DELIVERY_STATE', 'DUPLICATE', 'Duplicate',
     '{"ko":"중복","en":"Duplicate"}', 20, '{"terminal":true,"successful":true}'),
    ('CORE.DOMAIN_EVENT.DELIVERY_STATE', 'OUT_OF_ORDER', 'Out of order',
     '{"ko":"순서 대기","en":"Out of order"}', 30, '{"deferred":true}'),
    ('CORE.DOMAIN_EVENT.DELIVERY_STATE', 'DEFERRED', 'Deferred',
     '{"ko":"지연됨","en":"Deferred"}', 40, '{"deferred":true}'),
    ('CORE.DOMAIN_EVENT.DELIVERY_STATE', 'BUSY', 'Busy',
     '{"ko":"처리 중","en":"Busy"}', 50, '{"leaseHeld":true}'),
    ('CORE.DOMAIN_EVENT.DELIVERY_STATE', 'RETRY_SCHEDULED', 'Retry scheduled',
     '{"ko":"재시도 예약","en":"Retry scheduled"}', 60, '{"terminal":false}'),
    ('CORE.DOMAIN_EVENT.DELIVERY_STATE', 'DEAD', 'Dead letter',
     '{"ko":"데드 레터","en":"Dead letter"}', 70, '{"terminal":true}'),
    ('CORE.DOMAIN_EVENT.DELIVERY_STATE', 'QUARANTINED', 'Quarantined',
     '{"ko":"격리됨","en":"Quarantined"}', 80, '{"terminal":true,"manualReview":true}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('CORE.DOMAIN_EVENT.BEGIN_STATE', 'dwp-core', 'API_CONTRACT',
     'DomainEventInboxRepository.BeginState', 'TYPED_CONTRACT'),
    ('CORE.DOMAIN_EVENT.FAILURE_STATE', 'dwp-core', 'API_CONTRACT',
     'DomainEventInboxRepository.FailureState', 'TYPED_CONTRACT'),
    ('CORE.DOMAIN_EVENT.ORDERING_DECISION', 'dwp-core', 'BEHAVIOR',
     'DomainEventOrderingPolicy.Decision', 'TYPED_CONTRACT'),
    ('CORE.DOMAIN_EVENT.DELIVERY_STATE', 'dwp-core', 'API_CONTRACT',
     'IdempotentDomainEventConsumer.DeliveryState', 'TYPED_CONTRACT');
