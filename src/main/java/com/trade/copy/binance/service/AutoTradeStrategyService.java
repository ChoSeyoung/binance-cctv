package com.trade.copy.binance.service;

import com.trade.copy.binance.dto.ProfitEvaluationResult;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AutoTradeStrategyService {

	private final BinanceFutureTradeService binanceService;

	/**
	 * 현재 포지션이 없는 경우, RSI 진입 조건을 평가하여
	 * 조건을 만족하면 시장가로 BUY 포지션을 오픈합니다.
	 * 진입 조건:
	 * - 포지션이 없는 상태
	 * - RSI가 30 미만이면서 최근 저점이 이전 저점보다 높을 경우
	 *
	 * @param symbol 거래 페어 (예: "BTCUSDT")
	 * @throws Exception API 호출 또는 내부 로직 처리 중 오류가 발생한 경우
	 */
	public void setPosition(String symbol) throws Exception {
		// 1. 현재 포지션 확인
		boolean hasPosition = binanceService.hasOpenPosition(symbol);
		if (hasPosition) return;

		// 2. 포지션 없으면 RSI 진입 조건 체크
		boolean shouldEnter = binanceService.evaluateRsiEntry(symbol);
		if (shouldEnter) {
			binanceService.openMarketPosition(symbol, "BUY", null);
		}
	}

	/**
	 * 보유 중인 포지션에 대해 익절 조건을 평가하고,
	 * 조건을 만족하는 경우 시장가로 포지션을 청산합니다.
	 * 실행 절차:
	 * 1. 포지션 보유 여부 확인
	 * 2. 익절 조건 평가 (목표 수익률, 수수료, 슬리피지 고려)
	 * 3. 익절 조건 충족 시:
	 * - 모든 미체결 주문 취소
	 * - 현재 포지션 시장가로 청산
	 *
	 * @param symbol 거래 페어 (예: "BTCUSDT")
	 * @throws Exception API 호출 또는 내부 로직 처리 중 오류가 발생한 경우
	 */
	public void takeProfit(String symbol) throws Exception {
		// 1. 현재 포지션 보유 여부 확인
		boolean hasPosition = binanceService.hasOpenPosition(symbol);

		if (hasPosition) {
			// 2. 익절 조건 평가
			Optional<ProfitEvaluationResult> resultOpt = binanceService.evaluateProfitTarget(symbol);

			if (resultOpt.isPresent() && resultOpt.get().isShouldTakeProfit()) {
				// 3. 열려있는 주문 모두 취소
				binanceService.cancelAllOpenOrders(symbol);

				// 4. 시장가 포지션 청산
				binanceService.closePositionMarket(symbol, resultOpt.get().getSide());
			}
		}
	}
}
