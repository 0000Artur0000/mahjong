package ru.dorahub;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleArchitectureTest {

  private static final ApplicationModules MODULES = ApplicationModules.of(DorahubApplication.class);

  @Test
  void verifiesModuleBoundaries() {
    MODULES.verify();

    assertThat(MODULES.stream().map(module -> module.getIdentifier().toString()))
        .contains(
            "accounts",
            "admin",
            "achievements",
            "clubs",
            "learning",
            "media",
            "moderation",
            "notifications",
            "ratings",
            "rules",
            "scoring",
            "tables",
            "tournaments",
            "vision-integration");
  }

  @Test
  void keepsDomainIndependentFromAdapters() {
    var classes = new ClassFileImporter().importPackages("ru.dorahub");

    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..web..", "..persistence..")
        .allowEmptyShould(true)
        .check(classes);
  }

  @Test
  void keepsControllersIndependentFromRepositories() {
    var classes = new ClassFileImporter().importPackages("ru.dorahub");

    noClasses()
        .that()
        .haveSimpleNameEndingWith("Controller")
        .should()
        .dependOnClassesThat()
        .haveSimpleNameEndingWith("Repository")
        .allowEmptyShould(true)
        .check(classes);
  }
}
