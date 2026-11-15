package com.agrosense.backend;

import com.agrosense.backend.domain.enums.AlertType;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

	@Autowired
	private javax.sql.DataSource dataSource;

	@Test
	void entitiesMatchTheSchemaScriptAndConstraintsAreEnforced() {
		assertEquals(11, jdbc.queryForObject(
				"select count(*) from information_schema.tables where table_schema = 'public'"
						+ " and table_name not like 'spring_session%'", Integer.class));
		assertEquals(2, jdbc.queryForObject(
				"select count(*) from information_schema.tables where table_schema = 'public'"
						+ " and table_name like 'spring_session%'", Integer.class));

		jdbc.update("insert into users (name, last_name, email, password_hash, role) values ('A', 'B', 'a@b.co', 'x', 'farmer')");
		jdbc.update("insert into estates (id_user, name) values (1, 'Estate')");
		jdbc.update("insert into crops (id_estate, name) values (1, 'Crop')");

		assertThrows(Exception.class, () -> jdbc.update(
				"insert into users (name, last_name, email, password_hash, role) values ('A', 'B', 'a@b.co', 'x', 'farmer')"));
		assertThrows(Exception.class, () -> jdbc.update("insert into crops (id_estate, name, stage) values (1, 'Bad', 'NOPE')"));
		assertThrows(Exception.class, () -> jdbc.update("insert into estates (id_user, name, latitude) values (1, 'Half', 1.2)"));
		assertThrows(Exception.class, () -> jdbc.update("insert into crops (id_estate, name) values (999, 'Orphan')"));

		jdbc.update("delete from users where id_user = 1");
		assertEquals(0, jdbc.queryForObject("select count(*) from crops", Integer.class));
	}

	/** run-demo.ps1 applies every migration at each start: none may reject an alert type that a later one allows. */
	@Test
	void everyMigrationCanBeRunAgainOnADatabaseThatHasEveryAlertType() throws IOException {
		jdbc.update("insert into users (name, last_name, email, password_hash, role) values ('M', 'G', 'mig@b.co', 'x', 'farmer')");
		Integer user = jdbc.queryForObject("select id_user from users where email = 'mig@b.co'", Integer.class);
		jdbc.update("insert into estates (id_user, name) values (?, 'Migrated')", user);
		Integer estate = jdbc.queryForObject("select id_estate from estates where id_user = ?", Integer.class, user);
		jdbc.update("insert into crops (id_estate, name) values (?, 'Migrated crop')", estate);
		Integer crop = jdbc.queryForObject("select id_crop from crops where id_estate = ?", Integer.class, estate);
		for (AlertType type : AlertType.values()) {
			jdbc.update("insert into alerts (id_crop, alert_type, severity, message) values (?, ?, 'MEDIUM', 'x')", crop,
					type.name());
		}

		ResourceDatabasePopulator migrations = new ResourceDatabasePopulator();
		try (Stream<Path> files = Files.list(Path.of("../../database/migrations"))) {
			files.filter(file -> file.toString().endsWith(".sql")).sorted()
					.forEach(file -> migrations.addScript(new FileSystemResource(file)));
		}
		migrations.execute(dataSource);
		migrations.execute(dataSource);

		assertEquals(AlertType.values().length, jdbc.queryForObject(
				"select count(*) from alerts where id_crop = ?", Integer.class, crop));
	}
}
