package com.fashioncommerce.fashioncommercebackend.auth.otp;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OtpProperties.class)
public class OtpConfiguration { }
