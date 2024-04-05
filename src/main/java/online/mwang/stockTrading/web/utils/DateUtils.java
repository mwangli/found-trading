package online.mwang.stockTrading.web.utils;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.TimeUnit;

public class DateUtils {

    public static final SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyyMMddHHmmss");
    public static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
    public static final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");

    public static void main(String[] args) throws Exception {
        StringBuilder stringBuffer = new StringBuilder();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());
        calendar.add(Calendar.MONTH, -1);
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd");
        int count = 1;
        while (count <= 1000) {
            int weekDay = calendar.get(Calendar.DAY_OF_WEEK);
            Date date = calendar.getTime();
            String format = simpleDateFormat.format(date);
//            if (weekDay == 1 || weekDay == 7) {
//                System.out.println(weekDay);
//                System.out.println(date);
//
//            } else {
//                stringBuffer.append(format).append(",");
//                if (count % 20 == 0) stringBuffer.append("\r\n");
//                count++;
//            }
            stringBuffer.append(format).append("-") .append(weekDay-1)  .append(",");
            if (count % 20 == 0) stringBuffer.append("\r\n");
            count++;
            calendar.add(Calendar.DATE, 1);
        }
        System.out.println(stringBuffer);
        BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(new FileOutputStream("date.txt"));
        bufferedOutputStream.write(stringBuffer.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String format1(Date date) {
        return date == null ? "" : dateFormat.format(date);
    }

    public static Date getNextDay(Date date) {
        if (date == null) return null;
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.add(Calendar.DATE,1);
        return calendar.getTime();
    }

    public static String camelToUnderline(String str) {
        if (str == null || "".equals(str.trim())) {
            return "";
        }
        int len = str.length();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = str.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append("_").append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String timeConvertor(long millis) {
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long hours = TimeUnit.MILLISECONDS.toHours(millis) % 24;
        long days = TimeUnit.MILLISECONDS.toDays(millis) % 365;
        long years = TimeUnit.MILLISECONDS.toDays(millis) / 365;
        return years + "年 " + days + "天 " + hours + "小时 " + minutes + "分钟 " + seconds + "秒";
    }
}
