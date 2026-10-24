package com.stock.analyzer.configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AnalysisConfiguration {

	@Bean(destroyMethod = "close")
	@Primary
	ExecutorService analysisExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}

	@Bean(name = "batchItemExecutor", destroyMethod = "close")
	ExecutorService batchItemExecutor(ConcurrencyProperties properties) {
		properties.validate();
		int concurrency = properties.getBatchMaxConcurrentItems();
		return new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(properties.getBatchQueueCapacity()),
				Thread.ofVirtual().name("batch-item-", 0).factory(), (task, pool) -> {
					if (pool.isShutdown()) throw new RejectedExecutionException("Batch item executor is shutting down");
					try { pool.getQueue().put(task); }
					catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						throw new RejectedExecutionException("Interrupted while queueing a batch item", exception);
					}
				});
	}
}
