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

	private static final Logger logger =
		  Logger.getLogger(BinanceFutureTradeService.class.getName());

	private final BinanceApiHelper apiHelper;
	private final BinanceProperties props;
	private final ExchangeInfoCache exchangeInfoCache;
	private final TelegramMessageSender telegram;

	/**
	 * 미체결 주문 일괄 취소
	 *
	 * @param symbol
	 * @throws Exception
	 */
	public void cancelAllOpenOrders(String symbol) throws Exception {
		Map<String, String> params = new HashMap<>();
		params.put("symbol", symbol);

		// 헬퍼가 내부에서 serverTime + recvWindow + signature 생성 후 DELETE 요청 수행
		apiHelper.sendDeleteRequest("/fapi/v1/allOpenOrders", params);
	}

	/**
	 * 해당 심볼로 포지션 보유 여부 확인
	 *
	 * @param symbol
	 * @return
	 * @throws Exception
	 */
	public boolean hasOpenPosition(String symbol) throws Exception {
		String responseBody =
			  apiHelper.sendGetRequest("/fapi/v3/positionRisk", Collections.emptyMap());

		if (responseBody == null || responseBody.isBlank() || responseBody.equals("[]")) {
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
			return Math.abs(amt) > 0;
		}

		return false;
	}

	/**
	 * 익절 조건 계산 및 알림
	 *
	 * @param symbol
	 * @return
	 * @throws Exception
	 */
	public boolean evaluateProfitTarget(String symbol) throws Exception {
		String responseBody =
			  apiHelper.sendGetRequest("/fapi/v3/positionRisk", Collections.emptyMap());

		if (responseBody == null || responseBody.isBlank() || responseBody.equals("[]")) {
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
				return false;
			}

			double entryPrice = Double.parseDouble(pos.getString("entryPrice"));
			double markPrice = Double.parseDouble(pos.getString("markPrice"));

			// 수수료율, 목표 퍼센트, 슬리피지 버퍼는 props에서 가져오도록 변경
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

		return false;
	}

	/**
	 * 시장가 포지션 청산
	 *
	 * @param symbol
	 * @throws Exception
	 */
	public void closePositionMarket(String symbol) throws Exception {
		// 1. 포지션 목록 조회 (헷징 모드이므로 LONG/SHORT 따로 있음)
		String response = apiHelper.sendGetRequest("/fapi/v2/positionRisk", Map.of("symbol", symbol));
		JSONArray positions = new JSONArray(response);

		for (int i = 0; i < positions.length(); i++) {
			JSONObject pos = positions.getJSONObject(i);
			String positionSide = pos.getString("positionSide"); // "LONG" or "SHORT"
			BigDecimal positionAmt = new BigDecimal(pos.getString("positionAmt"));

			// 2. 보유한 포지션만 청산 (LONG → >0, SHORT → <0)
			if (positionAmt.compareTo(BigDecimal.ZERO) == 0) continue;

			String side = positionSide.equals("LONG") ? "SELL" : "BUY";
			BigDecimal quantity = positionAmt.abs();

			// 3. 시장가, reduceOnly 주문 생성
			Map<String, String> orderParams = new HashMap<>();
			orderParams.put("symbol", symbol);
			orderParams.put("side", side);
			orderParams.put("type", "MARKET");
			orderParams.put("quantity", quantity.toPlainString());
			orderParams.put("positionSide", positionSide);

			String result = apiHelper.sendPostRequest("/fapi/v1/order", orderParams);
			System.out.println("✅ 포지션 청산 완료: " + symbol + " / " + positionSide + " / 수량 " + quantity);
		}
	}

	/**
	 * RSI 진입 조건 확인
	 *
	 * @param symbol
	 * @return
	 * @throws Exception
	 */
	public boolean evaluateRsiEntry(String symbol) throws Exception {
		String url = String.format("%s/fapi/v1/klines?symbol=%s&interval=15m&limit=16",
			  props.getBaseUrl(), symbol);

		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			  .uri(URI.create(url))
			  .GET()
			  .build();

		java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpResponse<String> response =
			  client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() != 200) {
			throw new RuntimeException("캔들 조회 실패: " + response.body());
		}

		String resBody = response.body();
		if (resBody == null || resBody.isBlank()) {
			return false;
		}

		JSONArray arr = new JSONArray(resBody);
		if (arr.length() < 2) {
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

		// 저가 비교 (arr.length()-3 = 인덱스 13, arr.length()-2 = 인덱스 14)
		double prevLow = Double.parseDouble(arr.getJSONArray(arr.length() - 3).getString(3));
		double latestClosedLow =
			  Double.parseDouble(arr.getJSONArray(arr.length() - 2).getString(3));

		boolean lowCondition = latestClosedLow > prevLow;
		return (rsi < 30) && lowCondition;
	}

	/**
	 * 시장가 포지션 진입
	 *
	 * @param symbol
	 * @param side
	 * @param quantity
	 * @throws Exception
	 */
	public void openMarketPosition(String symbol, String side, String quantity) throws Exception {
		// 레버리지 설정
		Map<String, String> leverageParams = new HashMap<>();
		leverageParams.put("symbol", symbol);
		leverageParams.put("leverage", String.valueOf(props.getDefaultLeverage()));
		apiHelper.sendPostRequest("/fapi/v1/leverage", leverageParams);

		// quantity 파라미터가 null 또는 빈 문자열인 경우, MIN_NOTIONAL 기준 계산
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

		// 헤지 모드 확인 후 포지션 사이드 설정
		String dualRes =
			  apiHelper.sendGetRequest("/fapi/v1/positionSide/dual", Collections.emptyMap());
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

		// 시장가 주문 전송
		String orderRes = apiHelper.sendPostRequest("/fapi/v1/order", orderParams);

		String msg = String.format(
			  "🚀 시장가 주문 전송됨:\n심볼: %s\n방향: %s\n수량: %s\n레버리지: %dx\n응답: %s",
			  symbol, side, finalQuantity, props.getDefaultLeverage(), orderRes
		);
		telegram.sendMessage(msg);
	}
}