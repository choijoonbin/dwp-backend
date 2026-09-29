# Performance migration stream

This classpath location is reserved for versioned migrations owned by the
Performance module. Keeping the location non-SQL allows the isolated Flyway
stream and its schema history to exist before the first module migration.
