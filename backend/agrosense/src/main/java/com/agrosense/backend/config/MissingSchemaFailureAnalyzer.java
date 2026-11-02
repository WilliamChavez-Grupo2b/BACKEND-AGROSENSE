package com.agrosense.backend.config;

import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** The backend checks its tables at start-up; what fails there is almost always a migration nobody ran yet. */
public class MissingSchemaFailureAnalyzer extends AbstractFailureAnalyzer<SchemaManagementException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, SchemaManagementException cause) {
        return new FailureAnalysis("The database does not match this backend: " + cause.getMessage(),
                "Run the migrations in database/migrations in order (they can be repeated), or database/schema.sql on "
                        + "an empty database, and start again. See database/README.md.", cause);
    }
}
