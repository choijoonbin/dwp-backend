package com.dwp.core.database;

/** Pins Hibernate metadata discovery to the protected application schema. */
public final class HibernateSchemaConfigurationGuard {

    private HibernateSchemaConfigurationGuard() {
    }

    public static void verifyExact(
            String serviceName,
            String actualSchema,
            String expectedSchema) {
        requireText("serviceName", serviceName);
        requireText("expectedSchema", expectedSchema);
        if (!expectedSchema.equals(actualSchema)) {
            throw new IllegalArgumentException(
                    serviceName + " spring.jpa.properties.hibernate.default_schema must be "
                            + expectedSchema + ", got "
                            + (actualSchema == null || actualSchema.isBlank()
                                    ? "<unset>" : actualSchema));
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
