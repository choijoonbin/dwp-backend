package com.dwp.services.approval.formsv3;

import static com.dwp.services.approval.formsv3.ApprovalFormV3TestFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.dwp.core.exception.BaseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApprovalFormSchemaV3CompilerTest {
    private final ApprovalFormSchemaV3Compiler compiler = new ApprovalFormSchemaV3Compiler();

    @Test
    void compilesEveryEnterpriseFieldAndControlFamilyDeterministically() {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(ApprovalFormV3TestFixtures.field("summary", "TEXTAREA", "TEXTAREA", true));
        List<List<String>> pairs = List.of(
                List.of("text", "TEXT", "TEXT_INPUT"), List.of("rich", "RICH_TEXT", "RICH_TEXT_EDITOR"),
                List.of("email", "EMAIL", "EMAIL_INPUT"), List.of("phone", "PHONE", "PHONE_INPUT"),
                List.of("url", "URL", "URL_INPUT"), List.of("number", "NUMBER", "NUMBER_INPUT"),
                List.of("currency", "CURRENCY", "CURRENCY_INPUT"), List.of("percent", "PERCENT", "PERCENT_INPUT"),
                List.of("date", "DATE", "DATE_PICKER"), List.of("dateTime", "DATETIME", "DATETIME_PICKER"),
                List.of("time", "TIME", "TIME_PICKER"), List.of("boolean", "BOOLEAN", "SWITCH"),
                List.of("user", "USER", "USER_PICKER"), List.of("people", "PEOPLE", "PEOPLE_PICKER"),
                List.of("organization", "ORGANIZATION", "ORGANIZATION_PICKER"),
                List.of("group", "GROUP", "GROUP_PICKER"), List.of("lookup", "LOOKUP", "LOOKUP"),
                List.of("attachment", "ATTACHMENT", "FILE_UPLOAD"), List.of("signature", "SIGNATURE", "SIGNATURE_REQUEST"),
                List.of("address", "ADDRESS", "ADDRESS_INPUT"), List.of("heading", "HEADING", "HEADING"),
                List.of("paragraph", "PARAGRAPH", "PARAGRAPH"), List.of("divider", "DIVIDER", "DIVIDER"),
                List.of("calculatedText", "CALCULATED_TEXT", "CALCULATED_TEXT"));
        for (List<String> pair : pairs) fields.add(ApprovalFormV3TestFixtures.field(pair.get(0), pair.get(1), pair.get(2), false));
        fields.add(selection("single", "SINGLE_SELECT", "RADIO"));
        fields.add(selection("multi", "MULTI_SELECT", "CHECKBOX_GROUP"));
        Map<String, Object> table = ApprovalFormV3TestFixtures.field("lines", "TABLE", "TABLE_EDITOR", false);
        table.put("columns", List.of(ApprovalFormV3TestFixtures.field("lineName", "TEXT", "TEXT_INPUT", true),
                ApprovalFormV3TestFixtures.field("lineAmount", "NUMBER", "NUMBER_INPUT", true)));
        fields.add(table);
        Map<String, Object> repeating = ApprovalFormV3TestFixtures.field("contacts", "REPEATING_GROUP", "REPEATING_GROUP", false);
        repeating.put("columns", List.of(ApprovalFormV3TestFixtures.field("contactName", "TEXT", "TEXT_INPUT", true)));
        fields.add(repeating);
        fields.add(ApprovalFormV3TestFixtures.field("calculatedNumber", "CALCULATED_NUMBER", "CALCULATED_NUMBER", false));
        List<Map<String, Object>> calculations = List.of(
                map("key", "calcNumber", "target", "calculatedNumber", "expression", map("op", "CONST", "value", "1")),
                map("key", "calcText", "target", "calculatedText", "expression", map("op", "CONST", "value", "ready")));

        ApprovalFormSchemaV3 compiled = compiler.compile(schema("INITIAL", null, fields, calculations, List.of(), List.of()));

        assertThat(compiled.fieldKeys()).contains("summary", "lines", "lineAmount", "contacts", "calculatedNumber");
        assertThat(compiled.sha256()).matches("[a-f0-9]{64}");
        assertThat(compiler.compile(compiled.definition()).sha256()).isEqualTo(compiled.sha256());
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsOnlyVaultCredentialReferencesAndRejectsLiteralSecrets() {
        Map<String, Object> valid = enterpriseSchema();
        field(valid, "country").put("dataSource", map("providerKey", "REFERENCE_DATA", "resource", "countries/active",
                "credentialRef", "vault://approval/reference-data", "valuePath", "code", "labelPath", "name", "cacheTtlSeconds", 300));
        field(valid, "country").put("options", List.of());
        assertThatCode(() -> compiler.compile(valid)).doesNotThrowAnyException();

        Map<String, Object> secret = copy(valid);
        field(secret, "country").put("apiKey", "plain-text");
        assertThatThrownBy(() -> compiler.compile(secret)).isInstanceOf(BaseException.class);

        Map<String, Object> invalidRef = copy(valid);
        ((Map<String, Object>) field(invalidRef, "country").get("dataSource")).put("credentialRef", "https://secret");
        assertThatThrownBy(() -> compiler.compile(invalidRef)).isInstanceOf(BaseException.class);
    }

    @Test
    void rejectsUnsafePatternsCrossScopeCalculationCyclesAndRoleLeaks() {
        Map<String, Object> unsafe = enterpriseSchema();
        field(unsafe, "summary").put("validation", map("pattern", "(a+)+"));
        assertThatThrownBy(() -> compiler.compile(unsafe)).isInstanceOf(BaseException.class);

        Map<String, Object> cycle = enterpriseSchema();
        cycle.put("calculations", new ArrayList<Map<String, Object>>());
        calculations(cycle).add(map("key", "one", "target", "total", "expression", map("op", "FIELD", "field", "calculatedText")));
        fields(cycle).add(ApprovalFormV3TestFixtures.field("calculatedText", "CALCULATED_TEXT", "CALCULATED_TEXT", false));
        calculations(cycle).add(map("key", "two", "target", "calculatedText", "expression", map("op", "FIELD", "field", "total")));
        assertThatThrownBy(() -> compiler.compile(cycle)).isInstanceOf(BaseException.class);

        Map<String, Object> roleLeak = enterpriseSchema();
        field(roleLeak, "amount").put("viewRoles", List.of("FINANCE_VIEWER"));
        field(roleLeak, "amount").put("editRoles", List.of("FINANCE_EDITOR"));
        assertThatThrownBy(() -> compiler.compile(roleLeak)).isInstanceOf(BaseException.class);
    }

    @Test
    void compatibilityDetectsRemovedFieldsNarrowerRolesAndWrongBaseHash() {
        ApprovalFormSchemaV3 base = compiler.compile(enterpriseSchema());
        Map<String, Object> candidate = enterpriseSchema();
        candidate.put("compatibility", map("mode", "BACKWARD_COMPATIBLE", "minimumRuntime", "3.0",
                "baseSchemaSha256", base.sha256()));
        fields(candidate).removeIf(field -> "country".equals(field.get("key")));
        candidate.put("scenarios", List.of());
        field(candidate, "amount").put("viewRoles", List.of("FINANCE_VIEWER"));
        field(candidate, "amount").put("editRoles", List.of("FINANCE_VIEWER"));
        ApprovalFormSchemaV3.CompatibilityReport report = compiler.compare(base, compiler.compile(candidate));
        assertThat(report.compatible()).isFalse();
        assertThat(report.changes()).extracting(ApprovalFormSchemaV3.CompatibilityChange::code)
                .contains("FIELD_REMOVED", "ROLE_ACCESS_NARROWED");

        Map<String, Object> wrongBase = enterpriseSchema();
        wrongBase.put("compatibility", map("mode", "STRICT", "minimumRuntime", "3.0", "baseSchemaSha256", "f".repeat(64)));
        assertThat(compiler.compare(base, compiler.compile(wrongBase)).compatible()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fields(Map<String, Object> schema) {
        Map<String, Object> page = (Map<String, Object>) ((List<?>) schema.get("pages")).getFirst();
        Map<String, Object> section = (Map<String, Object>) ((List<?>) page.get("sections")).getFirst();
        return (List<Map<String, Object>>) section.get("fields");
    }

    private Map<String, Object> field(Map<String, Object> schema, String key) {
        return fields(schema).stream().filter(field -> key.equals(field.get("key"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> calculations(Map<String, Object> schema) {
        return (List<Map<String, Object>>) schema.get("calculations");
    }
}
