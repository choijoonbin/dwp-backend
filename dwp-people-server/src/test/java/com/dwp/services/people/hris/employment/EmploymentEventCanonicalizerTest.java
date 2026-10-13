package com.dwp.services.people.hris.employment;

import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.assignment.AssignmentProposalCanonicalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmploymentEventCanonicalizerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final EmploymentEventCanonicalizer canonicalizer =
            new EmploymentEventCanonicalizer(
                    mapper, new AssignmentProposalCanonicalizer(mapper));

    @Test
    void acceptsTheExactHrmPHireAndConcurrentEmploymentFixtures() throws Exception {
        EmploymentEventDtos.NormalizedCommand hire = canonicalizer.normalize(
                mapper.treeToValue(fixture("HIRE", "0", "1.0"),
                        EmploymentEventDtos.CreateCommand.class));
        EmploymentEventDtos.NormalizedCommand concurrent = canonicalizer.normalize(
                mapper.treeToValue(fixture("CONCURRENT_EMPLOYMENT", "8", "0.4"),
                        EmploymentEventDtos.CreateCommand.class));

        assertThat(hire.eventType()).isEqualTo("HIRE");
        assertThat(hire.personPublicId().toString())
                .isEqualTo("10000000-0000-4000-8000-000000000001");
        assertThat(hire.assignment().primary()).isTrue();
        assertThat(hire.assignment().fte()).isEqualByComparingTo("1.0");
        assertThat(concurrent.eventType()).isEqualTo("CONCURRENT_EMPLOYMENT");
        assertThat(concurrent.workerPublicId().toString())
                .isEqualTo("10000000-0000-4000-8000-000000000011");
        assertThat(concurrent.expectedOwnerVersion()).isEqualTo(8);
        assertThat(concurrent.assignment().validToExclusive().toString())
                .isEqualTo("2027-05-01");
    }

    @Test
    void rejectsNonCanonicalAndOutOfRangeFte() throws Exception {
        EmploymentEventDtos.CreateCommand negativeZero = mapper.treeToValue(
                fixture("HIRE", "0", "-0.0"), EmploymentEventDtos.CreateCommand.class);
        EmploymentEventDtos.CreateCommand tooLarge = mapper.treeToValue(
                fixture("HIRE", "0", "1.1"), EmploymentEventDtos.CreateCommand.class);

        assertThatThrownBy(() -> canonicalizer.normalize(negativeZero))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("canonical non-negative decimal");
        assertThatThrownBy(() -> canonicalizer.normalize(tooLarge))
                .isInstanceOf(EmploymentEventProblem.class)
                .extracting(error -> ((EmploymentEventProblem) error).code())
                .isEqualTo("FTE_OUT_OF_RANGE");
    }

    private JsonNode fixture(String type, String expectedVersion, String fte)
            throws Exception {
        boolean hire = "HIRE".equals(type);
        String subjectField = hire
                ? "\"personPublicId\":\"10000000-0000-4000-8000-000000000001\","
                : "\"workerPublicId\":\"10000000-0000-4000-8000-000000000011\","
                + "\"expectedWorkerVersion\":8,";
        String workerCreation = hire ? "\"workerCreation\":\"CREATE_NEW\"," : "";
        String employmentType = hire ? "\"employmentType\":\"EMPLOYEE\"," : "";
        String primary = hire ? "true" : "false";
        String end = hire ? "null" : "\"2027-05-01\"";
        String date = hire ? "2026-10-01" : "2026-11-01";
        String idempotency = hire ? "hire-tenant-100-0001" : "concurrent-tenant-100-01";
        String root = hire ? "00000000000" : "00000000001";
        return mapper.readTree("""
                {
                  "context":{"expectedVersion":%s,"idempotencyKey":"%s",
                    "purpose":"HR_LIFECYCLE","effectiveAt":"%sT00:00:00Z"},
                  "eventType":"%s",
                  "payload":{
                    %s
                    "legalEmployerRef":%s,
                    %s
                    %s
                    "relationshipStart":"%s",
                    "assignment":{
                      "organizationRef":%s,
                      "positionRef":%s,
                      "jobRef":%s,
                      "workLocationRef":%s,
                      "validFrom":"%s","validToExclusive":%s,
                      "primary":%s,"fte":"%s"
                    }
                  }
                }
                """.formatted(
                expectedVersion, idempotency, date, type, subjectField,
                ref("HRM.LegalEmployer.v1", root + "2", hire ? 7 : 2, 'a'),
                workerCreation, employmentType, date,
                ref("HRM.Organization.v1", root + "3", hire ? 4 : 1, 'b'),
                ref("HRM.Position.v1", root + "4", hire ? 2 : 1, 'c'),
                ref("HRM.Job.v1", root + "5", hire ? 3 : 1, 'd'),
                ref("HRM.WorkLocation.v1", root + "6", hire ? 5 : 1, 'e'),
                date, end, primary, fte));
    }

    private String ref(String schema, String suffix, long revision, char hash) {
        return "{\"owner\":\"HRM\",\"schemaId\":\"" + schema
                + "\",\"publicId\":\"10000000-0000-4000-8000-" + suffix
                + "\",\"revision\":" + revision + ",\"contentHash\":\""
                + String.valueOf(hash).repeat(64) + "\"}";
    }
}
