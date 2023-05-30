package online.mwang.foundtrading.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @version 1.0.0
 * @author: mwangli
 * @date: 2023/3/20 13:22
 * @description: FoundTradingMapper
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunTokenJob extends BaseJob {

    private final DailyJob job;

    @Override
    public void run() {
        job.refreshToken();
    }
}