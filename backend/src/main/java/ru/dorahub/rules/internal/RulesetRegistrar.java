package ru.dorahub.rules.internal;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.dorahub.rules.RulesetSnapshot;
import ru.dorahub.rules.Rulesets;
import tools.jackson.databind.ObjectMapper;

/**
 * Переносит пресеты из classpath в таблицу {@code ruleset}.
 *
 * <p>Стол ссылается на пресет внешним ключом, поэтому версия должна существовать в базе до создания
 * первой партии. Существующая версия не переписывается: снимок правил неизменяем, а изменение
 * пресета обязано выпускать новую версию.
 */
@Component
@Profile("!test")
class RulesetRegistrar {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(RulesetRegistrar.class);

  private final Rulesets rulesets;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  RulesetRegistrar(Rulesets rulesets, JdbcTemplate jdbc, ObjectMapper json) {
    this.rulesets = rulesets;
    this.jdbc = jdbc;
    this.json = json;
  }

  @EventListener(ApplicationReadyEvent.class)
  void register() {
    for (String key : rulesets.keys()) {
      RulesetSnapshot snapshot = rulesets.require(key);
      int inserted =
          jdbc.update(
              """
              INSERT INTO ruleset (key, version, checksum, parameters, certification_status)
              VALUES (?, ?, ?, ?::jsonb, 'draft')
              ON CONFLICT (key, version) DO NOTHING
              """,
              snapshot.key(),
              snapshot.version(),
              snapshot.checksum(),
              json.writeValueAsString(snapshot));

      if (inserted == 0) {
        // §1.1: пресет неизменяем. Расхождение контрольной суммы значит, что файл правили без
        // выпуска новой версии — старые партии перестанут воспроизводиться.
        String stored =
            jdbc.queryForObject(
                "SELECT checksum FROM ruleset WHERE key = ? AND version = ?",
                String.class,
                snapshot.key(),
                snapshot.version());
        if (!snapshot.checksum().equals(stored)) {
          throw new IllegalStateException(
              "пресет "
                  + snapshot.id()
                  + " изменён без новой версии: контрольная сумма не совпадает");
        }
      } else {
        LOG.info("Registered ruleset {}", snapshot.id());
      }
    }
  }
}
