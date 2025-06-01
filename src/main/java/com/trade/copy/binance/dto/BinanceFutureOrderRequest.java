package com.trade.copy.binance.dto;

public record BinanceFutureOrderRequest(
	  String symbol,    // 예: BTCUSDT
	  String side,      // BUY or SELL
	  String type,      // MARKET or LIMIT
	  String quantity   // 예: 0.01
) {}
