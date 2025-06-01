package com.trade.copy.binance.service;

import com.trade.copy.binance.config.CopyTradingConfig;
import com.trade.copy.binance.config.FuturesConfig;
import com.trade.copy.binance.config.BinanceHttpClient;
import com.trade.copy.binance.util.Calculator;
import com.trade.copy.binance.util.SignatureUtil;
import com.trade.copy.binance.util.TelegramMessageSender;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.configurationprocessor.json.JSONArray;
import org.springframework.boot.configurationprocessor.json.JSONObject;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BinanceFutureTradeService {
	private static final Logger logger =
		  Logger.getLogger(BinanceFutureTradeService.class.getName());

	// ● 복사거래 리더 전용 설정 (SAPI)
	private final CopyTradingConfig copyTradingConfig;

	// ● 선물 거래(포지션 조회·주문) 전용 설정 (FAPI)
	private final FuturesConfig futuresConfig;

	private final BinanceHttpClient httpClient;
	private final TelegramMessageSender telegram;

	/**
	 * 1) 바이낸스 서버 시간을 받아오는 헬퍼 메서드
	 *    - 호출 전 서버 시간을 가져와 timestamp 오차를 방지합니다.
	 *    - 실패 시 RuntimeException을 던집니다.
	 */
	private long getFuturesServerTime() throws Exception {
		String url = String.format("%s/fapi/v1/time", futuresConfig.getBaseUrl());
		logger.info("▶ getFuturesServerTime 호출 URL = " + url);

		HttpRequest req = HttpRequest.newBuilder()
			  .uri(URI.create(url))
			  .GET()
			  .build();

		HttpResponse<String> res =
			  httpClient.client.send(req, HttpResponse.BodyHandlers.ofString());

		if (res.statusCode() != 200) {
			throw new RuntimeException("서버 시간 조회 실패: " + res.body());
		}
		JSONObject obj = new JSONObject(res.body());
		return obj.getLong("serverTime");
	}

	/**
	 * 기존: DELETE /sapi/v1/lead/future/order
	 * 변경: DELETE /fapi/v1/allOpenOrders  // 특정 심볼의 모든 체결되지 않은 오더 취소
	 *   ● futuresConfig.getBaseUrl()로 base URL 지정 (https://fapi.binance.com)
	 *   ● Signature 생성 시 futuresConfig.getSecret() 사용
	 */
	public void cancelAllOpenOrders(String symbol) throws Exception {
		// 1) 타임스탬프 생성 (클라이언트 시스템 시간)
		long timestamp = System.currentTimeMillis();
		logger.info("▶ cancelAllOpenOrders - 클라이언트 timestamp = " + timestamp);

		// 2) query 문자열 구성 (symbol + timestamp)
		String query = String.format("symbol=%s&timestamp=%d", symbol, timestamp);
		logger.info("▶ cancelAllOpenOrders - query = " + query);

		// 3) signature 생성 (futuresConfig.getSecret 사용)
		String signature = SignatureUtil.generate(query, futuresConfig.getSecret());
		logger.info("▶ cancelAllOpenOrders - signature = " + signature);

		// 4) 최종 호출 URL 조립
		String url = String.format(
			  "%s/fapi/v1/allOpenOrders?%s&signature=%s",
			  futuresConfig.getBaseUrl(),
			  query,
			  signature
		);
		logger.info("▶ cancelAllOpenOrders 호출 URL = " + url);

		// 5) HTTP DELETE 요청 빌드
		HttpRequest request = HttpRequest.newBuilder()
			  .uri(URI.create(url))
			  .header("X-MBX-APIKEY", futuresConfig.getKey())
			  .DELETE()
			  .build();

		// 6) 요청 전송 및 응답 수신
		HttpResponse<String> response =
			  httpClient.client.send(request, HttpResponse.BodyHandlers.ofString());
		logger.info("▶ cancelAllOpenOrders 응답 코드 = " + response.statusCode());
		logger.info("▶ cancelAllOpenOrders 응답 바디 = " + response.body());

		// 7) 응답 코드 확인 (200이 아니면 예외)
		if (response.statusCode() != 200) {
			throw new RuntimeException(
				  "Binance API Error (cancelAllOpenOrders): "
						+ response.statusCode() + " – " + response.body()
			);
		}
	}

	/**
	 * Position Information V3 (USER_DATA)
	 * https://developers.binance.com/docs/derivatives/usds-margined-futures/trade/rest-api#position-information-v3
	 */
	public boolean hasOpenPosition(String symbol) throws Exception {
		// 1) 서버 시간 가져오기
		long serverTime = getFuturesServerTime();

		// 2) recvWindow: 허용 오차 범위 (예: 5000ms = 5초)
		long recvWindow = 1000L;
		logger.info("▶ hasOpenPosition - recvWindow = " + recvWindow);

		// 3) 쿼리 문자열 구성 (timestamp + recvWindow)
		String query = String.format("timestamp=%d&recvWindow=%d", serverTime, recvWindow);
		logger.info("▶ hasOpenPosition - query = " + query);

		// 4) signature 생성 (futuresConfig.getSecret 사용)
		String signature = SignatureUtil.generate(query, futuresConfig.getSecret());
		logger.info("▶ hasOpenPosition - signature = " + signature);

		// 5) v3 엔드포인트 호출 URL 조립
		String url = String.format(
			  "%s/fapi/v3/positionRisk?%s&signature=%s",
			  futuresConfig.getBaseUrl(),
			  query,
			  signature
		);
		logger.info("▶ hasOpenPosition(v3) 호출 URL = " + url);

		// 6) HTTP GET 요청 생성
		HttpRequest request = HttpRequest.newBuilder()
			  .uri(URI.create(url))
			  .header("X-MBX-APIKEY", futuresConfig.getKey())
			  .GET()
			  .build();

		// 7) 요청 전송
		HttpResponse<String> response =
			  httpClient.client.send(request, HttpResponse.BodyHandlers.ofString());
		logger.info("▶ hasOpenPosition - 응답 코드 = " + response.statusCode());
		logger.info("▶ hasOpenPosition - 응답 바디 = " + response.body());

		// 8) 상태 코드 검사 (200 이외면 예외)
		if (response.statusCode() != 200) {
			throw new RuntimeException(
				  "Binance API Error (hasOpenPosition): "
						+ response.statusCode() + " – " + response.body()
			);
		}

		// 9) 빈 응답이거나 길이가 0일 경우 포지션 없음 → false
		String respBody = response.body();
		if (respBody == null || respBody.isBlank() || respBody.equals("[]")) {
			logger.info("▶ hasOpenPosition - 빈 응답, 포지션 없음 반환");
			return false;
		}

		// 10) JSON 배열로 파싱하여 symbol 비교
		JSONArray arr = new JSONArray(respBody);
		for (int i = 0; i < arr.length(); i++) {
			JSONObject pos = arr.getJSONObject(i);
			String respSymbol = pos.getString("symbol");
			logger.info("▶ hasOpenPosition - 응답 symbol = " + respSymbol);

			if (symbol.equals(respSymbol)) {
				double amt = Double.parseDouble(pos.getString("positionAmt"));
				logger.info("▶ hasOpenPosition - positionAmt = " + amt);
				boolean open = Math.abs(amt) > 0;
				logger.info("▶ hasOpenPosition - 포지션 열림 여부 = " + open);
				return open;
			}
		}

		logger.info("▶ hasOpenPosition - 해당 심볼 포지션 없음 반환");
		return false;
	}

	public boolean evaluateProfitTarget(String symbol) throws Exception {
		// 1) 클라이언트 timestamp 생성
		long timestamp = System.currentTimeMillis();
		logger.info("▶ evaluateProfitTarget - 클라이언트 timestamp = " + timestamp);

		// 2) query 문자열 (timestamp)
		String query = String.format("timestamp=%d", timestamp);
		logger.info("▶ evaluateProfitTarget - query = " + query);

		// 3) signature 생성 (futuresConfig.getSecret)
		String signature = SignatureUtil.generate(query, futuresConfig.getSecret());
		logger.info("▶ evaluateProfitTarget - signature = " + signature);

		// 4) 호출 URL 조립 (v1 엔드포인트)
		String url = String.format(
			  "%s/fapi/v3/positionRisk?%s&signature=%s",
			  futuresConfig.getBaseUrl(),
			  query,
			  signature
		);
		logger.info("▶ evaluateProfitTarget 호출 URL = " + url);

		// 5) HTTP GET 요청 생성
		HttpRequest request = HttpRequest.newBuilder()
			  .uri(URI.create(url))
			  .header("X-MBX-APIKEY", futuresConfig.getKey())
			  .GET()
			  .build();

		// 6) 요청 전송 및 응답 수신
		HttpResponse<String> response =
			  httpClient.client.send(request, HttpResponse.BodyHandlers.ofString());
		logger.info("▶ evaluateProfitTarget - 응답 코드 = " + response.statusCode());
		logger.info("▶ evaluateProfitTarget - 응답 바디 = " + response.body());

		// 7) 상태 코드 검사
		if (response.statusCode() != 200) {
			throw new RuntimeException(
				  "Binance API Error (evaluateProfitTarget): "
						+ response.statusCode() + " – " + response.body()
			);
		}

		// 8) JSON 배열 파싱
		JSONArray arr = new JSONArray(response.body());
		for (int i = 0; i < arr.length(); i++) {
			JSONObject pos = arr.getJSONObject(i);
			String respSymbol = pos.getString("symbol");
			logger.info("▶ evaluateProfitTarget - 응답 symbol = " + respSymbol);

			if (symbol.equals(respSymbol)) {
				double amt = Double.parseDouble(pos.getString("positionAmt"));
				double entryPrice = Double.parseDouble(pos.getString("entryPrice"));
				double markPrice = Double.parseDouble(pos.getString("markPrice"));
				logger.info(String.format(
					  "▶ evaluateProfitTarget - positionAmt=%s, entryPrice=%.2f, markPrice=%.2f",
					  amt, entryPrice, markPrice
				));

				// === Pane 코드 기준 익절가 계산 ===
				// 1) 수수료율 (0.1% = 0.001)
				double commissionRate = 0.1 / 100.0;

				// 2) 목표 이익 퍼센트 (0.4% = 0.004)
				double targetProfitPercent = 0.4 / 100.0;

				// 3) 슬리피지 & 수수료 버퍼 계산 (진입 + 청산)
				double slippageBuffer = entryPrice * commissionRate * 2;

				// 4) 최종 익절가 (entryPrice × (1 + targetProfitPercent) + slippageBuffer)
				double profitTargetPrice = entryPrice * (1 + targetProfitPercent) + slippageBuffer;
				logger.info(String.format(
					  "▶ evaluateProfitTarget - 계산된 익절가(profitTargetPrice)=%.2f",
					  profitTargetPrice
				));
				// ===============================

				boolean shouldTakeProfit = false;
				if (amt > 0) {
					// 롱 포지션: 현재가가 계산된 익절가 이상이면 익절
					shouldTakeProfit = markPrice >= profitTargetPrice;
				} else if (amt < 0) {
					// 숏 포지션: 숏 익절가는 (entryPrice × (1 - targetProfitPercent)) - slippageBuffer 로도 계산 가능
					// pane 코드 기준 비율을 반대로 넣어주시면 됩니다.
					double shortTargetPrice = entryPrice * (1 - targetProfitPercent) - slippageBuffer;
					logger.info(String.format(
						  "▶ evaluateProfitTarget - 계산된 숏 익절가(shortTargetPrice)=%.2f",
						  shortTargetPrice
					));
					shouldTakeProfit = markPrice <= shortTargetPrice;
				}

				logger.info("▶ evaluateProfitTarget - 익절 조건 충족 여부 = " + shouldTakeProfit);

				if (shouldTakeProfit) {
					String msg = String.format(
						  "💰 익절 조건 충족: %s\n진입가: %.2f\n현재가: %.2f\n목표 익절가: %.2f",
						  symbol, entryPrice, markPrice, (amt > 0 ? profitTargetPrice : (entryPrice * (1 - targetProfitPercent) - slippageBuffer))
					);
					logger.info("▶ evaluateProfitTarget - 메시지 전송 = " + msg);
					telegram.sendMessage(msg);
				} else {
					String debugMsg = String.format(
						  "\uD83D\uDCB0 익절 조건 미충족: %s\n진입가: %.2f\n현재가: %.2f%n목표 익절가: %.2f",
						  symbol, entryPrice, markPrice, (amt > 0 ? profitTargetPrice : (entryPrice * (1 - targetProfitPercent) - slippageBuffer))
					);
					logger.info("▶ evaluateProfitTarget - 메시지 생략(조건 미충족) = " + debugMsg);
				}
				return shouldTakeProfit;
			}
		}

		logger.info("▶ evaluateProfitTarget - 해당 심볼 포지션 없음, false 반환");
		return false;
	}

	public void closePositionMarket(String symbol) throws Exception {
		// 1) 클라이언트 timestamp 생성
		long timestamp = System.currentTimeMillis();
		logger.info("▶ closePositionMarket - 클라이언트 timestamp = " + timestamp);

		// 2) query 문자열 (timestamp)
		String query = String.format("timestamp=%d", timestamp);
		logger.info("▶ closePositionMarket - query = " + query);

		// 3) signature 생성
		String signature = SignatureUtil.generate(query, futuresConfig.getSecret());
		logger.info("▶ closePositionMarket - signature = " + signature);

		// 4) 호출 URL 조립
		String url = String.format(
			  "%s/fapi/v3/positionRisk?%s&signature=%s",
			  futuresConfig.getBaseUrl(),
			  query,
			  signature
		);
		logger.info("▶ closePositionMarket 호출 URL = " + url);

		// 5) HTTP GET 요청 생성
		HttpRequest accReq = HttpRequest.newBuilder()
			  .uri(URI.create(url))
			  .header("X-MBX-APIKEY", futuresConfig.getKey())
			  .GET()
			  .build();

		// 6) 요청 전송 및 응답 수신
		HttpResponse<String> accRes =
			  httpClient.client.send(accReq, HttpResponse.BodyHandlers.ofString());
		logger.info("▶ closePositionMarket - 응답 코드 = " + accRes.statusCode());
		logger.info("▶ closePositionMarket - 응답 바디 = " + accRes.body());

		// 7) 상태 코드 검사
		if (accRes.statusCode() != 200) {
			throw new RuntimeException(
				  "Binance API Error (closePositionMarket): "
						+ accRes.statusCode() + " – " + accRes.body()
			);
		}

		// 8) JSON 배열 파싱 및 심볼 확인
		JSONArray arr = new JSONArray(accRes.body());
		for (int i = 0; i < arr.length(); i++) {
			JSONObject pos = arr.getJSONObject(i);
			String respSymbol = pos.getString("symbol");
			logger.info("▶ closePositionMarket - 응답 symbol = " + respSymbol);

			if (symbol.equals(respSymbol)) {
				double amt = Double.parseDouble(pos.getString("positionAmt"));
				logger.info("▶ closePositionMarket - positionAmt = " + amt);

				if (amt == 0) {
					logger.info("▶ closePositionMarket - positionAmt이 0, 청산 필요 없음");
					return;
				}

				String side = amt > 0 ? "SELL" : "BUY";
				String quantity = String.valueOf(Math.abs(amt));
				logger.info(String.format(
					  "▶ closePositionMarket - 청산 주문 = symbol:%s, side:%s, quantity:%s",
					  symbol, side, quantity
				));

				telegram.sendMessage("🔁 포지션 청산 시작: " + symbol + " " + side + " " + quantity);
				openMarketPosition(symbol, side, quantity);
				return;
			}
		}

		logger.info("▶ closePositionMarket - 해당 심볼 포지션 없음, 청산 로직 종료");
	}

	/**
	 * 기존: fapi 시장가 캔들 조회 (유효) → 변경 없음
	 *
	 * * 로직 자체는 변경하지 않고, 주석 및 로깅만 추가했습니다.
	 */
	public boolean evaluateRsiEntry(String symbol) throws Exception {
		// 1) 캔들 조회 URL 생성 (v1 엔드포인트, 변경 없음)
		String url = String.format(
			  "https://fapi.binance.com/fapi/v1/klines?symbol=%s&interval=15m&limit=16",
			  symbol
		);
		logger.info("▶ evaluateRsiEntry 호출 URL = " + url);

		// 2) HTTP GET 요청 생성
		HttpRequest request = HttpRequest.newBuilder()
			  .uri(URI.create(url))
			  .GET()
			  .build();

		// 3) 요청 전송 및 응답 수신
		HttpResponse<String> response =
			  httpClient.client.send(request, HttpResponse.BodyHandlers.ofString());
		logger.info("▶ evaluateRsiEntry - 응답 코드 = " + response.statusCode());
		logger.info("▶ evaluateRsiEntry - 응답 바디 = " + response.body());

		// 4) JSON 배열 파싱 및 종가 리스트 추출
		JSONArray arr = new JSONArray(response.body());
		List<Double> closes = new ArrayList<>();
		for (int i = 0; i < arr.length() - 1; i++) {
			JSONArray candle = arr.getJSONArray(i);
			double closePrice = Double.parseDouble(candle.getString(4));
			closes.add(closePrice);
			logger.info(String.format(
				  "▶ evaluateRsiEntry - %d번째 종가 = %.2f", i, closePrice
			));
		}

		// 5) RSI 계산 (Calculator 사용)
		double rsi = Calculator.calculateRsi(closes, 14);
		logger.info(String.format("▶ evaluateRsiEntry - RSI 계산 결과 = %.2f", rsi));

		// 6) 이전 저가 및 최근 저가 비교
		double prevLow = Double.parseDouble(arr.getJSONArray(arr.length() - 3).getString(3));
		double latestClosedLow = Double.parseDouble(arr.getJSONArray(arr.length() - 2).getString(3));
		logger.info(String.format(
			  "▶ evaluateRsiEntry - prevLow = %.2f, latestClosedLow = %.2f",
			  prevLow, latestClosedLow
		));

		boolean lowCondition = latestClosedLow > prevLow;
		logger.info("▶ evaluateRsiEntry - 저가 비교 조건 = " + lowCondition);

		boolean result = rsi < 30 && lowCondition;
		logger.info("▶ evaluateRsiEntry - 진입 조건 충족 여부 = " + result);
		return result;
	}

	/**
	 * 기존: POST /sapi/v1/lead/future/order
	 * 변경: POST /fapi/v1/order  // 시장가 주문 전송
	 *
	 * * 로직 자체는 변경하지 않고, 주석 및 로깅만 추가했습니다.
	 */
	public void openMarketPosition(String symbol, String side, String quantity) throws Exception {
		// 1) 레버리지 설정 호출
		setLeverage(symbol, 10);

		// 2) quantity가 null일 경우, 최소 notional 기준으로 계산
		if (quantity == null) {
			// 2-1) 현재 mark price 조회
			String markPriceUrl =
				  String.format("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=%s", symbol);
			logger.info("▶ openMarketPosition - markPrice 호출 URL = " + markPriceUrl);

			HttpRequest priceRequest = HttpRequest.newBuilder()
				  .uri(URI.create(markPriceUrl))
				  .GET()
				  .build();
			HttpResponse<String> priceResponse =
				  httpClient.client.send(priceRequest, HttpResponse.BodyHandlers.ofString());
			logger.info("▶ openMarketPosition - markPrice 응답 코드 = " + priceResponse.statusCode());
			logger.info("▶ openMarketPosition - markPrice 응답 바디 = " + priceResponse.body());

			JSONObject priceJson = new JSONObject(priceResponse.body());
			double markPrice = priceJson.getDouble("markPrice");
			logger.info("▶ openMarketPosition - markPrice = " + markPrice);

			// 2-2) exchangeInfo에서 최소 notional 및 stepSize 조회
			String infoUrl = "https://fapi.binance.com/fapi/v1/exchangeInfo";
			logger.info("▶ openMarketPosition - exchangeInfo 호출 URL = " + infoUrl);

			HttpRequest infoRequest = HttpRequest.newBuilder()
				  .uri(URI.create(infoUrl))
				  .GET()
				  .build();
			HttpResponse<String> infoResponse =
				  httpClient.client.send(infoRequest, HttpResponse.BodyHandlers.ofString());
			logger.info("▶ openMarketPosition - exchangeInfo 응답 코드 = " + infoResponse.statusCode());
			logger.info("▶ openMarketPosition - exchangeInfo 응답 바디 = " + infoResponse.body());

			JSONObject infoJson = new JSONObject(infoResponse.body());
			JSONArray symbols = infoJson.getJSONArray("symbols");

			double minNotional = 5.0; // fallback
			int quantityPrecision = 3;

			for (int i = 0; i < symbols.length(); i++) {
				JSONObject s = symbols.getJSONObject(i);
				String respSymbol = s.getString("symbol");
				if (symbol.equals(respSymbol)) {
					JSONArray filters = s.getJSONArray("filters");
					for (int j = 0; j < filters.length(); j++) {
						JSONObject f = filters.getJSONObject(j);
						String filterType = f.getString("filterType");
						if ("MIN_NOTIONAL".equals(filterType)) {
							minNotional = f.getDouble("notional");
							logger.info("▶ openMarketPosition - minNotional = " + minNotional);
						}
						if ("LOT_SIZE".equals(filterType)) {
							quantityPrecision =
								  new BigDecimal(f.getString("stepSize"))
										.stripTrailingZeros()
										.scale();
							logger.info("▶ openMarketPosition - quantityPrecision = " + quantityPrecision);
						}
					}
					break;
				}
			}

			double targetNotional = minNotional * 1.05;
			double rawQty = targetNotional / markPrice;
			quantity = new BigDecimal(rawQty)
				  .setScale(quantityPrecision, RoundingMode.UP)
				  .toPlainString();
			logger.info("▶ openMarketPosition - 계산된 quantity = " + quantity);
		}

		// 3) 주문 전송을 위한 timestamp 및 signature 생성
		long timestamp = System.currentTimeMillis();
		logger.info("▶ openMarketPosition - 클라이언트 timestamp = " + timestamp);

		String query = String.format(
			  "symbol=%s&side=%s&type=MARKET&quantity=%s&timestamp=%d",
			  symbol, side, quantity, timestamp
		);
		logger.info("▶ openMarketPosition - query = " + query);

		String signature = SignatureUtil.generate(query, futuresConfig.getSecret());
		logger.info("▶ openMarketPosition - signature = " + signature);

		String fullUrl = String.format(
			  "%s/fapi/v1/order?%s&signature=%s",
			  futuresConfig.getBaseUrl(),
			  query,
			  signature
		);
		logger.info("▶ openMarketPosition 호출 URL = " + fullUrl);

		HttpRequest orderRequest = HttpRequest.newBuilder()
			  .uri(URI.create(fullUrl))
			  .header("X-MBX-APIKEY", futuresConfig.getKey())
			  .POST(HttpRequest.BodyPublishers.noBody())
			  .build();

		HttpResponse<String> response =
			  httpClient.client.send(orderRequest, HttpResponse.BodyHandlers.ofString());
		logger.info("▶ openMarketPosition - 응답 코드 = " + response.statusCode());
		logger.info("▶ openMarketPosition - 응답 바디 = " + response.body());

		String msg = String.format(
			  "🚀 시장가 주문 전송됨:\n심볼: %s\n방향: %s\n수량: %s\n레버리지: 10x\n응답: %s",
			  symbol, side, quantity, response.body()
		);
		logger.info("▶ openMarketPosition - 텔레그램 메시지 = " + msg);
		telegram.sendMessage(msg);
	}

	/**
	 * 기존: POST /sapi/v1/lead/future/leverage
	 * 변경: POST /fapi/v1/leverage  // 심볼별 레버리지 설정
	 *
	 * * 로직 자체는 변경하지 않고, 주석 및 로깅만 추가했습니다.
	 */
	public void setLeverage(String symbol, int leverage) throws Exception {
		// 1) 클라이언트 timestamp 생성
		long timestamp = System.currentTimeMillis();
		logger.info("▶ setLeverage - 클라이언트 timestamp = " + timestamp);

		// 2) body 문자열 구성 (symbol + leverage + timestamp)
		String body = String.format("symbol=%s&leverage=%d&timestamp=%d", symbol, leverage, timestamp);
		logger.info("▶ setLeverage - body = " + body);

		// 3) signature 생성
		String signature = SignatureUtil.generate(body, futuresConfig.getSecret());
		logger.info("▶ setLeverage - signature = " + signature);

		// 4) 호출 URL 조립
		String url = String.format(
			  "%s/fapi/v1/leverage?%s&signature=%s",
			  futuresConfig.getBaseUrl(),
			  body,
			  signature
		);
		logger.info("▶ setLeverage 호출 URL = " + url);

		// 5) HTTP POST 요청 생성
		HttpRequest request = HttpRequest.newBuilder()
			  .uri(URI.create(url))
			  .header("X-MBX-APIKEY", futuresConfig.getKey())
			  .method("POST", HttpRequest.BodyPublishers.noBody())
			  .build();

		// 6) 요청 전송
		HttpResponse<String> response =
			  httpClient.client.send(request, HttpResponse.BodyHandlers.ofString());
		logger.info("▶ setLeverage - 응답 코드 = " + response.statusCode());
		logger.info("▶ setLeverage - 응답 바디 = " + response.body());
	}

	/**
	 * 복사거래 리더 상태 확인 (추가 메서드)
	 * 변경: GET /sapi/v1/copyTrading/futures/userStatus  // 리더 여부 확인
	 *
	 * * 로직 자체는 변경하지 않고, 주석 및 로깅만 추가했습니다.
	 */
	public boolean isCopyTradingLeader() throws Exception {
		// 1) 클라이언트 timestamp 생성
		long timestamp = System.currentTimeMillis();
		logger.info("▶ isCopyTradingLeader - 클라이언트 timestamp = " + timestamp);

		// 2) query 문자열 구성
		String query = String.format("timestamp=%d", timestamp);
		logger.info("▶ isCopyTradingLeader - query = " + query);

		// 3) signature 생성 (copyTradingConfig.getSecret 사용)
		String signature = SignatureUtil.generate(query, copyTradingConfig.getSecret());
		logger.info("▶ isCopyTradingLeader - signature = " + signature);

		// 4) 호출 URL 조립
		String url = String.format(
			  "%s/sapi/v1/copyTrading/futures/userStatus?%s&signature=%s",
			  copyTradingConfig.getBaseUrl(),
			  query,
			  signature
		);
		logger.info("▶ isCopyTradingLeader 호출 URL = " + url);

		// 5) HTTP GET 요청 생성
		HttpRequest request = HttpRequest.newBuilder()
			  .uri(URI.create(url))
			  .header("X-MBX-APIKEY", copyTradingConfig.getKey())
			  .GET()
			  .build();

		// 6) 요청 전송 및 응답 수신
		HttpResponse<String> response =
			  httpClient.client.send(request, HttpResponse.BodyHandlers.ofString());
		logger.info("▶ isCopyTradingLeader - 응답 코드 = " + response.statusCode());
		logger.info("▶ isCopyTradingLeader - 응답 바디 = " + response.body());

		// 7) 상태 코드 검사
		if (response.statusCode() != 200) {
			throw new RuntimeException(
				  "Binance API Error (isCopyTradingLeader): "
						+ response.statusCode() + " – " + response.body()
			);
		}

		// 8) JSON 객체 파싱 및 isLeader 필드 리턴
		JSONObject json = new JSONObject(response.body());
		boolean isLeader = json.optBoolean("isLeader", false);
		logger.info("▶ isCopyTradingLeader - isLeader = " + isLeader);
		return isLeader;
	}
}
