package com.dwp.gateway.filter;

/** Exchange attribute keys shared by inbound guards and verified identity reconstruction. */
final class InboundAuthorityContextAttributes {
    static final String SYS006_INBOUND_CHECKED =
            "com.dwp.gateway.filter.Sys006InboundAuthorityFilter.inboundChecked";
    static final String SYS006_TENANT_SELECTOR =
            "com.dwp.gateway.filter.Sys006InboundAuthorityFilter.tenantSelector";
    static final String SYS006_VERIFIED_IDENTITY =
            "com.dwp.gateway.filter.Sys006InboundAuthorityFilter.verifiedIdentity";

    static final String SYS008_INBOUND_CHECKED =
            "com.dwp.gateway.filter.Sys008InboundAuthorityFilter.inboundChecked";
    static final String SYS008_TENANT_SELECTOR =
            "com.dwp.gateway.filter.Sys008InboundAuthorityFilter.tenantSelector";
    static final String SYS008_VERIFIED_IDENTITY =
            "com.dwp.gateway.filter.Sys008InboundAuthorityFilter.verifiedIdentity";

    private InboundAuthorityContextAttributes() {
    }
}
