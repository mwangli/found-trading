package online.mwang.stockTrading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @author 13255
 */
@EnableScheduling
@SpringBootApplication
public class FoundTradingApplication {

    public static void main(String[] args) {
        SpringApplication.run(FoundTradingApplication.class, args);
    }
}
