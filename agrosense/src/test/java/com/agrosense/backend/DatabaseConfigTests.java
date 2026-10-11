package com.agrosense.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks that deployments can supply datasource settings without a local properties file. */
class DatabaseConfigTests {

	@Test
	void localDatasourcePropertiesAreOptional() {
		StandardEnvironment environment = load();

		assertThat(environment.getProperty("spring.config.import")).contains("optional:file:");
	}

	@Test
	void seedProfileCanBeLoadedWithoutLocalDatasourceProperties() {
		StandardEnvironment environment = load("seed");

		assertThat(environment.getActiveProfiles()).contains("seed");
	}

	private static StandardEnvironment load(String... profiles) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.setActiveProfiles(profiles);
		ConfigDataEnvironmentPostProcessor.applyTo(environment, new DefaultResourceLoader(), null);
		return environment;
	}
}
