package com.agrosense.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableSheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class AgrosenseApplication {

	public static void main(String[] args) {
		SpringApplication.run(AgrosenseApplication.class, args);
	}

}
