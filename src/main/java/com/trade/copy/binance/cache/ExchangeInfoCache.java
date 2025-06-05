package com.trade.copy.binance.cache;

import com.trade.copy.binance.helper.BinanceApiHelper;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 구동 시 한 번만 /fapi/v1/exchangeInfo API를 호출하여
 * 각 심볼별 MIN_NOTIONAL, LOT_SIZE precision 정보를 메모리에 캐싱합니다.
 * JVM이 살아있는 동안만 유지되는 인메모리 캐시입니다.
 */
@Component
@RequiredArgsConstructor
public class ExchangeInfoCache {

	private final BinanceApiHelper apiHelper;

	/**
	 * 심볼별 필터 정보(최소 거래 금액, 수량 소수점 자리수)를 담는 맵
	 *  key: 심볼명 (예: "BTCUSDT")
	 *  value: 해당 심볼의 SymbolFilterInfo 객체
	 */
	private final ConcurrentMap<String, SymbolFilterInfo> symbolInfoMap = new ConcurrentHashMap<>();

	@PostConstruct
	public void init() {
		try {
			// 1) exchangeInfo 호출 (파라미터 없음)
			String responseBody = apiHelper.sendGetRequest("/fapi/v1/exchangeInfo", Collections.emptyMap());
			JSONObject infoJson = new JSONObject(responseBody);
			JSONArray symbols = infoJson.getJSONArray("symbols");

			// 2) 각 심볼에 대해 MIN_NOTIONAL과 LOT_SIZE 필터를 찾아 맵에 저장
			for (int i = 0; i < symbols.length(); i++) {
				JSONObject s = symbols.getJSONObject(i);
				String symbol = s.getString("symbol");

				double minNotional = 0.0;
				int lotSizePrecision = 0;

				JSONArray filters = s.getJSONArray("filters");
				for (int j = 0; j < filters.length(); j++) {
					JSONObject f = filters.getJSONObject(j);
					String filterType = f.getString("filterType");

					if ("MIN_NOTIONAL".equals(filterType)) {
						minNotional = f.getDouble("notional");
					}
					else if ("LOT_SIZE".equals(filterType)) {
						// stepSize 예: "0.00100000" -> precision 3
						String stepSizeStr = f.getString("stepSize");
						lotSizePrecision = new BigDecimal(stepSizeStr)
							  .stripTrailingZeros()
							  .scale();
					}
				}

				// 기본값이 0인 경우가 없도록, 최소 거래 금액이 0.0이면 5.0으로 설정
				if (minNotional <= 0.0) {
					minNotional = 5.0;
				}
				// precision이 0일 때는 1 소수점 이하 자릿수(예: 소수 안 쓰는 경우)로 간주
				if (lotSizePrecision <= 0) {
					lotSizePrecision = 1;
				}

				symbolInfoMap.put(symbol, new SymbolFilterInfo(minNotional, lotSizePrecision));
			}

		} catch (Exception e) {
			throw new RuntimeException("ExchangeInfo 캐시 초기화 실패", e);
		}
	}

	/**
	 * 주어진 심볼의 SymbolFilterInfo 객체를 반환합니다.
	 * @param symbol (예: "BTCUSDT")
	 * @return 해당 심볼의 최소 거래 금액, 수량 precision 정보
	 */
	public SymbolFilterInfo getSymbolInfo(String symbol) {
		return symbolInfoMap.get(symbol);
	}

	/**
	 * 심볼별 필터 정보를 담는 단순 POJO
	 */
	@Getter
	public static class SymbolFilterInfo {
		private final double minNotional;
		private final int lotSizePrecision;

		public SymbolFilterInfo(double minNotional, int lotSizePrecision) {
			this.minNotional = minNotional;
			this.lotSizePrecision = lotSizePrecision;
		}
	}
}