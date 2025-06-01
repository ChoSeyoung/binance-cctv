package com.trade.copy.binance.util;

import java.util.List;

public class Calculator {
	public static double calculateRsi(List<Double> closes, int period) {
		double gain = 0, loss = 0;

		for (int i = 1; i <= period; i++) {
			double diff = closes.get(i) - closes.get(i - 1);
			if (diff >= 0) gain += diff;
			else loss -= diff;
		}

		double avgGain = gain / period;
		double avgLoss = loss / period;

		double rs = avgGain / avgLoss;
		return 100 - (100 / (1 + rs));
	}

}
