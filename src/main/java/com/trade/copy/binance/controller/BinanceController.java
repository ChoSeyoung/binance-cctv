package com.trade.copy.binance.controller;

import com.trade.copy.binance.service.BinanceFutureTradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/binance")
@RequiredArgsConstructor
public class BinanceController {

	private final BinanceFutureTradeService tradeService;

	@GetMapping("/hasOpen/{symbol}")
	public String checkPosition(@PathVariable String symbol) {
		try {
			boolean open = tradeService.hasOpenPosition(symbol);
			return open ? "포지션 보유 중" : "포지션 없음";
		} catch (Exception e) {
			return "에러 발생: " + e.getMessage();
		}
	}

	@PostMapping("/cancelAll/{symbol}")
	public String cancelAll(@PathVariable String symbol) {
		try {
			tradeService.cancelAllOpenOrders(symbol);
			return "모든 주문 취소 요청 완료";
		} catch (Exception e) {
			return "에러 발생: " + e.getMessage();
		}
	}

	@PostMapping("/evaluateProfit/{symbol}")
	public String evaluateProfit(@PathVariable String symbol) {
		try {
			boolean shouldTake = tradeService.evaluateProfitTarget(symbol);
			return shouldTake ? "익절 조건 만족" : "익절 조건 미충족";
		} catch (Exception e) {
			return "에러 발생: " + e.getMessage();
		}
	}

	@PostMapping("/close/{symbol}")
	public String closePosition(@PathVariable String symbol) {
		try {
			tradeService.closePositionMarket(symbol);
			return "청산 로직 실행 완료";
		} catch (Exception e) {
			return "에러 발생: " + e.getMessage();
		}
	}

	@GetMapping("/rsi/{symbol}")
	public String checkRsi(@PathVariable String symbol) {
		try {
			boolean entry = tradeService.evaluateRsiEntry(symbol);
			return entry ? "RSI 진입 조건 만족" : "RSI 진입 조건 미충족";
		} catch (Exception e) {
			return "에러 발생: " + e.getMessage();
		}
	}

	@PostMapping("/open/{symbol}/{side}")
	public String openPosition(@PathVariable String symbol, @PathVariable String side,
							   @RequestParam(required = false) String quantity) {
		try {
			tradeService.openMarketPosition(symbol, side, quantity);
			return "시장가 주문 요청 완료";
		} catch (Exception e) {
			return "에러 발생: " + e.getMessage();
		}
	}
}