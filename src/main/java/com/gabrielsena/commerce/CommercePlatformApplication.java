package com.gabrielsena.commerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CommercePlatformApplication {

	public static void main(String[] args) {
		SpringApplication.run(CommercePlatformApplication.class, args);
	}

}
