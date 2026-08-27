package com.dwp.core.autoconfig;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("dwp.security.key-management")
public class DwpKeyManagementProperties {

    private boolean required;
    private String provider = "";
    private String keyReference = "";
    private String activeVersion = "";
    private String inlineKey = "";
    private Map<String, String> previousInlineKeys = new LinkedHashMap<>();
    private Path localRoot = Path.of(".dev-runtime", "keys");
    private List<String> previousVersions = new ArrayList<>();
    private String service = "";
    private String purpose = "";

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = value(provider);
    }

    public String getKeyReference() {
        return keyReference;
    }

    public void setKeyReference(String keyReference) {
        this.keyReference = value(keyReference);
    }

    public String getActiveVersion() {
        return activeVersion;
    }

    public void setActiveVersion(String activeVersion) {
        this.activeVersion = value(activeVersion);
    }

    public String getInlineKey() {
        return inlineKey;
    }

    public void setInlineKey(String inlineKey) {
        this.inlineKey = value(inlineKey);
    }

    public Map<String, String> getPreviousInlineKeys() {
        return previousInlineKeys;
    }

    public void setPreviousInlineKeys(Map<String, String> previousInlineKeys) {
        this.previousInlineKeys = previousInlineKeys == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(previousInlineKeys);
    }

    public Path getLocalRoot() {
        return localRoot;
    }

    public void setLocalRoot(Path localRoot) {
        this.localRoot = localRoot;
    }

    public List<String> getPreviousVersions() {
        return previousVersions;
    }

    public void setPreviousVersions(List<String> previousVersions) {
        this.previousVersions = previousVersions == null
                ? new ArrayList<>()
                : new ArrayList<>(previousVersions);
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = value(service);
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = value(purpose);
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }
}
