package online.mwang.stockTrading.web.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import online.mwang.stockTrading.web.bean.base.Response;
import online.mwang.stockTrading.web.bean.param.LoginParam;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * @version 1.0.0
 * @author: mwangli
 * @date: 2023/3/31 15:01
 * @description: LoginController
 */
@Slf4j
@RestController
@RequestMapping("/login")
public class LoginController {

    private static final Random RANDOM = new Random();
    private static final String USERNAME_ADMIN = "admin";
    private static final String USERNAME_GUEST = "guest";
    private static final String USERNAME_TEST = "test";
    private static final String SDF = "MMdd";
    private static final Integer TOKEN_LENGTH = 32;
    private static final Integer TOKEN_EXPIRE_HOURS = 4;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static String generateToken() {
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            final int c = RANDOM.nextInt('z' - '0');
            builder.append((char) ('0' + c));
        }
        return builder.toString();
    }

    @PostMapping("/account")
    public Response<String> login(@RequestBody LoginParam param) {
        final String monthDate = new SimpleDateFormat(SDF).format(new Date());
        final String reverseDate = new StringBuilder(monthDate).reverse().toString();
        String username = param.getUsername().trim();
        String password = param.getPassword().trim();
        if (USERNAME_TEST.equalsIgnoreCase(username)) {
            final String token = generateToken();
            JSONObject user = getUserInfo(USERNAME_TEST, USERNAME_TEST);
            stringRedisTemplate.opsForValue().set(token, JSON.toJSONString(user), TOKEN_EXPIRE_HOURS, TimeUnit.HOURS);
            return Response.success(token);
        }
        if (USERNAME_GUEST.equalsIgnoreCase(username)) {
            final String token = generateToken();
            JSONObject user = getUserInfo(USERNAME_GUEST, USERNAME_GUEST);
            stringRedisTemplate.opsForValue().set(token, JSON.toJSONString(user), TOKEN_EXPIRE_HOURS, TimeUnit.HOURS);
            return Response.success(token);
        }
        if (USERNAME_ADMIN.equalsIgnoreCase(username) && reverseDate.equals(password)) {
            final String token = generateToken();
            JSONObject user = getUserInfo(USERNAME_ADMIN, USERNAME_ADMIN);
            stringRedisTemplate.opsForValue().set(token, JSON.toJSONString(user), TOKEN_EXPIRE_HOURS, TimeUnit.HOURS);
            return Response.success(token);
        }
        return Response.fail(1101, "用户名或密码错误!");
    }

    @PostMapping("/outLogin")
    public Response<Void> outLogin(HttpServletRequest request) {
        final String token = request.getHeader("token");
        stringRedisTemplate.opsForValue().getAndDelete(token);
        return Response.success();
    }

    @SneakyThrows
    @GetMapping("/currentUser")
    public Response<JSONObject> currentUser(HttpServletRequest request) {
        final String token = request.getHeader("token");
        final String user = stringRedisTemplate.opsForValue().get(token);
        return Response.success(JSON.parseObject(user));
    }

    private JSONObject getUserInfo(String name, String access) {
        JSONObject user = new JSONObject();
        user.put("name", name);
        user.put("avatar", "https://gw.alipayobjects.com/zos/antfincdn/XAosXuNZyF/BiazfanxmamNRoxxVxka.png");
        user.put("access", access);
        return user;
    }
}
