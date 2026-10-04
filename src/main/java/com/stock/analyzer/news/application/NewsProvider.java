package com.stock.analyzer.news.application;

import java.time.Instant;
import java.util.List;

import com.stock.analyzer.news.domain.NewsArticle;

public interface NewsProvider {

	boolean isConfigured();

	List<NewsArticle> fetchNews(String symbol, Instant updatedSince);
}
