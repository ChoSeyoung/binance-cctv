package com.trade.copy.binance.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * application.yml 의 binance.futures 아래 프로퍼티를 읽어오는 클래스
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "binance.futures")
public class BinanceProperties {

	/**
	 * Base URL (예: https://fapi.binance.com)
	 */
	private String baseUrl;

	/**
	 * API Key
	 */
	private String key;

	/**
	 * Secret Key
	 */
	private String secret;

	/**
	 * 마진 타입 (예: CROSSED, ISOLATED)
	 */
	private String marginType;

	/**
	 * 기본 레버리지 (예: 10)
	 */
	private int defaultLeverage;

	/**
	 * 커미션 비율 (예: 0.001 == 0.1%)
	 */
	private double commissionRate;

	/**
	 * 목표 프로핏 퍼센트 (예: 0.004 == 0.4%)
	 */
	private double targetProfitPercent;

	/**
	 * recvWindow (예: 5000L)
	 */
	private long recvWindow;
}