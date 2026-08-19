package com.dwp.services.platform.mail;

public final class MailTypes {

    private MailTypes() {
    }

    public enum ProviderType {
        DWP_SANDBOX,
        MICROSOFT_GRAPH,
        GOOGLE_GMAIL,
        NAVER_WORKS,
        JMAP,
        IMAP_SMTP
    }

    public enum ConnectionState {
        ACTIVE,
        CONFIGURATION_REQUIRED,
        SYNCING,
        DEGRADED,
        SUSPENDED
    }

    public enum TriageLane {
        PRIORITY,
        NEEDS_REPLY,
        ASSIGNED,
        UPDATES,
        NEWSLETTERS
    }

    public enum WorkflowState {
        OPEN,
        DONE,
        SNOOZED,
        ARCHIVED,
        DRAFT
    }

    public enum Importance {
        LOW,
        NORMAL,
        HIGH,
        URGENT
    }

    public enum Classification {
        PUBLIC,
        INTERNAL,
        CONFIDENTIAL,
        RESTRICTED
    }

    public enum ThreadAction {
        MARK_READ,
        MARK_UNREAD,
        STAR,
        UNSTAR,
        ARCHIVE,
        RESTORE,
        COMPLETE,
        REOPEN
    }

    public enum DeliveryMode {
        SEND,
        DRAFT
    }

    public enum DeliveryState {
        RECEIVED,
        DRAFT,
        QUEUED,
        SENDING,
        RETRYING,
        SENT,
        FAILED
    }

    public enum AdapterRuntimeState {
        AVAILABLE,
        DEPLOYMENT_REQUIRED
    }

    public enum ProposalType {
        DRAFT_REPLY,
        CREATE_CALENDAR_EVENT,
        CREATE_LEAVE_REQUEST,
        CREATE_TASK,
        ESCALATE_NOTIFICATION
    }

    public enum ProposalStatus {
        PROPOSED,
        ACCEPTED,
        DISMISSED,
        EXPIRED,
        EXECUTED
    }

    public enum ProposalDecision {
        ACCEPT,
        DISMISS
    }
}
