package com.trade.copy.binance.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

public class SignatureUtil {
	public static String generate(String data, String secret) throws Exception {
		Mac hmacSha256 = Mac.getInstance("HmacSHA256");
		SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		hmacSha256.init(keySpec);
		byte[] hash = hmacSha256.doFinal(data.getBytes(StandardCharsets.UTF_8));

		StringBuilder hex = new StringBuilder();
		for (byte b : hash) {
			String h = Integer.toHexString(0xff & b);
			if (h.length() == 1) hex.append('0');
			hex.append(h);
		}
		return hex.toString();
	}
}
