package com.dwp.services.synapsex.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 로컬 RAG 문서 저장 경로. 서버 기동 시 디렉토리가 없으면 자동 생성.
 */
@Slf4j
@Configuration
public class LocalStorageConfig {

    @Value("${storage.local.path:./storage/rag-documents}")
    private String localPath;

    @PostConstruct
    public void ensureStorageDirectory() {
        try {
            Path path = Paths.get(localPath).toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                log.info("Created local RAG storage directory: {}", path);
            } else {
                log.debug("Local RAG storage directory exists: {}", path);
            }
        } catch (Exception e) {
            log.warn("Could not create local RAG storage directory {}: {}", localPath, e.getMessage());
        }
    }

    public String getLocalPath() {
        return localPath;
    }

    /** 절대 경로 반환 (파일 저장 시 사용). */
    public Path getAbsolutePath() {
        return Paths.get(localPath).toAbsolutePath().normalize();
    }
}
