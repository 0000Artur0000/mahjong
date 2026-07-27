package ru.dorahub.system.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("prod")
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProductionIntegrationsProperties.class)
class ProductionConfiguration {}
