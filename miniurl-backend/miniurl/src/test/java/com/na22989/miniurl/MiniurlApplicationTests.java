package com.na22989.miniurl;

import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Base64;

@SpringBootTest
class MiniurlApplicationTests {

    @Test
    void contextLoads() {
    }


    @Test
    void testGenerateSecret() {
        // 生成一个256位（32字节）的随机秘钥，并Base64编码
        String secret = Base64.getEncoder().encodeToString(Keys.secretKeyFor(io.jsonwebtoken.SignatureAlgorithm.HS256).getEncoded());
        System.out.println("生成的秘钥: " + secret);
        System.out.println("长度: " + secret.length() + " 字符");
    }

}
