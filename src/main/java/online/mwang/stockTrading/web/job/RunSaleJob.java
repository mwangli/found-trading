package online.mwang.stockTrading.web.job;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import online.mwang.stockTrading.web.bean.po.StockInfo;
import online.mwang.stockTrading.web.bean.po.TradingRecord;
import online.mwang.stockTrading.web.mapper.TradingRecordMapper;
import online.mwang.stockTrading.web.service.StockInfoService;
import online.mwang.stockTrading.web.service.TradingRecordService;
import online.mwang.stockTrading.web.utils.DateUtils;
import online.mwang.stockTrading.web.utils.SleepUtils;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * @version 1.0.0
 * @author: mwangli
 * @date: 2023/3/20 13:22
 * @description: FoundTradingMapper
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunSaleJob extends BaseJob {

    public static final int SOLD_RETRY_TIMES = 4;
    private final AllJobs jobs;
    private final TradingRecordService tradingRecordService;
    private final TradingRecordMapper tradingRecordMapper;
    private final StockInfoService stockInfoService;
    private final SleepUtils sleepUtils;
    private final double SALE_PERCENT = 0.05;

    @Override
    public void run() {
        // 查询所有持仓股票
        List<TradingRecord> holdTradingRecords = jobs.getHoldList();
        // 多线程异步卖出
        holdTradingRecords.forEach(tradingRecord -> new Thread(() -> saleStock(tradingRecord.getCode())).start());
    }

    private void saleStock(String stockCode) {
        double priceCount = 1;
        double priceTotal = jobs.getNowPrice(stockCode);
        StockInfo stockInfo = stockInfoService.getOne(new QueryWrapper<StockInfo>().lambda().eq(StockInfo::getCode, stockCode));
        TradingRecord findRecord = tradingRecordService.getOne(new QueryWrapper<TradingRecord>().lambda()
                .eq(TradingRecord::getCode, stockCode).eq(TradingRecord::getSold, "0"));
        while (jobs.inTradingTimes1()) {
            // 每隔30秒获取一次最新的价格
            sleepUtils.second(30);
            Double nowPrice = jobs.getNowPrice(stockCode);
            double priceAvg = priceTotal / priceCount;
            priceTotal += nowPrice;
            priceCount++;
            if (priceCount > 60 && nowPrice >= priceAvg + priceAvg * SALE_PERCENT) {
                log.info("当前股票[{}-{}]，出现最佳卖出价格，当前价格为：{}，前段时间的平均价格为{}", stockInfo.getName(), stockInfo.getCode(), nowPrice, priceAvg);
                // 返回合同编号
                JSONObject res = jobs.buySale("S", stockInfo.getCode(), nowPrice, findRecord.getBuyNumber());
                String saleNo = res.getString("ANSWERNO");
                if (saleNo == null) {
                    log.info("当前股票[{}-{}]卖出失败,尝试进行下次卖出", stockInfo.getName(), stockInfo.getCode());
                    continue;
                }
                // 查询卖出结果
                final Boolean success = jobs.waitOrderStatus(saleNo);
                if (success == null) {
                    log.info("当前股票[{}-{}]撤销订单失败,取消卖出任务！", stockInfo.getName(), stockCode);
                    return;
                }
                if (!success) {
                    log.info("当前股票[{}-{}]卖出失败,尝试再次卖出。", stockInfo.getName(), stockCode);
                    continue;
                }
                // 卖出成功
                findRecord.setSold("1");
                findRecord.setSaleNo(saleNo);
                final Date now = new Date();
                findRecord.setSaleDate(now);
                findRecord.setSaleDateString(DateUtils.dateFormat.format(now));
                findRecord.setUpdateTime(now);
                // 计算收益率
                // 更新每日数据
                final double amount = findRecord.getSalePrice() * findRecord.getSaleNumber();
                double saleAmount = amount - jobs.getPeeAmount(amount);
                double income = saleAmount - findRecord.getBuyAmount();
                double incomeRate = income / findRecord.getBuyAmount() * 100;
                findRecord.setSaleAmount(saleAmount);
                findRecord.setIncome(income);
                findRecord.setIncomeRate(incomeRate);
                findRecord.setHoldDays(1);
                findRecord.setDailyIncomeRate(incomeRate);
                tradingRecordService.updateById(findRecord);
                // 更新账户资金
                jobs.getAmount();
                // 增加股票交易次数
                stockInfo.setBuySaleCount(stockInfo.getBuySaleCount() + 1);
                stockInfoService.updateById(stockInfo);
                log.info("成功卖出股票[{}-{}], 卖出金额为:{}, 收益为:{},日收益率为:{}。", stockInfo.getCode(), stockInfo.getName(),
                        findRecord.getSaleAmount(), findRecord.getIncome(), findRecord.getDailyIncomeRate());
            }

        }
    }
}
