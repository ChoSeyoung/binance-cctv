package com.trade.copy.binance.schedule;

import com.trade.copy.binance.service.AutoTradeStrategyService;
import com.trade.copy.binance.service.BinanceFutureTradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.HashSet;

@Component
@RequiredArgsConstructor
public class AutoTradeScheduler {

	private final AutoTradeStrategyService strategyService;
	private final BinanceFutureTradeService binanceFutureTradeService;

	// 2025년 6월 1일 기준 섹터별 시가총액 1위 종목
	private static final List<String> SYMBOLS = List.of(
		  "BTCUSDT",
		  "ETHUSDT",
		  "XRPUSDT",
		  "SOLUSDT",
		  "SUIUSDT"
	);

	// 매 15분마다 실행
	@Scheduled(cron = "* */15 * * * *")
	public void setPosition() {
		for (String symbol : SYMBOLS) {
			try {
				strategyService.setPosition(symbol);
			} catch (Exception e) {
				System.err.println("🔴 자동매매 실패 [" + symbol + "]: " + e.getMessage());
			}
		}
	}

	// 매 1분마다 실행
	@Scheduled(cron = "0 * * * * *")
	public void takeProfit() {
		try {
			// SYMBOLS + 현재 보유 중인 포지션 심볼을 합쳐 중복 없이 처리
			Set<String> allSymbols = new HashSet<>(SYMBOLS);
			allSymbols.addAll(binanceFutureTradeService.getOpenPositionSymbols());

			for (String symbol : allSymbols) {
				try {
					strategyService.takeProfit(symbol);
				} catch (Exception e) {
					System.err.println("🔴 익절 실패 [" + symbol + "]: " + e.getMessage());
				}
			}
		} catch (Exception e) {
			System.err.println("🔴 포지션 심볼 조회 실패: " + e.getMessage());
		}
	}
}
