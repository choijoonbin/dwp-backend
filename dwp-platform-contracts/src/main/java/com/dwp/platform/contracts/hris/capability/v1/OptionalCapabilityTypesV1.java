package com.dwp.platform.contracts.hris.capability.v1;

/** Closed wire vocabulary for the provider-neutral optional-capability admission boundary. */
public final class OptionalCapabilityTypesV1 {
    private OptionalCapabilityTypesV1() {
    }

    public enum Module {
        HRIS_HRM("HRIS-HRM"),
        HRIS_PER("HRIS-PER"),
        HRIS_PAY("HRIS-PAY"),
        HRIS_TIM("HRIS-TIM"),
        HRIS_SYS("HRIS-SYS");

        private final String wireName;

        Module(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public enum BindingKind {
        TENANT_CONFIG,
        INSIGHTS_STORE,
        PROTECTED_STORE,
        CURRENT_ISSUER,
        PROVIDER_ADAPTER,
        CONNECTOR_MAPPING,
        CONNECTOR_CREDENTIAL,
        COUNTRY_RULE_PROVIDER
    }

    public enum InstallStatus {
        UNINSTALLED,
        DISABLED,
        ENABLED
    }

    public enum DecisionKind {
        ALLOW_AFFECTED_OPERATION(true),
        ALLOW_UNRELATED_CORE(true),
        ALLOW_MANUAL_PATH(true),
        DENY_UNKNOWN_CAPABILITY(false),
        DENY_UNINSTALLED(false),
        DENY_DISABLED(false),
        DENY_VERSION(false),
        DENY_MISSING_BINDING(false),
        DENY_MISSING_CAPABILITY(false),
        DENY_INVALID_REQUEST(false);

        private final boolean allowed;

        DecisionKind(boolean allowed) {
            this.allowed = allowed;
        }

        public boolean allowed() {
            return allowed;
        }
    }

    public enum MissingEffect {
        AFFECTED_OPERATION_ONLY,
        JOB_ONLY_MANUAL_PATH_PRESERVED,
        STATUTORY_ONLY_GENERIC_TIME_PRESERVED,
        STATUTORY_ONLY_GENERIC_PAYROLL_PRESERVED
    }

    public enum ActivationStage {
        G6
    }
}
