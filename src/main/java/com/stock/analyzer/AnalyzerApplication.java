package com.stock.analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AnalyzerApplication {

	public static void main(String[] args) {
		//System.out.println("JVM timezone = " + java.util.TimeZone.getDefault().getID());
		SpringApplication.run(AnalyzerApplication.class, args);
	}

}
