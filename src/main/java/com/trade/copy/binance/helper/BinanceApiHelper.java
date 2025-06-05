package com.trade.copy.binance.helper;

import com.trade.copy.binance.config.BinanceHttpClient;
import com.trade.copy.binance.config.BinanceProperties;
import com.trade.copy.binance.util.SignatureUtil;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.StringJoiner;
import java.util.logging.Logger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.json.JSONObject;

/**
 * Binance API 호출을 추상화한 헬퍼 클래스
 *  - 서버 시간 동기화(getTime) → 시그니처 생성 → HTTP 요청 → 응답 코드 검사 → 응답 Body 반환
 */
@Service
@RequiredArgsConstructor
public class BinanceApiHelper {

	private static final Logger logger = Logger.getLogger(BinanceApiHelper.class.getName());

	private final BinanceProperties binanceProperties;
	private final BinanceHttpClient httpClient;

	/**
	 * 서버 시간 조회
	 */
	private long getFuturesServerTime() throws Exception {
		String url = binanceProperties.getBaseUrl() + "/fapi/v1/time";
		HttpRequest req = HttpRequest.newBuilder()
			  .uri(URI.create(url))
			  .GET()
			  .build();

		HttpResponse<String> res = httpClient.client.send(req, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() != 200) {
			throw new RuntimeException("서버 시간 조회 실패: " + res.body());
		}
		JSONObject obj = new JSONObject(res.body());
		return obj.getLong("serverTime");
	}

	/**
	 * Map<String, String> 형태의 파라미터를 쿼리 문자열로 변환
	 */
	private String buildQueryString(Map<String, String> params) {
		if (params == null || params.isEmpty()) {
			return "";
		}
		StringJoiner joiner = new StringJoiner("&");
		params.forEach((k, v) -> {
			if (StringUtils.hasText(v)) {
				joiner.add(k + "=" + v);
			}
		});
		return joiner.toString();
	}

	/**
	 * 공통 GET 요청
	 * @param path  API 경로 (예: "/fapi/v3/positionRisk")
	 * @param extraParams  호출 시그니처 생성에 필요한 추가 파라미터(Map으로)
	 * @return response body (String)
	 */
	public String sendGetRequest(String path, Map<String, String> extraParams) throws Exception {
		long serverTime = getFuturesServerTime();
		MultiValueMap<String, String> allParams = new LinkedMultiValueMap<>();

		// 1) 서버 시간 + recvWindow
		allParams.add("timestamp", String.valueOf(serverTime));
		allParams.add("recvWindow", String.valueOf(binanceProperties.getRecvWindow()));

		// 2) 추가 파라미터
		if (extraParams != null) {
			extraParams.forEach((k, v) -> {
				if (v != null && !v.isBlank()) {
					allParams.add(k, v);
				}
			});
		}

		// 3) 쿼리 문자열 조립
		String queryString = allParams.entrySet().stream()
			  .map(e -> e.getKey() + "=" + e.getValue().get(0))
			  .reduce((a, b) -> a + "&" + b)
			  .orElse("");

		// 4) 시그니처 생성
		String signature = SignatureUtil.generate(queryString, binanceProperties.getSecret());
		String fullUrl = binanceProperties.getBaseUrl() + path + "?" + queryString + "&signature=" + signature;

		// 5) HTTP GET 요청 생성 및 전송
		HttpRequest request = HttpRequest.newBuilder()
			  .uri(URI.create(fullUrl))
			  .header("X-MBX-APIKEY", binanceProperties.getKey())
			  .GET()
			  .build();

		HttpResponse<String> response = httpClient.client.send(request, HttpResponse.BodyHandlers.ofString());
		logger.info("▶ GET " + path + " 응답 코드 = " + response.statusCode());

		if (response.statusCode() != 200) {
			throw new RuntimeException("Binance API Error (GET " + path + "): "
				  + response.statusCode() + " – " + response.body());
		}
		return response.body();
	}

	/**
	 * 공통 DELETE 요청
	 */
	public String sendDeleteRequest(String path, Map<String, String> extraParams) throws Exception {
		long serverTime = getFuturesServerTime();
		MultiValueMap<String, String> allParams = new LinkedMultiValueMap<>();

		allParams.add("timestamp", String.valueOf(serverTime));
		allParams.add("recvWindow", String.valueOf(binanceProperties.getRecvWindow()));
		if (extraParams != null) {
			extraParams.forEach((k, v) -> {
				if (v != null && !v.isBlank()) {
					allParams.add(k, v);
				}
			});
		}

		String queryString = allParams.entrySet().stream()
			  .map(e -> e.getKey() + "=" + e.getValue().get(0))
			  .reduce((a, b) -> a + "&" + b)
			  .orElse("");

		String signature = SignatureUtil.generate(queryString, binanceProperties.getSecret());
		String fullUrl = binanceProperties.getBaseUrl() + path + "?" + queryString + "&signature=" + signature;

		HttpRequest request = HttpRequest.newBuilder()
			  .uri(URI.create(fullUrl))
			  .header("X-MBX-APIKEY", binanceProperties.getKey())
			  .DELETE()
			  .build();

		HttpResponse<String> response = httpClient.client.send(request, HttpResponse.BodyHandlers.ofString());
		logger.info("▶ DELETE " + path + " 응답 코드 = " + response.statusCode());

		if (response.statusCode() != 200) {
			throw new RuntimeException("Binance API Error (DELETE " + path + "): "
				  + response.statusCode() + " – " + response.body());
		}
		return response.body();
	}

	/**
	 * 공통 POST 요청 (바디 없이 query string으로만 파라미터 전달)
	 */
	public String sendPostRequest(String path, Map<String, String> extraParams) throws Exception {
		long serverTime = getFuturesServerTime();
		MultiValueMap<String, String> allParams = new LinkedMultiValueMap<>();

		allParams.add("timestamp", String.valueOf(serverTime));
		allParams.add("recvWindow", String.valueOf(binanceProperties.getRecvWindow()));
		if (extraParams != null) {
			extraParams.forEach((k, v) -> {
				if (v != null && !v.isBlank()) {
					allParams.add(k, v);
				}
			});
		}

		String queryString = allParams.entrySet().stream()
			  .map(e -> e.getKey() + "=" + e.getValue().get(0))
			  .reduce((a, b) -> a + "&" + b)
			  .orElse("");

		String signature = SignatureUtil.generate(queryString, binanceProperties.getSecret());
		String fullUrl = binanceProperties.getBaseUrl() + path + "?" + queryString + "&signature=" + signature;

		HttpRequest request = HttpRequest.newBuilder()
			  .uri(URI.create(fullUrl))
			  .header("X-MBX-APIKEY", binanceProperties.getKey())
			  .POST(HttpRequest.BodyPublishers.noBody())
			  .build();

		HttpResponse<String> response = httpClient.client.send(request, HttpResponse.BodyHandlers.ofString());
		logger.info("▶ POST " + path + " 응답 코드 = " + response.statusCode());

		if (response.statusCode() != 200) {
			throw new RuntimeException("Binance API Error (POST " + path + "): "
				  + response.statusCode() + " – " + response.body());
		}
		return response.body();
	}
}