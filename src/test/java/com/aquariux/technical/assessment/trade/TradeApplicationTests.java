package com.aquariux.technical.assessment.trade;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "scheduler.price-update.enabled=false")
class TradeApplicationTests {

	@Test
	void contextLoads() {
	}

}
