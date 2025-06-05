package com.trade.copy.binance.dto;

import lombok.Data;

@Data
public class ProfitEvaluationResult {
	private final boolean shouldTakeProfit;
	private final String side;
	private final double entryPrice;
	private final double markPrice;
	private final double targetPrice;
}
