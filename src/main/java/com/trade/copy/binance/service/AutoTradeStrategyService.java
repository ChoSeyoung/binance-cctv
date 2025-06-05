package com.trade.copy.binance.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AutoTradeStrategyService {

	private final BinanceFutureTradeService binanceService;

	public void setPosition(String symbol) throws Exception {
		// 1. 열려있는 주문 모두 취소
//		binanceService.cancelAllOpenOrders(symbol);

		// 2. 현재 포지션 확인
		boolean hasPosition = binanceService.hasOpenPosition(symbol);
		if (hasPosition) return;

		// 3. 포지션 없으면 RSI 진입 조건 체크
		boolean shouldEnter = binanceService.evaluateRsiEntry(symbol);
		if (shouldEnter) {
			binanceService.openMarketPosition(symbol, "BUY", null);
		}
	}

	public void takeProfit(String symbol) throws Exception {
		// 2. 현재 포지션 확인
		boolean hasPosition = binanceService.hasOpenPosition(symbol);

		if (hasPosition) {
			boolean shouldTakeProfit = binanceService.evaluateProfitTarget(symbol);
			if (shouldTakeProfit) {
				// 1. 열려있는 주문 모두 취소
				binanceService.cancelAllOpenOrders(symbol);

				binanceService.closePositionMarket(symbol);
			}
		}
	}
}
