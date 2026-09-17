package com.dwp.services.platform.mail;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/mail")
public class MailAddressBookController {

    private final MailAddressBookService service;

    public MailAddressBookController(MailAddressBookService service) {
        this.service = service;
    }

    @GetMapping("/address-book")
    public ApiResponse<MailAddressBookDtos.AddressBook> addressBook(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return ApiResponse.success(service.addressBook(tenantId, userId, query, page, pageSize));
    }

    @PostMapping("/contacts")
    public ApiResponse<MailAddressBookDtos.Contact> createContact(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody MailAddressBookDtos.ContactCreateRequest request) {
        return ApiResponse.success(service.createContact(tenantId, userId, correlationId, request));
    }

    @PutMapping("/contacts/{contactId}")
    public ApiResponse<MailAddressBookDtos.Contact> updateContact(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID contactId,
            @Valid @RequestBody MailAddressBookDtos.ContactUpdateRequest request) {
        return ApiResponse.success(service.updateContact(
                tenantId, userId, contactId, correlationId, request));
    }

    @DeleteMapping("/contacts/{contactId}")
    public ResponseEntity<Void> deleteContact(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID contactId,
            @RequestParam long version) {
        service.deleteContact(tenantId, userId, contactId, version, correlationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/contact-groups")
    public ApiResponse<MailAddressBookDtos.ContactGroup> createGroup(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody MailAddressBookDtos.ContactGroupCreateRequest request) {
        return ApiResponse.success(service.createGroup(tenantId, userId, correlationId, request));
    }

    @PutMapping("/contact-groups/{groupId}")
    public ApiResponse<MailAddressBookDtos.ContactGroup> updateGroup(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID groupId,
            @Valid @RequestBody MailAddressBookDtos.ContactGroupUpdateRequest request) {
        return ApiResponse.success(service.updateGroup(
                tenantId, userId, groupId, correlationId, request));
    }

    @PutMapping("/contact-groups/{groupId}/members")
    public ApiResponse<MailAddressBookDtos.ContactGroup> replaceGroupMembers(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID groupId,
            @Valid @RequestBody MailAddressBookDtos.GroupMembersReplaceRequest request) {
        return ApiResponse.success(service.replaceMembers(
                tenantId, userId, groupId, correlationId, request));
    }

    @DeleteMapping("/contact-groups/{groupId}")
    public ResponseEntity<Void> deleteGroup(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID groupId,
            @RequestParam long version) {
        service.deleteGroup(tenantId, userId, groupId, version, correlationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/contact-groups/{groupId}/messages")
    public ApiResponse<MailAddressBookDtos.GroupSendResult> sendGroupMessage(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID groupId,
            @Valid @RequestBody MailAddressBookDtos.GroupMessageRequest request) {
        return ApiResponse.success(service.sendGroupMessage(
                tenantId, userId, groupId, correlationId, request));
    }

    @GetMapping("/contact-groups/{groupId}/messages/history")
    public ApiResponse<List<MailAddressBookDtos.GroupSendReceipt>> groupMessageHistory(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID groupId) {
        return ApiResponse.success(service.groupMessageHistory(tenantId, userId, groupId));
    }
}
