package com.dwp.services.platform.mail;

final class MailOrganizationTypes {

    private MailOrganizationTypes() {
    }

    enum FolderColor {
        NEUTRAL,
        BLUE,
        TEAL,
        GREEN,
        AMBER,
        CORAL,
        VIOLET
    }

    enum ProviderSyncState {
        LOCAL_ONLY,
        PENDING,
        SYNCED,
        ERROR
    }

    enum RuleMatchMode {
        ALL,
        ANY
    }

    enum RuleField {
        SENDER,
        RECIPIENT,
        SUBJECT,
        BODY,
        HAS_ATTACHMENT,
        IMPORTANCE
    }

    enum RuleOperator {
        CONTAINS,
        EQUALS,
        STARTS_WITH,
        ENDS_WITH,
        IS
    }

    enum RuleActionType {
        MOVE_TO_FOLDER,
        MARK_READ,
        STAR,
        SET_IMPORTANCE
    }

    enum LifecycleAction {
        MOVE,
        ARCHIVE,
        TRASH,
        SPAM,
        RESTORE,
        DELETE_FOREVER
    }
}
