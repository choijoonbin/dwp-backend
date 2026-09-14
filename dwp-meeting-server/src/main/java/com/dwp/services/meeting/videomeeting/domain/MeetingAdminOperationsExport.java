package com.dwp.services.meeting.videomeeting.domain;

import java.util.Objects;

/** An operations export whose content is isolated from both producer and consumer mutations. */
public record MeetingAdminOperationsExport(String filename, byte[] content) {

    public MeetingAdminOperationsExport {
        Objects.requireNonNull(filename, "filename");
        content = Objects.requireNonNull(content, "content").clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
