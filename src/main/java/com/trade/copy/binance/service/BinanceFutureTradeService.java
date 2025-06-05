package com.trade.copy.binance.service;

import com.trade.copy.binance.cache.ExchangeInfoCache;
import com.trade.copy.binance.cache.ExchangeInfoCache.SymbolFilterInfo;
import com.trade.copy.binance.config.BinanceProperties;
import com.trade.copy.binance.dto.ProfitEvaluationResult;
import com.trade.copy.binance.helper.BinanceApiHelper;
import com.trade.copy.binance.util.Calculator;
import com.trade.copy.binance.util.TelegramMessageSender;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.*;
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
	 * 지정된 심볼에 대한 모든 미체결 주문을 취소합니다.
	 * Binance Futures의 /fapi/v1/allOpenOrders 엔드포인트에 DELETE 요청을 보내
	 * 해당 심볼의 모든 열린 주문을 일괄 취소합니다.
	 *
	 * @param symbol 취소할 거래 페어 (예: "BTCUSDT")
	 * @throws Exception API 요청 실패 시 발생
	 */
	public void cancelAllOpenOrders(String symbol) throws Exception {
		Map<String, String> params = new HashMap<>();
		params.put("symbol", symbol);

		// 헬퍼가 내부에서 serverTime + recvWindow + signature 생성 후 DELETE 요청 수행
		apiHelper.sendDeleteRequest("/fapi/v1/allOpenOrders", params);
	}

	/**
	 * 지정된 심볼(symbol)에 대해 현재 보유 중인 포지션이 존재하는지 확인합니다.
	 * Binance Futures API의 /fapi/v3/positionRisk 엔드포인트를 호출하여
	 * 해당 심볼의 포지션 수량(positionAmt)이 0이 아닌 경우, 포지션을 보유 중인 것으로 간주합니다.
	 *
	 * @param symbol 조회할 거래 페어 (예: "BTCUSDT")
	 * @return 포지션을 보유 중이면 true, 아니면 false
	 * @throws Exception Binance API 호출 중 오류가 발생한 경우
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
	 * 지정된 심볼에 대해 현재 보유 중인 포지션의 익절 조건을 평가합니다.
	 * 진입가, 현재가, 목표 수익률, 수수료, 슬리피지를 기준으로 익절 여부를 계산합니다.
	 * 조건을 만족할 경우 Telegram으로 알림을 전송하고, 포지션 방향(LONG/SHORT) 및 관련 정보가 포함된 결과 객체를 반환합니다.
	 * 포지션이 없거나 조건에 맞지 않는 경우 빈 Optional을 반환합니다.
	 *
	 * @param symbol 평가할 거래 페어 (예: "BTCUSDT")
	 * @return 익절 조건 평가 결과. 조건에 맞는 포지션이 없거나 익절 조건을 충족하지 않으면 빈 Optional을 반환
	 * @throws Exception Binance API 호출 중 오류가 발생한 경우
	 */
	public Optional<ProfitEvaluationResult> evaluateProfitTarget(String symbol) throws Exception {
		String responseBody =
			  apiHelper.sendGetRequest("/fapi/v3/positionRisk", Collections.emptyMap());

		if (responseBody == null || responseBody.isBlank() || responseBody.equals("[]")) {
			return Optional.empty();
		}

		JSONArray arr = new JSONArray(responseBody);
		for (int i = 0; i < arr.length(); i++) {
			JSONObject pos = arr.getJSONObject(i);
			if (!symbol.equals(pos.getString("symbol"))) continue;

			double amt = Double.parseDouble(pos.getString("positionAmt"));
			if (amt == 0) return Optional.empty();

			double entryPrice = Double.parseDouble(pos.getString("entryPrice"));
			double markPrice = Double.parseDouble(pos.getString("markPrice"));

			// 수수료율, 목표 퍼센트, 슬리피지 버퍼는 props에서 가져오도록 변경
			double commissionRate = props.getCommissionRate();         // 예: 0.001 (0.1%)
			double targetProfitPercent = props.getTargetProfitPercent(); // 예: 0.004 (0.4%)
			double slippageBuffer = entryPrice * commissionRate * 2.0;

			boolean isLong = amt > 0;
			double profitTargetPrice = entryPrice * (1 + targetProfitPercent) + slippageBuffer;
			double shortTargetPrice = entryPrice * (1 - targetProfitPercent) - slippageBuffer;

			boolean shouldTakeProfit = isLong
				  ? markPrice >= profitTargetPrice
				  : markPrice <= shortTargetPrice;

			double targetPrice = isLong ? profitTargetPrice : shortTargetPrice;
			String side = isLong ? "LONG" : "SHORT";

			if (shouldTakeProfit) {
				String msg = String.format(
					  "💰 익절 조건 충족: %s\n진입가: %.2f\n현재가: %.2f\n목표 익절가: %.2f",
					  symbol, entryPrice, markPrice, targetPrice
				);
				telegram.sendMessage(msg);
			}

			return Optional.of(new ProfitEvaluationResult(
				  shouldTakeProfit, side, entryPrice, markPrice, targetPrice
			));
		}

		return Optional.empty();
	}

	/**
	 * 지정된 심볼과 주문 방향(side)에 해당하는 포지션을 시장가로 청산합니다.
	 * 헷지 모드 기준으로 각 포지션은 LONG 또는 SHORT로 구분되며,
	 * 주어진 side가 BUY이면 SHORT 포지션을, SELL이면 LONG 포지션을 청산 대상으로 간주합니다.
	 * 해당 심볼의 포지션 중 보유 수량(positionAmt)이 0이 아닌 경우에만 청산을 시도하며,
	 * 시장가 주문으로 reduceOnly 옵션 없이 주문을 전송합니다.
	 *
	 * @param symbol 거래 페어 (예: "BTCUSDT")
	 * @param side   주문 방향 ("BUY" 또는 "SELL"). 포지션 방향과 반대 방향이어야 청산 가능
	 * @throws Exception Binance API 호출 또는 주문 전송 중 오류가 발생한 경우
	 */
	public void closePositionMarket(String symbol, String side) throws Exception {
		// 1. 포지션 목록 조회 (헷징 모드이므로 LONG/SHORT 따로 있음)
		String response =
			  apiHelper.sendGetRequest("/fapi/v2/positionRisk", Map.of("symbol", symbol));
		JSONArray positions = new JSONArray(response);

		for (int i = 0; i < positions.length(); i++) {
			JSONObject pos = positions.getJSONObject(i);
			String positionSide = pos.getString("positionSide"); // "LONG" or "SHORT"

			String holdSide = positionSide.equals("LONG") ? "SELL" : "BUY";
			if (!holdSide.equals(side)) continue;

			BigDecimal positionAmt = new BigDecimal(pos.getString("positionAmt"));

			// 2. 보유한 포지션만 청산 (LONG → >0, SHORT → <0)
			if (positionAmt.compareTo(BigDecimal.ZERO) == 0) continue;

			BigDecimal quantity = positionAmt.abs();

			// 3. 시장가, reduceOnly 주문 생성
			Map<String, String> orderParams = new HashMap<>();
			orderParams.put("symbol", symbol);
			orderParams.put("side", side);
			orderParams.put("type", "MARKET");
			orderParams.put("quantity", quantity.toPlainString());
			orderParams.put("positionSide", positionSide);

			apiHelper.sendPostRequest("/fapi/v1/order", orderParams);
			System.out.println(
				  "✅ 포지션 청산 완료: " + symbol + " / " + positionSide + " / 수량 " + quantity);
		}
	}

	/**
	 * RSI 진입 조건을 평가합니다.
	 * 지정된 심볼의 15분봉 캔들 데이터를 Binance API에서 조회한 뒤,
	 * 최근 15개의 완료된 종가 데이터를 기반으로 RSI(14)를 계산합니다.
	 * 조건은 다음 두 가지를 모두 만족해야 합니다:
	 * 1. RSI가 30 미만
	 * 2. 최근 봉의 저가가 이전 봉의 저가보다 높은 경우
	 * 위 조건을 만족할 경우 진입 조건이 성립한 것으로 판단하여 true를 반환합니다.
	 *
	 * @param symbol 평가할 거래 페어 (예: "BTCUSDT")
	 * @return 진입 조건을 만족하면 true, 그렇지 않으면 false
	 * @throws Exception API 호출 또는 응답 파싱 중 오류가 발생한 경우
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
	 * 지정된 심볼에 대해 시장가 포지션을 오픈합니다.
	 * 지정된 거래 페어(symbol)와 주문 방향(side: BUY 또는 SELL)을 기반으로 시장가 주문을 실행합니다.
	 * 수량(quantity)이 지정되지 않은 경우, MIN_NOTIONAL 기준으로 수량을 계산하여 주문을 생성합니다.
	 * 실행 전 다음과 같은 절차를 수행합니다:
	 * 1. 지정된 심볼에 대해 레버리지 설정
	 * 2. 수량이 비어 있을 경우:
	 * - 현재 마크 가격 조회
	 * - 캐시에서 심볼의 최소 주문 금액 및 수량 소수점 자릿수 정보 조회
	 * - 최소 주문 금액(MIN_NOTIONAL)의 1.05배에 해당하는 수량 계산
	 * 3. 현재 계정이 헷지 모드(dual position mode)인지 확인하고, 포지션 방향(LONG/SHORT)을 설정
	 * 4. 시장가 주문 전송 후, 주문 내용을 텔레그램으로 전송
	 *
	 * @param symbol   거래 페어 (예: "BTCUSDT")
	 * @param side     주문 방향 ("BUY" 또는 "SELL")
	 * @param quantity 주문 수량. null 또는 빈 문자열인 경우 자동 계산됨
	 * @throws Exception Binance API 호출 또는 내부 계산 중 오류가 발생한 경우
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
			double rawQty = targetNotional / markPrice * 100;
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
		apiHelper.sendPostRequest("/fapi/v1/order", orderParams);

		telegram.sendMessage(String.format(
			  "🚀 시장가 주문 전송됨:\n심볼: %s\n방향: %s\n수량: %s\n레버리지: %dx",
			  symbol, side, finalQuantity, props.getDefaultLeverage()
		));
	}

	/**
	 * 현재 보유 중인 모든 포지션의 심볼 목록을 반환합니다.
	 * Binance Futures API의 /fapi/v3/positionRisk 엔드포인트를 호출하여,
	 * positionAmt가 0이 아닌 포지션의 symbol만 필터링하여 반환합니다.
	 *
	 * @return 포지션을 보유 중인 심볼들의 리스트 (예: ["BTCUSDT", "ETHUSDT"])
	 * @throws Exception API 호출 실패 또는 파싱 오류 발생 시
	 */
	public List<String> getOpenPositionSymbols() throws Exception {
		String responseBody =
			  apiHelper.sendGetRequest("/fapi/v3/positionRisk", Collections.emptyMap());

		if (responseBody == null || responseBody.isBlank() || responseBody.equals("[]")) {
			return Collections.emptyList();
		}

		JSONArray arr = new JSONArray(responseBody);
		List<String> result = new ArrayList<>();

		for (int i = 0; i < arr.length(); i++) {
			JSONObject pos = arr.getJSONObject(i);
			double amt = Double.parseDouble(pos.getString("positionAmt"));
			if (amt != 0) {
				result.add(pos.getString("symbol"));
			}
		}

		return result;
	}
}