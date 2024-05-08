package online.mwang.stockTrading.web.utils;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @version 1.0.0
 * @author: mwangli
 * @date: 2023/5/22 10:24
 * @description: RequestUtils
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestUtils {

    public static final String REQUEST_URL = "https://weixin.citicsinfo.com/reqxml";
    public static final String TOKEN = "requestToken";
    public final StringRedisTemplate redisTemplate;
    public boolean logs = false;
    @Value("${PROFILE}")
    private String profile;


    @SneakyThrows
    public JSONObject request(String url, HashMap<String, Object> formParam) {
        CloseableHttpClient client = HttpClients.createDefault();
        MultipartEntityBuilder entityBuilder = MultipartEntityBuilder.create();
        formParam.forEach((k, v) -> entityBuilder.addTextBody(k, String.valueOf(v)));
        HttpPost post = new HttpPost(url);
        post.setEntity(entityBuilder.build());
        CloseableHttpResponse response = client.execute(post);
        String result = EntityUtils.toString(response.getEntity());
        boolean debug = "dev".equalsIgnoreCase(profile);
        if (logs || debug) log.info(result);
        JSONObject res = JSONObject.parseObject(result);
        if (checkToken(res)) return res;
        return new JSONObject();
    }

    private boolean checkToken(JSONObject res) {
        List<String> errorCodes = Arrays.asList("-204007", "-204009", "-204001");
        String errorNo = res.getString("ERRORNO");
        if (errorCodes.contains(errorNo)) {
            log.info("TOKEN已经失效,请重试!");
            redisTemplate.opsForValue().getAndDelete(TOKEN);
            return false;
        } else {
            String token = res.getString("TOKEN");
            if (token != null) redisTemplate.opsForValue().set(TOKEN, token, 30, TimeUnit.MINUTES);
            return true;
        }
    }

    @SneakyThrows
    public JSONObject request(HashMap<String, Object> formParam) {
        return request(REQUEST_URL, formParam);
    }

    @SneakyThrows
    public JSONArray request2(HashMap<String, Object> formParam) {
        JSONObject res = request(REQUEST_URL, formParam);
        return res.getJSONArray("GRID0");
    }

    @SneakyThrows
    public JSONArray request3(HashMap<String, Object> formParam) {
        JSONObject res = request(REQUEST_URL.concat("?action=1230"), formParam);
        final JSONObject data = res.getJSONObject("BINDATA");
        if (data != null && data.getJSONArray("results") != null)
            return data.getJSONArray("results");
        return new JSONArray();
    }
}
