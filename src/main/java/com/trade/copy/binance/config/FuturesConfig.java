package com.trade.copy.binance.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "binance.futures")
public class FuturesConfig {
	private String key;
	private String secret;
	private String baseUrl;
}
