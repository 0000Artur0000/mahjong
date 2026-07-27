package ru.dorahub.system.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionConfigurationTest {

  private static final String SECRET = "must-not-appear";
  private static final ApplicationContextRunner CONTEXT =
      new ApplicationContextRunner()
          .withUserConfiguration(ProductionConfiguration.class)
          .withPropertyValues("spring.profiles.active=prod");

  @Test
  void rejectsIncompleteProductionConfigurationWithoutLeakingProvidedSecret() {
    CONTEXT
        .withPropertyValues("dorahub.integrations.oidc.telegram-client-secret=" + SECRET)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(stackTrace(context.getStartupFailure()))
                  .contains("dorahub.integrations")
                  .doesNotContain(SECRET);
            });
  }

  @Test
  void bindsCompleteProductionConfigurationAndRedactsDiagnostics() {
    CONTEXT
        .withPropertyValues(
            "dorahub.integrations.oidc.telegram-client-id=telegram",
            "dorahub.integrations.oidc.telegram-client-secret=" + SECRET,
            "dorahub.integrations.oidc.vk-client-id=vk",
            "dorahub.integrations.oidc.vk-client-secret=" + SECRET,
            "dorahub.integrations.smtp.host=smtp.example.test",
            "dorahub.integrations.smtp.port=587",
            "dorahub.integrations.smtp.username=dorahub",
            "dorahub.integrations.smtp.password=" + SECRET,
            "dorahub.integrations.smtp.from=no-reply@example.test",
            "dorahub.integrations.s3.endpoint=https://s3.example.test",
            "dorahub.integrations.s3.region=local",
            "dorahub.integrations.s3.bucket=dorahub",
            "dorahub.integrations.s3.access-key=" + SECRET,
            "dorahub.integrations.s3.secret-key=" + SECRET,
            "dorahub.integrations.vision.base-url=https://vision.example.test",
            "dorahub.integrations.vision.api-key=" + SECRET)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var properties = context.getBean(ProductionIntegrationsProperties.class);

              assertThat(
                      properties
                          + properties.oidc().toString()
                          + properties.smtp()
                          + properties.s3()
                          + properties.vision())
                  .doesNotContain(SECRET);
            });
  }

  private static String stackTrace(Throwable failure) {
    var output = new StringWriter();
    failure.printStackTrace(new PrintWriter(output));
    return output.toString();
  }
}
