package com.trade.copy.binance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BinanceCopyLeaderApplication {

	public static void main(String[] args) {
		SpringApplication.run(BinanceCopyLeaderApplication.class, args);
	}

}
