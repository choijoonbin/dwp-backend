package com.dwp.services.provider.governance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "dwp.data-governance")
public class DataGovernanceProperties {

    private Duration cacheTtl = Duration.ofSeconds(30);
    private int queryTimeoutSeconds = 10;
    private List<Source> sources = new ArrayList<>();

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public List<Source> getSources() {
        return List.copyOf(sources);
    }

    public void setSources(List<Source> sources) {
        this.sources = new ArrayList<>(sources == null ? List.of() : sources);
    }

    public static class Source {
        private String key;
        private String databaseName;
        private String displayName;
        private String ownerService;
        private String jdbcUrl;
        private String username;
        private String password;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getDatabaseName() {
            return databaseName;
        }

        public void setDatabaseName(String databaseName) {
            this.databaseName = databaseName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getOwnerService() {
            return ownerService;
        }

        public void setOwnerService(String ownerService) {
            this.ownerService = ownerService;
        }

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public void setJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
