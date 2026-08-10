package com.dwp.services.people.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class WorkdayReferenceMapper {

    static final String SAMPLE_RESOURCE = "samples/hris/workday-workers.synthetic.json";
    static final String MAPPING_RESOURCE = "samples/hris/workday-reference-mapping.json";

    private final ObjectMapper objectMapper;

    public WorkdayReferenceMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public HrisModels.WorkforceBatch mapSyntheticFixture() {
        return map(readResource(SAMPLE_RESOURCE));
    }

    public JsonNode mappingDefinition() {
        return readResource(MAPPING_RESOURCE);
    }

    HrisModels.WorkforceBatch map(JsonNode root) {
        JsonNode metadata = required(root, "Fixture_Metadata");
        List<HrisModels.WorkerRecord> workers = new ArrayList<>();
        for (JsonNode entry : required(root, "Report_Entry")) {
            JsonNode worker = required(entry, "Worker_Data");
            JsonNode personal = required(worker, "Personal_Data");
            JsonNode employment = required(worker, "Employment_Data");
            JsonNode employer = required(employment, "Legal_Employer");
            List<HrisModels.Assignment> assignments = new ArrayList<>();
            for (JsonNode assignment : required(worker, "Position_Data")) {
                JsonNode organization = required(assignment, "Supervisory_Organization");
                JsonNode job = required(assignment, "Job_Profile");
                JsonNode location = required(assignment, "Location");
                assignments.add(new HrisModels.Assignment(
                        text(assignment, "External_ID"),
                        text(assignment, "Source_Version"),
                        text(assignment, "Assignment_ID"),
                        enumValue(assignment, "Status"),
                        assignment.path("Primary").asBoolean(false),
                        date(assignment, "Effective_Date"),
                        optionalDate(assignment, "Effective_End_Date"),
                        text(assignment, "Business_Title"),
                        optionalText(assignment, "Manager_Assignment_ID"),
                        optionalText(assignment, "Cost_Center_ID"),
                        optionalText(assignment, "Change_Reason"),
                        new HrisModels.Organization(
                                text(organization, "ID"),
                                text(organization, "Name"),
                                enumValue(organization, "Type"),
                                optionalText(organization, "Parent_ID")),
                        new HrisModels.JobProfile(
                                text(job, "ID"),
                                text(job, "Name"),
                                optionalText(job, "Family_ID"),
                                optionalText(job, "Management_Level")),
                        new HrisModels.Location(
                                text(location, "ID"),
                                text(location, "Name"),
                                optionalText(location, "Country_Code"),
                                optionalText(location, "Time_Zone")),
                        new HrisModels.Position(
                                text(assignment, "Position_ID"),
                                text(assignment, "Business_Title"))));
            }
            workers.add(new HrisModels.WorkerRecord(
                    text(entry, "Worker_External_ID"),
                    text(entry, "Source_Version"),
                    text(worker, "Worker_ID"),
                    enumValue(employment, "Worker_Type"),
                    enumValue(employment, "Worker_Status"),
                    text(personal, "Display_Name"),
                    text(personal, "Given_Name"),
                    text(personal, "Family_Name"),
                    optionalText(personal, "Preferred_Locale"),
                    optionalText(personal, "Time_Zone"),
                    optionalText(required(worker, "Contact_Data"), "Work_Email"),
                    date(employment, "Original_Hire_Date"),
                    new HrisModels.Employer(
                            text(employer, "ID"),
                            text(employer, "Legal_Name"),
                            optionalText(employer, "Country_Code")),
                    List.copyOf(assignments)));
        }
        return new HrisModels.WorkforceBatch(
                text(metadata, "Source_Key"),
                text(metadata, "Source_Type"),
                text(metadata, "Source_Schema_Version"),
                text(metadata, "Watermark"),
                metadata.path("Synthetic").asBoolean(false),
                List.copyOf(workers));
    }

    private JsonNode readResource(String resource) {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Resource not found: " + resource);
            return objectMapper.readTree(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("HRIS reference resource could not be read.", exception);
        }
    }

    private JsonNode required(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw invalid("Missing required HRIS field: " + field);
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) throw invalid("Missing required HRIS field: " + field);
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null
                : value.asText().trim();
    }

    private String enumValue(JsonNode node, String field) {
        return text(node, field).trim().toUpperCase(java.util.Locale.ROOT);
    }

    private LocalDate date(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException exception) {
            throw invalid("Invalid HRIS date field: " + field);
        }
    }

    private LocalDate optionalDate(JsonNode node, String field) {
        String value = optionalText(node, field);
        return value == null ? null : LocalDate.parse(value);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
