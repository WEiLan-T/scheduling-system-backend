package com.company.scheduling;

import org.apache.poi.util.IOUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SchedulingApplication {
    public static void main(String[] args) {
        // 放开POI内部字节数组上限（默认1亿字节），否则大Excel导入会报
        // "Tried to allocate an array of length ... maximum length ... 100,000,000"
        IOUtils.setByteArrayMaxOverride(-1);
        SpringApplication.run(SchedulingApplication.class, args);
        System.out.println("排产系统后端已成功启动！");
    }
}