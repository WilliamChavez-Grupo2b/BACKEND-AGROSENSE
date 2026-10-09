package com.agrosense.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:agrosense;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"cors.allowed-origins=",
		"jwt.secret=test-secret-that-is-at-least-32-bytes-long"
})
class AgrosenseApplicationTests {

	@Test
	void contextLoads() {
	}

}
