package com.dwp.services.synapsex.service.admin;

import com.dwp.services.synapsex.dto.admin.PiiFieldCatalogDto;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PII 필드 카탈로그 (tenant-agnostic, product catalog).
 */
@Service
public class PiiFieldCatalogService {

    private static final List<PiiFieldCatalogDto.PiiFieldCatalogItem> CATALOG = List.of(
            PiiFieldCatalogDto.PiiFieldCatalogItem.builder()
                    .fieldKey("IBAN")
                    .label("IBAN")
                    .description("국제 은행 계좌 번호")
                    .dataDomain("FINANCIAL")
                    .defaultHandling("MASK")
                    .supportsMask(true)
                    .supportsHash(true)
                    .supportsEncrypt(true)
                    .supportsVault(false)
                    .sampleMaskedFormat("DE89****1234")
                    .build(),
            PiiFieldCatalogDto.PiiFieldCatalogItem.builder()
                    .fieldKey("BANK_ACCOUNT")
                    .label("Bank Account")
                    .description("은행 계좌 번호")
                    .dataDomain("FINANCIAL")
                    .defaultHandling("MASK")
                    .supportsMask(true)
                    .supportsHash(true)
                    .supportsEncrypt(true)
                    .supportsVault(false)
                    .sampleMaskedFormat("****-****-1234")
                    .build(),
            PiiFieldCatalogDto.PiiFieldCatalogItem.builder()
                    .fieldKey("EMAIL")
                    .label("Email")
                    .description("이메일 주소")
                    .dataDomain("CONTACT")
                    .defaultHandling("MASK")
                    .supportsMask(true)
                    .supportsHash(true)
                    .supportsEncrypt(true)
                    .supportsVault(false)
                    .sampleMaskedFormat("u***@***.com")
                    .build(),
            PiiFieldCatalogDto.PiiFieldCatalogItem.builder()
                    .fieldKey("PHONE")
                    .label("Phone")
                    .description("전화번호")
                    .dataDomain("CONTACT")
                    .defaultHandling("MASK")
                    .supportsMask(true)
                    .supportsHash(true)
                    .supportsEncrypt(true)
                    .supportsVault(false)
                    .sampleMaskedFormat("010-****-5678")
                    .build(),
            PiiFieldCatalogDto.PiiFieldCatalogItem.builder()
                    .fieldKey("TAX_ID")
                    .label("Tax ID")
                    .description("세금 관련 식별자")
                    .dataDomain("FINANCIAL")
                    .defaultHandling("MASK")
                    .supportsMask(true)
                    .supportsHash(true)
                    .supportsEncrypt(true)
                    .supportsVault(true)
                    .sampleMaskedFormat("***-**-*****")
                    .build(),
            PiiFieldCatalogDto.PiiFieldCatalogItem.builder()
                    .fieldKey("NAME")
                    .label("Name")
                    .description("성명")
                    .dataDomain("IDENTITY")
                    .defaultHandling("MASK")
                    .supportsMask(true)
                    .supportsHash(true)
                    .supportsEncrypt(true)
                    .supportsVault(false)
                    .sampleMaskedFormat("홍*동")
                    .build(),
            PiiFieldCatalogDto.PiiFieldCatalogItem.builder()
                    .fieldKey("ADDRESS")
                    .label("Address")
                    .description("주소")
                    .dataDomain("IDENTITY")
                    .defaultHandling("MASK")
                    .supportsMask(true)
                    .supportsHash(true)
                    .supportsEncrypt(true)
                    .supportsVault(false)
                    .sampleMaskedFormat("서울시 ***구 ***동")
                    .build()
    );

    public PiiFieldCatalogDto getCatalog() {
        return PiiFieldCatalogDto.builder().fields(CATALOG).build();
    }
}
