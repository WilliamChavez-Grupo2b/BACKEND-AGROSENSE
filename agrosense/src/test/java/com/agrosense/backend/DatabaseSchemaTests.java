package com.agrosense.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Applies database/schema.sql and lets Hibernate validate every entity against it, so the SQL script and
 * the JPA mappings cannot drift apart. H2 runs in PostgreSQL compatibility mode.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:agrosense-schema;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.sql.init.mode=always",
		"spring.sql.init.schema-locations=file:../../database/schema.sql",
		"spring.jpa.hibernate.ddl-auto=validate",
		"cors.allowed-origins=",
		"jwt.secret=test-secret-that-is-at-least-64-bytes-long-for-hs512-signatures-0123456789"
})
class DatabaseSchemaTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void entitiesMatchTheSchemaScriptAndConstraintsAreEnforced() {
		// Reaching this point means Hibernate validated all eight entities against schema.sql.
		assertEquals(8, jdbc.queryForObject(
				"select count(*) from information_schema.tables where table_schema = 'public'", Integer.class));

		jdbc.update("insert into users (name, last_name, email, password_hash, role) values ('A', 'B', 'a@b.co', 'x', 'farmer')");
		jdbc.update("insert into estates (id_user, name) values (1, 'Estate')");
		jdbc.update("insert into crops (id_estate, name) values (1, 'Crop')");

		assertThrows(Exception.class, () -> jdbc.update(
				"insert into users (name, last_name, email, password_hash, role) values ('A', 'B', 'a@b.co', 'x', 'farmer')"));
		assertThrows(Exception.class, () -> jdbc.update("insert into crops (id_estate, name, stage) values (1, 'Bad', 'NOPE')"));
		assertThrows(Exception.class, () -> jdbc.update("insert into estates (id_user, name, latitude) values (1, 'Half', 1.2)"));
		assertThrows(Exception.class, () -> jdbc.update("insert into crops (id_estate, name) values (999, 'Orphan')"));

		// Deleting the user removes everything it owns.
		jdbc.update("delete from users where id_user = 1");
		assertEquals(0, jdbc.queryForObject("select count(*) from crops", Integer.class));
	}
}
