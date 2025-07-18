package com.trade.copy.binance.util;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Component
public class TelegramMessageSender {

	@Value("${telegram.bot-token}")
	private String botToken;

	@Value("${telegram.chat-id}")
	private String chatId;

	private final HttpClient client = HttpClient.newHttpClient();

	public void sendMessage(String message) {
		try {
			String encodedMessage = java.net.URLEncoder.encode(message, StandardCharsets.UTF_8);
			String url = String.format(
				  "https://api.telegram.org/bot%s/sendMessage?chat_id=%s&text=%s",
				  botToken, chatId, encodedMessage
			);

			HttpRequest request = HttpRequest.newBuilder()
				  .uri(URI.create(url))
				  .GET()
				  .build();

			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			System.out.println("Telegram 응답: " + response.body());
		} catch (Exception e) {
			System.err.println("Telegram 메시지 전송 실패: " + e.getMessage());
		}
	}
}
