package com.trade.copy.binance.config;

import java.net.http.HttpClient;
import org.springframework.stereotype.Component;

@Component
public class BinanceHttpClient {
	public final HttpClient client = HttpClient.newHttpClient();
}
