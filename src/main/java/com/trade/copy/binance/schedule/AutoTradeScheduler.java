package com.trade.copy.binance.schedule;

import com.trade.copy.binance.service.AutoTradeStrategyService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AutoTradeScheduler {

	private final AutoTradeStrategyService strategyService;

	// 2025년 6월 1일 기준 섹터별 시가총액 1위 종목
	private static final List<String> SYMBOLS = List.of(
		  "BTCUSDT",
		  "ETHUSDT",
		  "XRPUSDT",
		  "BNBUSDT",
		  "SOLUSDT"
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
		for (String symbol : SYMBOLS) {
			try {
				strategyService.takeProfit(symbol);
			} catch (Exception e) {
				System.err.println("🔴 익절 실패 [" + symbol + "]: " + e.getMessage());
			}
		}
	}
}
