package com.dwp.services.messaging.attachment;

public interface AttachmentStorage {

    void store(String objectKey, byte[] content);

    byte[] load(String objectKey);

    void delete(String objectKey);
}
