ALTER TABLE prv_operation_approvals
    DROP CONSTRAINT prv_operation_approvals_operation_id_fkey,
    ADD CONSTRAINT fk_prv_operation_approvals_operation
        FOREIGN KEY (operation_id)
        REFERENCES prv_operations(operation_id)
        ON DELETE RESTRICT;

ALTER TABLE prv_service_incident_updates
    DROP CONSTRAINT prv_service_incident_updates_service_incident_id_fkey,
    ADD CONSTRAINT fk_prv_service_incident_updates_incident
        FOREIGN KEY (service_incident_id)
        REFERENCES prv_service_incidents(service_incident_id)
        ON DELETE RESTRICT;
