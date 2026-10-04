package com.stock.analyzer.configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AnalysisConfiguration {

	@Bean(destroyMethod = "close")
	ExecutorService analysisExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}
}
