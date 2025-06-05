package com.trade.copy.binance.service;

import com.trade.copy.binance.cache.ExchangeInfoCache;
import com.trade.copy.binance.cache.ExchangeInfoCache.SymbolFilterInfo;
import com.trade.copy.binance.config.BinanceProperties;
import com.trade.copy.binance.helper.BinanceApiHelper;
import com.trade.copy.binance.util.Calculator;
import com.trade.copy.binance.util.TelegramMessageSender;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import lombok.RequiredArgsConstructor;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class BinanceFutureTradeService {

	private static final Logger logger = Logger.getLogger(BinanceFutureTradeService.class.getName());

	private final BinanceApiHelper apiHelper;
	private final BinanceProperties props;
	private final ExchangeInfoCache exchangeInfoCache;
	private final TelegramMessageSender telegram;

	// ========================
	// 1) 미체결 주문 일괄 취소
	// ========================
	public void cancelAllOpenOrders(String symbol) throws Exception {
		Map<String, String> params = new HashMap<>();
		params.put("symbol", symbol);

		// 헬퍼가 내부에서 serverTime + recvWindow + signature 생성 후 DELETE 요청 수행
		String body = apiHelper.sendDeleteRequest("/fapi/v1/allOpenOrders", params);
		logger.info("▶ cancelAllOpenOrders 응답 바디 = " + body);
	}

	// ===========================
	// 2) 해당 심볼로 포지션 보유 여부 확인
	// ===========================
	public boolean hasOpenPosition(String symbol) throws Exception {
		String responseBody = apiHelper.sendGetRequest("/fapi/v3/positionRisk", Collections.emptyMap());

		if (responseBody == null || responseBody.isBlank() || responseBody.equals("[]")) {
			logger.info("▶ hasOpenPosition - 빈 응답, 포지션 없음");
			return false;
		}

		JSONArray arr = new JSONArray(responseBody);
		for (int i = 0; i < arr.length(); i++) {
			JSONObject pos = arr.getJSONObject(i);
			String respSymbol = pos.getString("symbol");
			if (!symbol.equals(respSymbol)) {
				continue;
			}

			double amt = Double.parseDouble(pos.getString("positionAmt"));
			boolean open = Math.abs(amt) > 0;
			logger.info("▶ hasOpenPosition - symbol=" + respSymbol + ", positionAmt=" + amt + ", open=" + open);
			return open;
		}

		logger.info("▶ hasOpenPosition - 해당 심볼 포지션 없음");
		return false;
	}

	// ===========================
	// 3) 익절 조건 계산 및 알림 (롱/숏)
	// ===========================
	public boolean evaluateProfitTarget(String symbol) throws Exception {
		String responseBody = apiHelper.sendGetRequest("/fapi/v3/positionRisk", Collections.emptyMap());

		if (responseBody == null || responseBody.isBlank() || responseBody.equals("[]")) {
			logger.info("▶ evaluateProfitTarget - 빈 응답, 포지션 없음");
			return false;
		}

		JSONArray arr = new JSONArray(responseBody);
		for (int i = 0; i < arr.length(); i++) {
			JSONObject pos = arr.getJSONObject(i);
			if (!symbol.equals(pos.getString("symbol"))) {
				continue;
			}

			double amt = Double.parseDouble(pos.getString("positionAmt"));
			if (amt == 0) {
				logger.info("▶ evaluateProfitTarget - 포지션 수량 0, 익절 불필요");
				return false;
			}

			double entryPrice = Double.parseDouble(pos.getString("entryPrice"));
			double markPrice = Double.parseDouble(pos.getString("markPrice"));

			// =====================================================
			// 하단의 수수료율, 목표 퍼센트, 슬리피지 버퍼는 props에서 가져오도록 변경
			// =====================================================
			double commissionRate = props.getCommissionRate();         // 예: 0.001 (0.1%)
			double targetProfitPercent = props.getTargetProfitPercent(); // 예: 0.004 (0.4%)
			double slippageBuffer = entryPrice * commissionRate * 2.0;

			double profitTargetPrice = entryPrice * (1 + targetProfitPercent) + slippageBuffer;
			double shortTargetPrice = entryPrice * (1 - targetProfitPercent) - slippageBuffer;

			boolean shouldTakeProfit;
			if (amt > 0) {
				// 롱: 현재 마크가 목표가 이상이면 익절
				shouldTakeProfit = markPrice >= profitTargetPrice;
			} else {
				// 숏: 현재 마크가 목표가 이하이면 익절
				shouldTakeProfit = markPrice <= shortTargetPrice;
			}

			logger.info(String.format(
				  "▶ evaluateProfitTarget - symbol=%s, amt=%.6f, entry=%.4f, mark=%.4f, profitTarget=%.4f, shortTarget=%.4f, shouldTake=%s",
				  symbol, amt, entryPrice, markPrice, profitTargetPrice, shortTargetPrice, shouldTakeProfit
			));

			if (shouldTakeProfit) {
				String msg = String.format(
					  "💰 익절 조건 충족: %s\n진입가: %.2f\n현재가: %.2f\n목표 익절가: %.2f",
					  symbol,
					  entryPrice,
					  markPrice,
					  (amt > 0 ? profitTargetPrice : shortTargetPrice)
				);
				telegram.sendMessage(msg);
			}

			return shouldTakeProfit;
		}

		logger.info("▶ evaluateProfitTarget - 해당 심볼 포지션 없음");
		return false;
	}

	// ===========================
	// 4) 시장가 포지션 청산 (롱/숏 구분)
	// ===========================
	public void closePositionMarket(String symbol) throws Exception {
		String responseBody = apiHelper.sendGetRequest("/fapi/v3/positionRisk", Collections.emptyMap());

		if (responseBody == null || responseBody.isBlank() || responseBody.equals("[]")) {
			logger.info("▶ closePositionMarket - 빈 응답, 포지션 없음");
			return;
		}

		JSONArray arr = new JSONArray(responseBody);
		for (int i = 0; i < arr.length(); i++) {
			JSONObject pos = arr.getJSONObject(i);
			if (!symbol.equals(pos.getString("symbol"))) {
				continue;
			}

			double amt = Double.parseDouble(pos.getString("positionAmt"));
			if (amt == 0) {
				logger.info("▶ closePositionMarket - 포지션 수량0, 청산 불필요");
				return;
			}

			String side = amt > 0 ? "SELL" : "BUY";
			// BigDecimal을 써서 소숫점 불필요 제거
			String quantity = BigDecimal.valueOf(Math.abs(amt))
				  .stripTrailingZeros()
				  .toPlainString();

			logger.info(String.format(
				  "▶ closePositionMarket - 청산 주문(symbol=%s, side=%s, quantity=%s)",
				  symbol, side, quantity
			));

			telegram.sendMessage("🔁 포지션 청산 시작: " + symbol + " " + side + " " + quantity);
			// openMarketPosition 안에서 레버리지 및 주문 요청 처리
			openMarketPosition(symbol, side, quantity);
			return;
		}

		logger.info("▶ closePositionMarket - 해당 심볼 포지션 없음");
	}

	// ===========================
	// 5) RSI 진입 조건 확인
	// ===========================
	public boolean evaluateRsiEntry(String symbol) throws Exception {
		String url = String.format("%s/fapi/v1/klines?symbol=%s&interval=15m&limit=16",
			  props.getBaseUrl(), symbol);

		logger.info("▶ evaluateRsiEntry 호출 URL = " + url);
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			  .uri(URI.create(url))
			  .GET()
			  .build();

		java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpResponse<String> response =
			  client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
		logger.info("▶ evaluateRsiEntry - 응답 코드 = " + response.statusCode());

		if (response.statusCode() != 200) {
			throw new RuntimeException("캔들 조회 실패: " + response.body());
		}

		String resBody = response.body();
		if (resBody == null || resBody.isBlank()) {
			logger.warning("▶ evaluateRsiEntry - 빈 응답, RSI 진입 불가");
			return false;
		}

		JSONArray arr = new JSONArray(resBody);
		if (arr.length() < 2) {
			logger.warning("▶ evaluateRsiEntry - 캔들 개수 부족: " + arr.length());
			return false;
		}

		// 종가 리스트 (완료된 15개 봉)
		List<Double> closes = new ArrayList<>();
		for (int i = 0; i < arr.length() - 1; i++) {
			JSONArray candle = arr.getJSONArray(i);
			double closePrice = Double.parseDouble(candle.getString(4));
			closes.add(closePrice);
		}
		double rsi = Calculator.calculateRsi(closes, 14);
		logger.info(String.format("▶ evaluateRsiEntry - RSI 계산 결과 = %.2f", rsi));

		// 저가 비교 (arr.length()-3 = 인덱스 13, arr.length()-2 = 인덱스 14)
		double prevLow = Double.parseDouble(arr.getJSONArray(arr.length() - 3).getString(3));
		double latestClosedLow = Double.parseDouble(arr.getJSONArray(arr.length() - 2).getString(3));
		logger.info(String.format("▶ evaluateRsiEntry - prevLow=%.4f, latestClosedLow=%.4f",
			  prevLow, latestClosedLow));

		boolean lowCondition = latestClosedLow > prevLow;
		boolean result = (rsi < 30) && lowCondition;
		logger.info("▶ evaluateRsiEntry - 진입 조건 충족 여부 = " + result);
		return result;
	}

	// =============================================
	// 6) 시장가 포지션 진입 (레버리지 설정 포함, 인메모리 캐시 사용)
	// =============================================
	public void openMarketPosition(String symbol, String side, String quantity) throws Exception {
		// ───────────────────────────────────────────────────────────────────────────────
		// 0) 마진 모드를 CROSS로 설정
		// ───────────────────────────────────────────────────────────────────────────────
		Map<String, String> marginParams = new HashMap<>();
		marginParams.put("symbol", symbol);
		marginParams.put("marginType", props.getMarginType());  // CROSSED: Cross 마진, ISOLATED: Isolated 마진
		String marginBody = apiHelper.sendPostRequest("/fapi/v1/marginType", marginParams);
		logger.info("▶ setMarginType (CROSS) 응답 바디 = " + marginBody);

		// ------------------------------------------------------------
		// 1) 레버리지 설정 (외부 props.getDefaultLeverage() 사용)
		// ------------------------------------------------------------
		Map<String, String> leverageParams = new HashMap<>();
		leverageParams.put("symbol", symbol);
		leverageParams.put("leverage", String.valueOf(props.getDefaultLeverage()));
		String levBody = apiHelper.sendPostRequest("/fapi/v1/leverage", leverageParams);
		logger.info("▶ setLeverage 응답 바디 = " + levBody);

		// ------------------------------------------------------------
		// 2) quantity 파라미터가 null 또는 빈 문자열인 경우, MIN_NOTIONAL 기준 계산
		//    → In-Memory 캐시(ExchangeInfoCache)에서 가져옴
		// ------------------------------------------------------------
		String finalQuantity = quantity;
		if (finalQuantity == null || finalQuantity.isBlank()) {
			// 2-1) 현재 마크 가격 조회
			Map<String, String> markParams = new HashMap<>();
			markParams.put("symbol", symbol);
			String markRes = apiHelper.sendGetRequest("/fapi/v1/premiumIndex", markParams);
			double markPrice = new JSONObject(markRes).getDouble("markPrice");

			// 2-2) In-Memory 캐시에서 해당 심볼 정보 조회
			SymbolFilterInfo sInfo = exchangeInfoCache.getSymbolInfo(symbol);
			if (sInfo == null) {
				throw new RuntimeException("캐시에 심볼 정보가 없습니다: " + symbol);
			}
			double minNotional = sInfo.getMinNotional();
			int quantityPrecision = sInfo.getLotSizePrecision();

			double targetNotional = minNotional * 1.05;
			double rawQty = targetNotional / markPrice;
			finalQuantity = new BigDecimal(rawQty)
				  .setScale(quantityPrecision, RoundingMode.UP)
				  .toPlainString();
		}

		// ------------------------------------------------------------
		// 3) 헤지 모드 확인 → 포지션 사이드 설정 (LONG/SHORT)
		// ------------------------------------------------------------
		String dualRes = apiHelper.sendGetRequest("/fapi/v1/positionSide/dual", Collections.emptyMap());
		boolean isHedgeMode = new JSONObject(dualRes).getBoolean("dualSidePosition");

		Map<String, String> orderParams = new HashMap<>();
		orderParams.put("symbol", symbol);
		orderParams.put("side", side);
		orderParams.put("type", "MARKET");
		orderParams.put("quantity", finalQuantity);
		if (isHedgeMode) {
			String positionSide = side.equalsIgnoreCase("BUY") ? "LONG" : "SHORT";
			orderParams.put("positionSide", positionSide);
		}

		// ------------------------------------------------------------
		// 4) 시장가 주문 전송
		// ------------------------------------------------------------
		String orderRes = apiHelper.sendPostRequest("/fapi/v1/order", orderParams);
		logger.info("▶ openMarketPosition - 주문 응답 바디 = " + orderRes);

		String msg = String.format(
			  "🚀 시장가 주문 전송됨:\n심볼: %s\n방향: %s\n수량: %s\n레버리지: %dx\n응답: %s",
			  symbol, side, finalQuantity, props.getDefaultLeverage(), orderRes
		);
		telegram.sendMessage(msg);
	}
}