package online.mwang.foundtrading.job;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import online.mwang.foundtrading.bean.po.*;
import online.mwang.foundtrading.mapper.AccountInfoMapper;
import online.mwang.foundtrading.mapper.ScoreStrategyMapper;
import online.mwang.foundtrading.mapper.StockInfoMapper;
import online.mwang.foundtrading.service.StockInfoService;
import online.mwang.foundtrading.service.TradingRecordService;
import online.mwang.foundtrading.utils.DateUtils;
import online.mwang.foundtrading.utils.OcrUtils;
import online.mwang.foundtrading.utils.RequestUtils;
import online.mwang.foundtrading.utils.SleepUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * @version 1.0.0
 * @author: mwangli
 * @date: 2023/5/22 10:32
 * @description: DailyJob
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyJob {

    // 最大持股数量，限制可买入股票的最大价格
    private static final int MAX_HOLD_NUMBER = 100;
    private static final int MIN_HOLD_NUMBER = 100;
    // 最大持仓股票数量
    private static final int MAX_HOLD_STOCKS = 10;
    // 最低价格系数，保证可买入股票价格不会过低
    private static final double LOW_PRICE_PERCENT = 0.9;
    // 最低价格限制，资金不足时不买入低价股
    private static final double LOW_PRICE_LIMIT = 5.0;
    private static final int BUY_RETRY_TIMES = 3;
    private static final int SOLD_RETRY_TIMES = 3;
    private static final int LOGIN_RETRY_TIMES = 10;
    private static final int PRICE_CONTINUE_FALL_LIMIT = 3;
    private static final int PRICE_TOTAL_FALL_LIMIT = 10;
    private static final int PRICE_CONTINUE_UPPER_LIMIT = 3;
    private static final int PRICE_TOTAL_UPPER_LIMIT = 10;
    private static final int BUY_RETRY_LIMIT = 20;
    private static final int WAIT_TIME_SECONDS = 10;
    private static final int HOLD_TIME_MINUTES = 5;
    private static final int WAIT_TIME_MINUTES = 10;
    private static final int HISTORY_PRICE_LIMIT = 100;
    private static final int UPDATE_BATCH_SIZE = 500;
    private static final int THREAD_POOL_NUMBERS = 8;
    private static final int TOKEN_EXPIRE_MINUTES = 30;
    private static final int CANCEL_WAIT_TIMES = 30;
    private static final String BUY_TYPE_OP = "B";
    private static final String SALE_TYPE_OP = "S";
    private static final String TOKEN = "requestToken";
    private static HashMap<String, Integer> dateMap;
    private final RequestUtils requestUtils;
    private final OcrUtils ocrUtils;
    private final StockInfoService stockInfoService;
    private final TradingRecordService tradingRecordService;
    private final AccountInfoMapper accountInfoMapper;
    private final StringRedisTemplate redisTemplate;
    private final StockInfoMapper stockInfoMapper;
    private final ScoreStrategyMapper strategyMapper;
    private final ExecutorService threadPool = Executors.newFixedThreadPool(THREAD_POOL_NUMBERS);

    public static HashMap<String, Object> buildParams(HashMap<String, Object> paramMap) {
        if (paramMap == null) return new HashMap<>();
        paramMap.put("cfrom", "H5");
        paramMap.put("tfrom", "PC");
        paramMap.put("newindex", "1");
        paramMap.put("MobileCode", "13278828091");
        paramMap.put("intacttoserver", "%40ClZvbHVtZUluZm8JAAAAN0EwOS1DMjdC");
        return paramMap;
    }

    // 每隔10分钟刷新Token
//    @Scheduled(fixedRate = 1000 * 60 * 25, initialDelay = 1000 * 60 * 5)
    public void runTokenJob() {
        log.info("开始执行刷新Token任务================================");
        buySale(SALE_TYPE_OP, "", 100.0, 100.0);
        log.info("刷新Token任务执行完毕================================");
    }

    // 每天登录一次
//    @Scheduled(fixedRate = 1000 * 60 * 25, initialDelay = 1000 * 60 * 5)
    public void runLoginJob() {
        log.info("开始执行账户登录任务================================");
        login();
        log.info("账户登录任务执行完毕================================");
    }

    // 交易日开盘时间买入 9:30
//    @Scheduled(cron = "0 0,15,30 9 ? * MON-FRI")
    public void runBuyJob() {
        log.info("开始执行买入任务====================================");
        buy();
        log.info("买入任务执行完毕====================================");
    }

    // 交易日收盘时间卖出 14:30
//    @Scheduled(cron = "0 0 10,11,14 ? * MON-FRI")
    public void runSaleJob() {
        log.info("开始执行卖出任务====================================");
        sale();
        log.info("卖出任务执行完毕====================================");
    }

    // 更新账户余额，交易时间段内每小时执行一次
//    @Scheduled(cron = "0 0 9-15 ? * MON-FRI")
    public void runAccountJob() {
        log.info("更新账户余额任务执行开始====================================");
        updateAccountAmount();
        log.info("更新账户余额任务执行结束====================================");
    }

    // 更新股票实时价格，交易日每天上午八点执行
//    @Scheduled(cron = "0 0 8 ? * MON-FRI")
    public void runNowJob() {
        log.info("更新股票实时价格任务执行开始====================================");
        updateNowPrice();
        log.info("更新股票实时价格任务执行结束====================================");
    }

    // 更新股票历史价格，交易日每天下午三点执行
//    @Scheduled(cron = "0 0 15 ? * MON-FRI")
    public void runHistoryJob() {
        log.info("更新股票历史价格任务执行开始====================================");
        updateHistoryPrice();
        log.info("更新股票历史价格任务执行结束====================================");
    }

    // 同步股票交易记录，每十天执行一次
//    @Scheduled(cron = "0 0 15 1/10 * ?")
    public void runSyncJob() {
        log.info("同步订单任务执行开始====================================");
        syncBuySaleRecord();
        syncBuySaleCount();
        log.info("同步订单执行结束====================================");
    }

    // 刷新股票交易权限，每半月月执行一次
//    @Scheduled(cron = "0 0 12 1,15 * ?")
    public void runFlushJob() {
        log.info("更新权限任务执行开始====================================");
        flushPermission();
        log.info("更新权限任务执行结束====================================");
    }

    public String getToken() {
        final String requestToken = redisTemplate.opsForValue().get(TOKEN);
        if (requestToken == null) {
            log.info("没有检测到Token，正在重新登录...");
            login();
        }
        return redisTemplate.opsForValue().get(TOKEN);
    }

    @SneakyThrows
    public String getCheckCodeFromMessage(String message) {
        String code = ocrUtils.execute(message);
        log.info("识别到图片验证码：{}", code);
        return code;
    }

    @SneakyThrows
    public List<String> getCheckCode() {
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("action", "41092");
        final JSONObject res = requestUtils.request(buildParams(paramMap));
        final String checkToken = res.getString("CHECKTOKEN");
        final String checkMessage = res.getString("MESSAGE");
        final String checkCode = getCheckCodeFromMessage(checkMessage);
        return Arrays.asList(checkCode, checkToken);
    }

    @SneakyThrows
    public void login() {
        int time = 0;
        while (time++ < LOGIN_RETRY_TIMES) {
            if (doLogin()) return;
            log.info("第{}次登录失败，正在尝试重新登录！", time);
        }
    }

    public Boolean doLogin() {
        final List<String> checkCode = getCheckCode();
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("action", "100");
        paramMap.put("modulus_id", "2");
        paramMap.put("accounttype", "ZJACCOUNT");
        paramMap.put("account", "880008900626");
        paramMap.put("MobileCode", "13278828091");
        paramMap.put("password", "9da08684daf6cda30ef11b8fecbaa568963749de0a0496e23fcfca4b9b29953e9703b1a7cc9fe9259f7ad732f2fa1bd09017ba884c0817667c458cb554dcbeb480623ad070c4d81a185dd997e45c9ab5ae655584728193759ec40e72ad7e25f833773f3e6cd0d23c16493765358adc593ce0688edccbefdd35a3355016b724fc");
        paramMap.put("signkey", "51cfce1626c7cb087b940a0c224f2caa");
        paramMap.put("CheckCode", checkCode.get(0));
        paramMap.put("CheckToken", checkCode.get(1));
        final JSONObject res = requestUtils.request(buildParams(paramMap));
        final String errorNo = res.getString("ERRORNO");
        final String token = res.getString("TOKEN");
        if (errorNo.equals("0")) {
            log.info("登录成功！");
            redisTemplate.opsForValue().set(TOKEN, token, TOKEN_EXPIRE_MINUTES, TimeUnit.MINUTES);
            return true;
        }
        return false;
    }

    private JSONArray getHoldList() {
        String token = getToken();
        long timeMillis = System.currentTimeMillis();
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("action", "117");
        paramMap.put("StartPos", 0);
        paramMap.put("MaxCount", 500);
        paramMap.put("reqno", timeMillis);
        paramMap.put("token", token);
        paramMap.put("Volume", 100);
        return requestUtils.request2(buildParams(paramMap));
    }

    private Boolean checkSoldToday(String sold) {
        // 检查今天是否有卖出记录，防止重复卖出
        LambdaQueryWrapper<TradingRecord> queryWrapper = new LambdaQueryWrapper<TradingRecord>()
                .eq(TradingRecord::getSaleDateString, DateUtils.dateFormat.format(new Date()))
                .eq(TradingRecord::getSold, sold);
        List<TradingRecord> hasSold = tradingRecordService.list(queryWrapper);
        return CollectionUtils.isNotEmpty(hasSold);
    }

    private Boolean checkBuyCode(String code) {
        // 检查今天是否有卖出记录，防止重复卖出
        LambdaQueryWrapper<TradingRecord> queryWrapper = new LambdaQueryWrapper<TradingRecord>()
                .eq(TradingRecord::getSold, "0")
                .eq(TradingRecord::getCode, code);
        List<TradingRecord> hasSold = tradingRecordService.list(queryWrapper);
        return CollectionUtils.isNotEmpty(hasSold);
    }

    public void buy() {
        int time = 0;
        while (time++ < BUY_RETRY_TIMES) {
            log.info("第{}次尝试买入股票---------", time);
            // 查询持仓股票数量
            final long holdCount = tradingRecordService.list(new LambdaQueryWrapper<TradingRecord>().eq(TradingRecord::getSold, "0")).size();
            final long needCount = MAX_HOLD_STOCKS - holdCount;
            if (needCount <= 0) {
                log.info("持仓股票数量已达到最大值:{}，无需购买!", MAX_HOLD_STOCKS);
                return;
            }
            if (checkSoldToday("0")) {
                log.info("今天已经有买入记录了，无需重复购买！");
                return;
            }
            // 撤销所有未成功订单，回收可用资金
            if (!waitOrderStatus()) {
                log.info("存在未撤销失败订单，取消购买任务！");
                return;
            }
            // 更新账户可用资金
            final AccountInfo accountInfo = updateAccountAmount();
            final Double totalAvailableAmount = accountInfo.getAvailableAmount();
            final Double totalAmount = accountInfo.getTotalAmount();
            final double maxAmount = totalAmount / MAX_HOLD_STOCKS;
            // 计算此次可用资金
            double availableAmount = totalAvailableAmount / needCount;
            availableAmount = Math.min(availableAmount, maxAmount);
            // 计算可买入股票价格区间
            final double highPrice = availableAmount / MIN_HOLD_NUMBER;
            final double lowPrice = (availableAmount / MAX_HOLD_NUMBER) * LOW_PRICE_PERCENT;
            log.info("当前可用资金{}元, 可买入价格区间[{},{}]", availableAmount, lowPrice, highPrice);
            if (lowPrice < LOW_PRICE_LIMIT) {
                log.info("可用资金资金不足，取消购买任务！");
                return;
            }
            //  获取实时价格
            final List<StockInfo> dataList = getDataList();
            // 计算得分
            List<StockInfo> stockInfos = calculateScore(dataList, getStrategyParams());
            // 选择有交易权限合适价格区间的数据，按评分排序分组
            final List<StockInfo> limitList = stockInfos.stream()
                    .sorted(Comparator.comparingDouble(StockInfo::getScore).reversed())
                    .filter(s -> "1".equals(s.getPermission()) && s.getPrice() >= lowPrice && s.getPrice() <= highPrice)
                    .skip((long) time * BUY_RETRY_LIMIT).limit(BUY_RETRY_LIMIT).collect(Collectors.toList());
            if (limitList.size() < BUY_RETRY_LIMIT) {
                log.info("可买入股票数量不足{}，取消购买任务！", BUY_RETRY_LIMIT);
                return;
            }
            // 在得分高的一组中随机选择一支买入
            StockInfo best = limitList.get(new Random(System.currentTimeMillis()).nextInt(BUY_RETRY_LIMIT));
            if (checkBuyCode(best.getCode())) {
                log.info("当前股票[{}-{}]已经持有，尝试买入下一组股票", best.getCode(), best.getName());
                continue;
            }
            log.info("当前买入最佳股票[{}-{}],价格:{},评分:{}", best.getCode(), best.getName(), best.getPrice(), best.getScore());
            // 等待最佳买入时机
            if (!waitingBestTime(best.getCode(), best.getName(), best.getPrice(), false)) {
                log.info("未找到合适的买入时机，尝试买入下一组股票!");
                continue;
            }
            final int maxBuyNumber = (int) (availableAmount / best.getPrice());
            final int buyNumber = (maxBuyNumber / 100) * 100;
            JSONObject res = buySale(BUY_TYPE_OP, best.getCode(), best.getPrice(), (double) buyNumber);
            String buyNo = res.getString("ANSWERNO");
            if (buyNo == null) {
                log.info("当前股票[{}-{}]买入失败，尝试买入下一组股票!", best.getCode(), best.getName());
                continue;
            }
            // 查询买入结果
            final Boolean success = waitOrderStatus(buyNo);
            if (success == null) {
                log.info("当前股票[{}-{}].订单撤销失败，取消买入任务！", best.getCode(), best.getName());
                return;
            }
            if (success) {
                // 买入成功后，保存交易数据
                final TradingRecord record = new TradingRecord();
                record.setCode(best.getCode());
                record.setName(best.getName());
                record.setBuyPrice(best.getPrice());
                record.setBuyNumber((double) buyNumber);
                record.setBuyNo(buyNo);
                final double amount = best.getPrice() * buyNumber;
                record.setBuyAmount(amount + getPeeAmount(amount));
                final Date now = new Date();
                record.setBuyDate(now);
                record.setBuyDateString(DateUtils.dateFormat.format(now));
                record.setSold("0");
                record.setCreateTime(now);
                record.setUpdateTime(now);
                // 保存选股策略ID
                final ScoreStrategy strategy = strategyMapper.getSelectedStrategy();
                record.setStrategyId(strategy == null ? 0 : strategy.getId());
                record.setStrategyName(strategy == null ? "默认策略" : strategy.getName());
                tradingRecordService.save(record);
                // 更新账户资金
                updateAccountAmount();
                // 更新交易次数
                stockInfos.stream().filter(s -> s.getCode().equals(best.getCode())).forEach(s -> s.setBuySaleCount(s.getBuySaleCount() + 1));
                log.info("成功买入股票[{}-{}], 买入价格:{}，买入数量:{}，买入金额:{}", record.getCode(), record.getName(), record.getBuyPrice(), record.getBuyNumber(), record.getBuyAmount());
                // 保存评分数据
                saveDate(stockInfos);
            } else {
                // 如果交易不成功，撤单后再次尝试卖出
                log.info("当前买入交易不成功，后尝试买入下一组股票。");
            }
        }
    }

    public void sale() {
        int time = 0;
        while (time++ < SOLD_RETRY_TIMES) {
            log.info("第{}次尝试卖出股票---------", time);
            if (checkSoldToday("1")) {
                log.info("今天已经有卖出记录了，无需重复卖出!");
                return;
            }
            // 撤销未成功订单
            if (!waitOrderStatus()) {
                log.info("存在未撤销失败订单，取消卖出任务！");
                return;
            }
            JSONArray dataList = getHoldList();
            double maxDailyRate = -100.0;
            TradingRecord best = null;
            for (int i = 1; i < dataList.size(); i++) {
                String data = dataList.getString(i);
                String[] split = data.split("\\|");
                String code = split[9];
                String name = split[0];
                // 持仓数量，持仓数量为0代表该股票今日已卖出
                double hasNumber = Double.parseDouble(split[1]);
                if (hasNumber == 0) {
                    log.info("当前股票[{}-{}]持有数量为0，已经卖出无需处理", code, name);
                    continue;
                }
                // 可用数量，已委托订单或者不在交易时间段内会导致可用数量为0
                double availableNumber = Double.parseDouble(split[2]);
                if (availableNumber == 0) {
                    log.info("当前股票[{}-{}]可卖出数量为0，请检查订单或者非交易时间段！", code, name);
                    continue;
                }
                double price = Double.parseDouble(split[4]);
                // 查询买入时间
                final LambdaQueryWrapper<TradingRecord> queryWrapper = new LambdaQueryWrapper<TradingRecord>().eq(TradingRecord::getCode, code).eq(TradingRecord::getSold, "0");
                TradingRecord selectRecord = tradingRecordService.getOne(queryWrapper);
                if (selectRecord == null) {
                    log.info("当前股票[{}-{}]未查询到买入记录,开始同步订单", code, name);
                    threadPool.submit(this::syncBuySaleRecord);
                    continue;
                }
                // 更新每日数据
                final double amount = price * availableNumber;
                double saleAmount = amount - getPeeAmount(amount);
                double income = saleAmount - selectRecord.getBuyAmount();
                int dateDiff = diffDate(selectRecord.getBuyDate(), new Date());
                double incomeRate = income / selectRecord.getBuyAmount() * 100;
                double dailyIncomeRate = incomeRate / dateDiff;
                log.info("当前股票[{}-{}]，买入金额:{}，卖出金额:{}，收益:{}元，日收益率:{}%", selectRecord.getCode(), selectRecord.getName(), selectRecord.getBuyAmount(), saleAmount, income, dailyIncomeRate);
                if (dailyIncomeRate > maxDailyRate) {
                    selectRecord.setSalePrice(price);
                    selectRecord.setSaleNumber(selectRecord.getBuyNumber());
                    selectRecord.setSaleAmount(saleAmount);
                    selectRecord.setIncome(income);
                    selectRecord.setIncomeRate(incomeRate);
                    selectRecord.setHoldDays(dateDiff);
                    selectRecord.setDailyIncomeRate(dailyIncomeRate);
                    maxDailyRate = dailyIncomeRate;
                    best = selectRecord;
                }
            }
            // 卖出最高收益的股票
            if (best == null) {
                log.info("无可卖出股票，无法进行卖出交易！");
                return;
            }
            log.info("最佳卖出股票[{}-{}]，买入价格:{}，当前价格:{}，预期收益:{}，日收益率:{}", best.getCode(), best.getName(), best.getBuyPrice(), best.getSalePrice(), best.getIncome(), String.format("%.4f", best.getDailyIncomeRate()));
            // 等待最佳卖出时机
            if (!waitingBestTime(best.getCode(), best.getName(), best.getBuyPrice(), true)) {
                log.info("未找到合适的卖出时机，尝试卖出下一组股票!");
                continue;
            }
            // 返回合同编号
            JSONObject res = buySale(SALE_TYPE_OP, best.getCode(), best.getSalePrice(), best.getBuyNumber());
            String saleNo = res.getString("ANSWERNO");
            if (saleNo == null) {
                log.info("当前股票[{}-{}]卖出失败，尝试买入下一组。", best.getCode(), best.getName());
                continue;
            }
            // 查询卖出结果
            final Boolean success = waitOrderStatus(saleNo);
            if (success == null) {
                log.info("当前股票[{}-{}]撤销订单失败，取消卖出任务！", best.getCode(), best.getName());
                return;
            }
            if (success) {
                best.setSold("1");
                best.setSaleNo(saleNo);
                final Date now = new Date();
                best.setSaleDate(now);
                best.setSaleDateString(DateUtils.dateFormat.format(now));
                best.setUpdateTime(now);
                tradingRecordService.updateById(best);
                // 更新账户资金
                updateAccountAmount();
                // 增加股票交易次数
                StockInfo stockInfo = stockInfoService.getOne(new QueryWrapper<StockInfo>().lambda().eq(StockInfo::getCode, best.getCode()));
                stockInfo.setBuySaleCount(stockInfo.getBuySaleCount() + 1);
                stockInfoService.updateById(stockInfo);
                log.info("成功卖出股票[{}-{}], 卖出金额为:{}, 收益为:{}，日收益率为:{}。", best.getCode(), best.getName(), best.getSaleAmount(), best.getIncome(), best.getDailyIncomeRate());
            } else {
                log.info("当前股票[{}-{}]卖出失败，尝试再次卖出。", best.getCode(), best.getName());
            }
        }

    }

    private Boolean waitingBestTime(String code, String name, Double buyPrice, Boolean sale) {
        int priceContinueFallCount = 0;
        int priceTotalFallCount = 0;
        int priceContinueUpperCount = 0;
        int priceTotalUpperCount = 0;
        int holdCount = 0;
        int timesCount = 0;
        String operation = sale ? "卖出" : "买入";
        String upperFallKey = sale ? "上涨" : "跌落";
        String fallUpperKey = sale ? "跌落" : "上涨";
        int totalLimit = sale ? PRICE_TOTAL_FALL_LIMIT : PRICE_TOTAL_UPPER_LIMIT;
        int continueLimit = sale ? PRICE_CONTINUE_FALL_LIMIT : PRICE_CONTINUE_UPPER_LIMIT;
        Double nowPrice = getLastPrice(code);
        while (timesCount++ < 6 * WAIT_TIME_MINUTES) {
            SleepUtils.second(WAIT_TIME_SECONDS);
            Double lastPrice = getLastPrice(code);
            final boolean priceUpper = lastPrice > nowPrice;
            final boolean priceFall = lastPrice < nowPrice;
            nowPrice = lastPrice;
            if (sale ? priceUpper : priceFall) {
                // 如果是连续上涨，代表还有更大上升空间，则减少总下降次数以获取更大收益
                if (priceContinueUpperCount > 0) {
                    priceContinueUpperCount--;
                }
                priceTotalUpperCount++;
                priceContinueUpperCount++;
                priceContinueFallCount = 0;
                holdCount = 0;
            } else if (sale ? priceFall : priceUpper) {
                priceTotalFallCount++;
                priceContinueFallCount++;
                priceContinueUpperCount = 0;
                holdCount = 0;
            } else {
                holdCount++;
                priceContinueFallCount = 0;
                priceContinueUpperCount = 0;
            }
            log.info("最佳{}股票[{}-{}]，买入价格：{}, 当前价格：{}，总{}次数：{}，连续{}次数：{}，总{}数：{}，连续{}次数{}，等待最佳{}时机...",
                    operation, code, name, buyPrice, nowPrice, upperFallKey, priceTotalUpperCount, upperFallKey, priceContinueUpperCount, fallUpperKey, priceTotalFallCount, fallUpperKey, priceContinueFallCount, operation);
            // 总跌落10次或者连续跌落3次，代表价格上涨已达到峰值，开始卖出
            boolean incomeCondition = nowPrice - buyPrice > 0.1;
            boolean priceCondition = priceTotalFallCount > totalLimit || priceContinueFallCount > continueLimit;
            boolean saleCondition = incomeCondition && priceCondition;
            if (sale ? saleCondition : priceCondition) {
                log.info("最佳{}股票[{}-{}]，总{}数达到{}，或者连续{}次数达到{}，开始{}股票。", operation, code, name, fallUpperKey, totalLimit, fallUpperKey, continueLimit, operation);
                return true;
            }
            if (holdCount > 6 * HOLD_TIME_MINUTES) {
                log.info("最佳{}股票[{}-{}]，价格在{}分钟内无变化!", operation, code, name, HOLD_TIME_MINUTES);
                return false;
            }
        }
        return false;
    }
    // 获取持仓股票

    private Double getLastPrice(String code) {
        long timeMillis = System.currentTimeMillis();
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("stockcode", code);
        paramMap.put("Reqno", timeMillis);
        paramMap.put("action", 33);
        paramMap.put("ReqlinkType", 1);
        paramMap.put("Level", 1);
        paramMap.put("UseBPrice", 1);
        JSONObject res = requestUtils.request(buildParams(paramMap));
        return res.getDouble("PRICE");
    }

    public List<StockInfo> calculateScore(List<StockInfo> dataList, StrategyParams params) {
        List<StockInfo> stockInfos = stockInfoService.list();
        stockInfos.forEach(info -> dataList.stream().filter(s -> s.getCode().equals(info.getCode())).findFirst().ifPresent(p -> {
            Double nowPrice = p.getPrice();
            List<DailyItem> priceList = JSON.parseArray(info.getPrices(), DailyItem.class);
            List<DailyItem> rateList = JSON.parseArray(info.getIncreaseRate(), DailyItem.class);
            Double score = handleScore(nowPrice, priceList, rateList, params);
            info.setScore(score);
            info.setPrice(p.getPrice());
            info.setIncrease(p.getIncrease());
            info.setUpdateTime(new Date());
        }));
        return stockInfos;
    }

    public void cancelOrder(String answerNo) {
        String token = getToken();
        final long timeMillis = System.currentTimeMillis();
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("action", "111");
        paramMap.put("ContactID", answerNo);
        paramMap.put("token", token);
        paramMap.put("reqno", timeMillis);
        final JSONObject result = requestUtils.request(buildParams(paramMap));
        final String newToken = result.getString("TOKEN");
        redisTemplate.opsForValue().set("requestToken", newToken, TOKEN_EXPIRE_MINUTES, TimeUnit.MINUTES);
    }

    // 更新账户资金
    public AccountInfo updateAccountAmount() {
        String token = getToken();
        final long timeMillis = System.currentTimeMillis();
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("action", 116);
        paramMap.put("ReqlinkType", 1);
        paramMap.put("token", token);
        paramMap.put("reqno", timeMillis);
        final JSONArray jsonArray = requestUtils.request2(buildParams(paramMap));
        final String string = jsonArray.getString(1);
        // 币种|余额|可取|可用|总资产|证券|基金|冻结资金|资产|资金账户|币种代码|账号主副标志|
        final String[] split = string.split("\\|");
        final Double availableAmount = Double.parseDouble(split[3]);
        final Double totalAmount = Double.parseDouble(split[4]);
        final Double usedAmount = Double.parseDouble(split[5]);
        final AccountInfo accountInfo = new AccountInfo();
        accountInfo.setAvailableAmount(availableAmount);
        accountInfo.setUsedAmount(usedAmount);
        accountInfo.setTotalAmount(totalAmount);
        final Date now = new Date();
        accountInfo.setCreateTime(now);
        accountInfo.setUpdateTime(now);
        accountInfoMapper.insert(accountInfo);
        log.info("当前可用金额：{}元，持仓金额：{}元，总金额：{}元。", availableAmount, usedAmount, totalAmount);
        return accountInfo;
    }

    private List<OrderStatus> arrayToList(JSONArray result, boolean isToday) {
//        委托日期|时间|证券代码|证券|委托类别|买卖方向|状态|委托|数量|委托编号|均价|成交|股东代码|交易类别|
        ArrayList<OrderStatus> statusList = new ArrayList<>();
        if (result != null && result.size() > 1) {
            for (int i = 1; i < result.size(); i++) {
                String string = result.getString(i);
                String[] split = string.split("\\|");
                String code = isToday ? split[0] : split[2];
                String name = isToday ? split[1] : split[3];
                String status = isToday ? split[2] : split[6];
                String answerNo = isToday ? split[8] : split[9];
                OrderStatus orderStatus = new OrderStatus(answerNo, code, name, status);
                statusList.add(orderStatus);
            }
        }
        return statusList;
    }

    public List<OrderStatus> pageCancelOrder(int page) {
        String token = getToken();
        final long timeMillis = System.currentTimeMillis();
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("action", 152);
        paramMap.put("StartPos", page * 500);
        paramMap.put("MaxCount", 500);
        paramMap.put("op_station", 4);
        paramMap.put("token", token);
        paramMap.put("reqno", timeMillis);
        JSONArray result = requestUtils.request2(buildParams(paramMap));
        return arrayToList(result, true);
    }

    public List<OrderStatus> listCancelOrder() {
        List<OrderStatus> cancelOrders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            cancelOrders.addAll(pageCancelOrder(i));
        }
        return cancelOrders;
    }

    public void cancelAllOrder() {
        List<OrderStatus> orderList = listCancelOrder();
        log.info("待撤销订单：{}", orderList);
        orderList.forEach(o -> cancelOrder(o.getAnswerNo()));
    }

    public List<OrderStatus> listTodayOrder() {
        String token = getToken();
        final long timeMillis = System.currentTimeMillis();
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("action", 113);
        paramMap.put("StartPos", 0);
        paramMap.put("MaxCount", 500);
        paramMap.put("token", token);
        paramMap.put("ReqlinkType", 1);
        paramMap.put("reqno", timeMillis);
        JSONArray result = requestUtils.request2(buildParams(paramMap));
        return arrayToList(result, true);
    }

    public List<OrderStatus> listHistoryOrder() {
        String token = getToken();
        final long timeMillis = System.currentTimeMillis();
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("action", 5018);
        paramMap.put("StartPos", 0);
        paramMap.put("MaxCount", 100);
        paramMap.put("token", token);
        paramMap.put("ReqlinkType", 1);
        paramMap.put("reqno", timeMillis);
        JSONArray result = requestUtils.request2(buildParams(paramMap));
        return arrayToList(result, false);
    }


    public String queryOrderStatus(String answerNo) {
        List<OrderStatus> orderInfos = listTodayOrder();
        log.info("查询到订单状态信息：{}", orderInfos);
        Optional<OrderStatus> status = orderInfos.stream().filter(o -> o.getAnswerNo().equals(answerNo)).findFirst();
        if (status.isEmpty()) {
            log.info("未查询到合同编号为{}的订单交易状态！", answerNo);
            return null;
        }
        return status.get().getStatus();
    }

    public Boolean waitOrderStatus() {
        int times = 0;
        while (times++ < CANCEL_WAIT_TIMES) {
            SleepUtils.second(WAIT_TIME_SECONDS);
            List<OrderStatus> todayOrders = listTodayOrder();
            List<OrderStatus> historyOrders = listHistoryOrder();
            todayOrders.addAll(historyOrders);
            List<String> cancelStatus = Arrays.asList("已报", "已报待撤");
            List<OrderStatus> orderList = todayOrders.stream().filter(o -> cancelStatus.contains(o.getStatus())).collect(Collectors.toList());
            if (orderList.size() == 0) {
                return true;
            } else {
                log.info("待撤销订单：{}", orderList);
                orderList.forEach(o -> cancelOrder(o.getAnswerNo()));
            }
        }
        return false;
    }

    public Boolean waitOrderStatus(String answerNo) {
        int times = 0;
        while (times++ < CANCEL_WAIT_TIMES) {
            SleepUtils.second(WAIT_TIME_SECONDS);
            final String status = queryOrderStatus(answerNo);
            if (status == null) {
                log.info("当前合同编号：{}，订单状态查询失败。", answerNo);
                return null;
            }
            if ("已成".equals(status)) {
                log.info("当前合同编号：{}，交易成功。", answerNo);
                return true;
            }
            if ("已报".equals(status)) {
                log.info("当前合同编号：{}，交易不成功，进行撤单操作。", answerNo);
                cancelOrder(answerNo);
            }
            if ("已报待撤".equals(status)) {
                log.info("当前合同编号：{}，等待撤单完成...", answerNo);
            }
            if ("已撤".equals(status)) {
                log.info("当前合同编号：{}，订单撤销完成", answerNo);
                return false;
            }
        }
        return null;
    }


    public JSONObject buySale(String type, String code, Double price, Double number) {
        String token = getToken();
        final long timeMillis = System.currentTimeMillis();
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("action", 110);
        paramMap.put("PriceType", 0);
        paramMap.put("Direction", type);
        paramMap.put("StockCode", code);
        paramMap.put("Price", price);
        paramMap.put("Volume", number);
        paramMap.put("token", token);
        paramMap.put("reqno", timeMillis);
        final JSONObject result = requestUtils.request(buildParams(paramMap));
        final String newToken = result.getString("TOKEN");
        redisTemplate.opsForValue().set("requestToken", newToken, TOKEN_EXPIRE_MINUTES, TimeUnit.MINUTES);
        return result;
    }

    // 获取每日最新价格数据
    public List<StockInfo> getDataList() {
        final List<StockInfo> stockInfos = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            HashMap<String, Object> paramMap = new HashMap<>();
            paramMap.put("c.funcno", 21000);
            paramMap.put("c.version", 1);
            paramMap.put("c.sort", 1);
            paramMap.put("c.order", 0);
            paramMap.put("c.type", "0:2:9:18");
            paramMap.put("c.curPage", i);
            paramMap.put("c.rowOfPage", 500);
            paramMap.put("c.field", "1:2:22:23:24:3:8:16:21:31");
            paramMap.put("c.cfrom", "H5");
            paramMap.put("c.tfrom", "PC");
            final JSONArray results = requestUtils.request3(buildParams(paramMap));
            for (int j = 0; j < results.size(); j++) {
                final String s = results.getString(j);
                final String[] split = s.split(",");
                final String increase = split[0].replaceAll("\\[", "");
                final double increasePercent = Double.parseDouble(increase) * 100;
                final Double price = Double.parseDouble(split[1]);
                final String name = split[2].replaceAll("\"", "");
                final String market = split[3].replaceAll("\"", "");
                final String code = split[4].replaceAll("\"", "");
                final StockInfo stockInfo = new StockInfo();
                stockInfo.setName(name);
                stockInfo.setCode(code);
                stockInfo.setMarket(market);
                stockInfo.setIncrease(increasePercent);
                stockInfo.setPrice(price);
                stockInfos.add(stockInfo);
            }
        }
        log.info("共获取到{}条新数据。", stockInfos.size());
        return stockInfos;
    }

    @SneakyThrows
    public void syncBuySaleRecord() {
        // 请求数据
        List<OrderInfo> lastOrders = getLastOrder();
        List<OrderInfo> todayOrders = getTodayOrder();
        lastOrders.addAll(todayOrders);
        for (OrderInfo order : lastOrders) {
            final String date = order.getDate();
            final String time = order.getTime();
            final String answerNo = order.getAnswerNo();
            final String code = order.getCode();
            final String name = order.getName();
            final String type = order.getType();
            final Double price = order.getPrice();
            Double number = order.getNumber();
            final String dateString = date + (time.length() < 6 ? ("0" + time) : time);
            if ("买入".equals(type)) {
                // 查询买入订单信息是否存在
                final LambdaQueryWrapper<TradingRecord> lambdaQueryWrapper = new QueryWrapper<TradingRecord>().lambda()
                        .eq(TradingRecord::getCode, code).like(TradingRecord::getBuyNo, answerNo);
                final TradingRecord selectedOrder = tradingRecordService.getOne(lambdaQueryWrapper);
                if (selectedOrder == null) {
                    log.info("当前股票[{}-{}]买入记录不存在，新增买入记录", name, code);
                    // 合并多个买入订单
                    final LambdaQueryWrapper<TradingRecord> queryWrapper = new QueryWrapper<TradingRecord>().lambda()
                            .eq(TradingRecord::getCode, code).eq(TradingRecord::getSold, "0");
                    final TradingRecord selectedRecord = tradingRecordService.getOne(queryWrapper);
                    if (selectedRecord == null) {
                        final TradingRecord record = new TradingRecord();
                        record.setCode(code);
                        record.setName(name);
                        record.setBuyPrice(price);
                        record.setBuyNumber(number);
                        final double amount = price * number;
                        record.setBuyAmount(amount + getPeeAmount(amount));
                        final Date buyDate = DateUtils.dateTimeFormat.parse(dateString);
                        record.setBuyDate(buyDate);
                        record.setBuyDateString(date);
                        record.setBuyNo(answerNo);
                        record.setSold("0");
                        record.setStrategyId(0L);
                        record.setStrategyName("默认策略");
                        final Date now = new Date();
                        record.setCreateTime(now);
                        record.setUpdateTime(now);
                        tradingRecordService.save(record);
                    } else {
                        selectedRecord.setBuyPrice(price);
                        selectedRecord.setBuyNumber(number + selectedRecord.getBuyNumber());
                        final double amount = price * number;
                        selectedRecord.setBuyAmount(selectedRecord.getBuyAmount() + amount + getPeeAmount(amount));
                        final Date buyDate = DateUtils.dateTimeFormat.parse(dateString);
                        selectedRecord.setBuyDate(buyDate);
                        selectedRecord.setBuyDateString(date);
                        selectedRecord.setBuyNo(selectedRecord.getBuyNo() + "," + answerNo);
                        final Date now = new Date();
                        selectedRecord.setUpdateTime(now);
                        tradingRecordService.updateById(selectedRecord);
                    }
                }
            }
            if ("卖出".equals(type)) {
                // 查询卖出订单信息是否存在
                final LambdaQueryWrapper<TradingRecord> lambdaQueryWrapper = new QueryWrapper<TradingRecord>().lambda()
                        .eq(TradingRecord::getCode, code).eq(TradingRecord::getSaleNo, answerNo);
                final TradingRecord selectedOrder = tradingRecordService.getOne(lambdaQueryWrapper);
                if (selectedOrder == null) {
                    log.info("当前股票[{}-{}]卖出记录不存在，新增卖出记录", name, code);
                    final LambdaQueryWrapper<TradingRecord> queryWrapper = new QueryWrapper<TradingRecord>().lambda()
                            .eq(TradingRecord::getCode, code).eq(TradingRecord::getSold, "0");
                    final TradingRecord record = tradingRecordService.getOne(queryWrapper);
                    if (record == null) {
                        log.error("当前股票[{}-{}]没有查询到买入记录，卖出记录同步失败！", name, code);
                        return;
                    }
                    record.setSalePrice(price);
                    number = Math.abs(number);
                    record.setSaleNumber(number);
                    final double amount = price * number;
                    record.setSaleAmount(amount - getPeeAmount(amount));
                    final Date saleDate = DateUtils.dateTimeFormat.parse(dateString);
                    record.setSaleDate(saleDate);
                    record.setSaleDateString(date);
                    record.setSold("1");
                    record.setSaleNo(answerNo);
                    final Date now = new Date();
                    record.setUpdateTime(now);
                    // 计算收益和日收益率
                    double income = record.getSaleAmount() - record.getBuyAmount();
                    record.setIncome(income);
                    int dateDiff = diffDate(record.getBuyDate(), record.getSaleDate());
                    record.setHoldDays(dateDiff);
                    double incomeRate = income / record.getBuyAmount() * 100;
                    record.setIncomeRate(incomeRate);
                    final double dailyIncomeRate = incomeRate / dateDiff;
                    record.setDailyIncomeRate(dailyIncomeRate);
                    tradingRecordService.update(record, queryWrapper);
                }
            }
        }
        log.info("已同步{}条订单交易记录", lastOrders.size());
    }

    @SneakyThrows
    public void flushPermission() {
        // 此处不能使用多线程处理，因为每次请求会使上一个Token失效
        List<String> errorCodes = Arrays.asList("[251112]", "[251127]", "[251299]", "该股票是退市");
        List<StockInfo> stockInfos = stockInfoService.list();
        final HashSet<String> set = new HashSet<>();
        AtomicInteger count = new AtomicInteger();
        stockInfos.forEach(info -> {
            JSONObject res = buySale(BUY_TYPE_OP, info.getCode(), 100.0, 100.0);
            final String message = res.getString("ERRORMESSAGE");
            set.add(message);
            if (errorCodes.stream().anyMatch(message::startsWith)) {
                info.setPermission("0");
            } else {
                info.setPermission("1");
            }
            stockInfoMapper.updateById(info);
            if (count.incrementAndGet() % 100 == 0) {
                log.info("已更新{}条股票交易权限记录。", count.get());
            }
        });
        log.info("交易权限错误信息合集：{}", set);
        // 取消所有提交的订单
        cancelAllOrder();
    }

    @SneakyThrows
    public void syncBuySaleCount() {
        List<TradingRecord> list = tradingRecordService.list();
        Map<String, IntSummaryStatistics> collect = list.stream().collect(Collectors.groupingBy(TradingRecord::getCode, Collectors.summarizingInt((o) -> "1".equals(o.getSold()) ? 2 : 1)));
        collect.forEach((code, accumulate) -> {
            StockInfo stockInfo = new StockInfo();
            stockInfo.setBuySaleCount((int) accumulate.getSum());
            stockInfoService.update(stockInfo, new QueryWrapper<StockInfo>().lambda().eq(StockInfo::getCode, code));
        });
        log.info("共同步{}条股票交易次数", collect.size());
        list.stream().filter(r -> r.getSold().equals("1")).forEach(record -> {
            Double income = record.getIncome();
            Double buyAmount = record.getBuyAmount();
            double incomeRate = (income * 100) / buyAmount;
            record.setIncomeRate(incomeRate);
            double dailyRate = incomeRate / record.getHoldDays();
            record.setDailyIncomeRate(dailyRate);
            tradingRecordService.updateById(record);
        });
    }

    @SneakyThrows
    public void updateHistoryPrice() {
        final List<StockInfo> stockInfos = stockInfoService.list();
        final CountDownLatch countDownLatch = new CountDownLatch(stockInfos.size());
        // 多线程请求数据
        ArrayList<StockInfo> saveList = new ArrayList<>();
        stockInfos.forEach(s -> threadPool.submit(() -> {
            try {
                List<DailyItem> historyPrices = getHistoryPrices(s.getCode());
                List<DailyItem> rateList = getRateList(historyPrices);
                s.setPrices(JSON.toJSONString(historyPrices));
                s.setIncreaseRate(JSON.toJSONString(rateList));
                s.setUpdateTime(new Date());
                saveList.add(s);
                final long finishNums = stockInfos.size() - countDownLatch.getCount() + 1;
                if (finishNums % 100 == 0) {
                    log.info("已完成{}个获取股票历史价格任务,剩余{}个任务", finishNums, countDownLatch.getCount() + 1);
                }
            } catch (Exception e) {
                e.printStackTrace();
                log.error("获取数据异常：{}", e.getMessage());
            } finally {
                countDownLatch.countDown();
            }

        }));
        countDownLatch.await();
        saveDate(saveList);
    }

    private StrategyParams getStrategyParams() {
        // 获取策略默认值
        StrategyParams strategyParams = new StrategyParams(0.5, 5, 50);
        ScoreStrategy strategy = strategyMapper.getSelectedStrategy();
        if (strategy != null) {
            try {
                String params = strategy.getParams();
                String[] split = params.split("\\|");
                strategyParams = new StrategyParams(Double.parseDouble(split[0].trim()), Integer.parseInt(split[1].trim()), Integer.parseInt(split[2].trim()));
            } catch (Exception e) {
                log.warn("策略参数解析异常，使用默认参数：{}", strategyParams);
            }
        }
        return strategyParams;
    }

    @SneakyThrows
    public void updateNowPrice() {
        List<StockInfo> newInfos = getDataList();
        final List<StockInfo> dataList = stockInfoService.list();
        final ArrayList<StockInfo> saveList = new ArrayList<>();
        final StrategyParams params = getStrategyParams();
        newInfos.forEach(newInfo -> {
            AtomicBoolean exist = new AtomicBoolean(false);
            dataList.stream().filter(s -> s.getCode().equals(newInfo.getCode())).findFirst().ifPresent(p -> {
                Double nowPrice = newInfo.getPrice();
                List<DailyItem> priceList = JSON.parseArray(p.getPrices(), DailyItem.class);
                List<DailyItem> rateList = JSON.parseArray(p.getIncreaseRate(), DailyItem.class);
                Double score = handleScore(nowPrice, priceList, rateList, params);
                p.setScore(score);
                p.setPrice(newInfo.getPrice());
                p.setIncrease(newInfo.getIncrease());
                p.setUpdateTime(new Date());
                saveList.add(p);
                exist.set(true);
            });
            if (!exist.get()) {
                Date now = new Date();
                newInfo.setCreateTime(now);
                newInfo.setUpdateTime(now);
                newInfo.setPermission("1");
                newInfo.setBuySaleCount(0);
                newInfo.setScore(0.0);
                newInfo.setPrices("[]");
                newInfo.setIncreaseRate("[]");
                saveList.add(newInfo);
                log.info("获取到新数据：{}", newInfo);
            }
        });
        saveDate(saveList);
    }

    private Double handleScore(Double nowPrice, List<DailyItem> priceList, List<DailyItem> rateList, StrategyParams params) {
        if (priceList != null && priceList.size() > 0) {
            Double prePrice = priceList.get(priceList.size() - 1).getItem();
            double increaseRate = (nowPrice - prePrice) / prePrice;
            priceList.add(new DailyItem(DateUtils.dateFormat.format(new Date()), nowPrice));
            rateList.add(new DailyItem(DateUtils.dateFormat.format(new Date()), increaseRate));
            List<Double> prices = priceList.stream().map(DailyItem::getItem).collect(Collectors.toList());
            List<Double> rates = rateList.stream().map(DailyItem::getItem).collect(Collectors.toList());
            return getScoreByList(prices, rates, params);
        }
        return 0.0;
    }


    @SneakyThrows
    public void saveDate(List<StockInfo> dataList) {
        log.info("开始更新数据库......");
        if (CollectionUtils.isNotEmpty(dataList)) {
            // 多线程批量更新数据库
            int pages = dataList.size() / UPDATE_BATCH_SIZE + 1;
            CountDownLatch countDownLatch = new CountDownLatch(pages);
            log.info("共{}条数据, 分{}页进行批量更新,每页{}条数据", dataList.size(), pages, UPDATE_BATCH_SIZE);
            for (int i = 1; i <= pages; i++) {
                int finalI = i;
                threadPool.submit(() -> {
                    try {
                        long startIndex = (long) (finalI - 1) * UPDATE_BATCH_SIZE;
                        final List<StockInfo> saveList = dataList.stream().skip(startIndex).limit(UPDATE_BATCH_SIZE).collect(Collectors.toList());
                        stockInfoService.saveOrUpdateBatch(saveList);
                        long endIndex = startIndex + UPDATE_BATCH_SIZE;
                        endIndex = Math.min(endIndex, dataList.size());
                        log.info("第{}个数据更新任务处理完成，任务更新范围[{},{}]内,共{}条数", pages - countDownLatch.getCount() + 1, startIndex + 1, endIndex, endIndex - startIndex);
                    } catch (Exception e) {
                        e.printStackTrace();
                        log.error("保存数据异常：{}", e.getMessage());
                    } finally {
                        countDownLatch.countDown();
                    }
                });
            }
            countDownLatch.await();
            log.info("数据库更新完成......");
        }
    }

    // 获取历史价格曲线
    public List<DailyItem> getHistoryPrices(String code) {
        // 获取历史数据
        HashMap<String, Object> paramMap = new HashMap<>(10);
        paramMap.put("c.funcno", 20009);
        paramMap.put("c.version", 1);
        paramMap.put("c.stock_code", code);
        paramMap.put("c.type", "day");
        paramMap.put("c.count", "20");
        paramMap.put("c.cfrom", "H5");
        paramMap.put("c.tfrom", "PC");
        final JSONArray results = requestUtils.request3(buildParams(paramMap));
        final ArrayList<DailyItem> prices = new ArrayList<>();
        int startIndex = results.size() >= HISTORY_PRICE_LIMIT ? results.size() - HISTORY_PRICE_LIMIT : 0;
        for (int i = startIndex; i < results.size(); i++) {
            final String s = results.getString(i);
            final String[] split = s.split(",");
            final String date = split[0].replaceAll("\\[", "");
            final String price1 = split[1];
            final String price2 = split[2];
            final String price3 = split[3];
            final String price4 = split[4].replaceAll("]", "");
//            prices.add(new DailyItem(date.concat("-1"), Double.parseDouble(price1) / 100));
//            prices.add(new DailyItem(date.concat("-2"), Double.parseDouble(price2) / 100));
//            prices.add(new DailyItem(date.concat("-3"), Double.parseDouble(price3) / 100));
            prices.add(new DailyItem(date, Double.parseDouble(price1) / 100));
        }
        return prices;
    }

    // 获取最近一个月的成交订单订单
    public List<OrderInfo> getLastOrder() {
        final long timeMillis = System.currentTimeMillis();
        final String token = getToken();
        final Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());
        final String end = DateUtils.dateFormat.format(calendar.getTime());
        calendar.add(Calendar.MONTH, -1);
        final String start = DateUtils.dateFormat.format(calendar.getTime());
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("action", 115);
        paramMap.put("ReqlinkType", 1);
        paramMap.put("StartPos", 0);
        paramMap.put("MaxCount", 500);
//        paramMap.put("BeginDate", start);
//        paramMap.put("EndDate", end);
        paramMap.put("token", token);
        paramMap.put("reqno", timeMillis);
        final JSONArray results = requestUtils.request2(buildParams(paramMap));
        return arrayToOrderList(results, false);
    }

    // 获取今日成交订单
    public List<OrderInfo> getTodayOrder() {
        final long timeMillis = System.currentTimeMillis();
        final String token = getToken();
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("action", 114);
        paramMap.put("ReqlinkType", 1);
        paramMap.put("StartPos", 0);
        paramMap.put("MaxCount", 100);
        paramMap.put("token", token);
        paramMap.put("reqno", timeMillis);
        final JSONArray results = requestUtils.request2(buildParams(paramMap));
        return arrayToOrderList(results, true);
    }

    private List<OrderInfo> arrayToOrderList(JSONArray results, boolean isToday) {
        final ArrayList<OrderInfo> orderList = new ArrayList<>();
        for (int i = 1; i < results.size(); i++) {
            final String result = results.getString(i);
            final String[] split = result.split("\\|");
            final String name = isToday ? split[0] : split[5];
            final String type = isToday ? split[1] : split[6];
            final String number = isToday ? split[2] : split[8];
            final String price = isToday ? split[3] : split[7];
            final String code = isToday ? split[5] : split[4];
            final String answerNo = isToday ? split[6] : split[1];
            final String date = isToday ? split[10] : split[0];
            final String time = split[11];
            if ("0".equals(answerNo) || "799999".equals(code)) {
                // 非买卖信息，跳过处理
                continue;
            }
            OrderInfo orderInfo = new OrderInfo();
            orderInfo.setAnswerNo(answerNo);
            orderInfo.setCode(code);
            orderInfo.setName(name);
            orderInfo.setDate(date);
            orderInfo.setTime(time);
            orderInfo.setType(type);
            orderInfo.setPrice(Double.parseDouble(price));
            orderInfo.setNumber(Double.parseDouble(number));
            orderList.add(orderInfo);
        }
        return orderList;
    }

    // 计算价格日增长率曲线
    public List<DailyItem> getRateList(List<DailyItem> prices) {
        final ArrayList<DailyItem> rateList = new ArrayList<>();
        for (int i = 1; i < prices.size(); i++) {
            final Double price = prices.get(i).getItem();
            final Double oldPrice = prices.get(i - 1).getItem();
            final double dailyIncreaseRate = oldPrice == 0 ? 0 : (price - oldPrice) / oldPrice;
            // 增长率乘100方便计算，否则数值太小
            double increaseRate = Double.parseDouble(String.format("%.4f", dailyIncreaseRate * 100));
            rateList.add(new DailyItem(prices.get(i).getDate(), increaseRate));
        }
        return rateList;
    }

    // 获取最近10个交易日天的日增长率，用以计算增长的稳定性和增长率总和
    // 价格稳定性用方差来衡量,增长率总和体现增长幅度
    // 增长率总和，乘以占比系数(index/size)，离当前时间越近,趋近于1，离当前时间越远，系数趋近于0
    // 增长的天数越多，日增长率总和越大，价格方差越小，增长波动越小，代表稳定增长，评分越高
    public Double getScoreByList(List<Double> priceList, List<Double> rateList, StrategyParams params) {
        if (priceList.isEmpty() || rateList.isEmpty()) {
            return 0.0;
        }
        Double preRateFactor = params.getPreRateFactor();
        Integer priceTolerance = params.getPriceTolerance();
        Integer historyLimit = params.getHistoryLimit();
        List<Double> limitPriceList = priceList.stream().skip(priceList.size() > historyLimit ? priceList.size() - historyLimit : 0).limit(historyLimit).collect(Collectors.toList());
        List<Double> limitRateList = rateList.stream().skip(rateList.size() > historyLimit ? rateList.size() - historyLimit : 0).limit(historyLimit).collect(Collectors.toList());
        // 计算日增长率平均值总和
        double sumRate = 0;
        final int size = limitRateList.size();
        for (int i = 1; i <= size; i++) {
            // 修改前期数据对得分的影响，保证系数范围在[0.5,1]之间
            // 数值越小，前期数据对得分影响越低
            // 有常数阶和线性阶两种，可供调试
            final double f = ((double) i / size) * (1 - preRateFactor) + preRateFactor;
//            final double f = 1;
            sumRate += limitRateList.get(i - 1) * f;
        }
        // 计算价格方差
        Double priceSum = limitPriceList.stream().reduce(Double::sum).orElse(0.0);
        final double priceAvg = priceSum / limitPriceList.size();
        double sum = 0;
        for (Double price : limitPriceList) {
            sum += Math.pow((price - priceAvg), 2);
        }
        // 方差
        double sqrt = Math.sqrt(sum / priceList.size());
        // 价格波动系数(1 - sqrt / PRICE_BASE_NUMBER)
        // 方差范围限制，确保价格波动系数不为负数
        // 方差越趋向于0，价格波动系数越趋向于1，说明价格越稳定，得分越高
        // 方越趋向于 PRICE_BASE_NUMBER(价格波动容忍度)，
        // 价格波动系数越趋向于0，说明价格波动越大，得分越低
        sqrt = Math.min(sqrt, priceTolerance);
        return sumRate * (1 - sqrt / priceTolerance);
    }

    // 计算交易日期差
    public int diffDate(Date date1, Date date2) {
        HashMap<String, Integer> dateMap = getDateMap();
        Integer from = dateMap.get(DateUtils.dateFormat.format(date1));
        Integer to = dateMap.get(DateUtils.dateFormat.format(date2));
        return Math.abs(to - from);
    }

    // 计算手续费，万五，最低五元
    public Double getPeeAmount(Double amount) {
        return Math.max(5, amount * 0.0005);
    }

    @SneakyThrows
    public HashMap<String, Integer> getDateMap() {
        if (dateMap == null) {
            InputStream is = new ClassPathResource("/json/date.txt").getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            final StringBuilder stringBuilder = new StringBuilder();
            String s;
            while ((s = reader.readLine()) != null) {
                stringBuilder.append(s);
            }
            String[] split = stringBuilder.toString().split(",");
            dateMap = new HashMap<>();
            int index = 0;
            for (String dateString : split) {
                String[] split1 = dateString.split("-");
                int weekDay = Integer.parseInt(split1[1]);
                // 非交易日 不计算日期差
                if (weekDay >= 1 && weekDay <= 5) {
                    index++;
                }
                dateMap.put(split1[0], index);
            }
        }
        return dateMap;
    }
}
