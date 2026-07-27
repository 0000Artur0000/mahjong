package ru.dorahub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(systemName = "Dorahub", sharedModules = "system")
@SpringBootApplication
public class DorahubApplication {

  public static void main(String[] args) {
    SpringApplication.run(DorahubApplication.class, args);
  }
}
