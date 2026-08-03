package ru.dorahub.rules;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Реестр пресетов правил.
 *
 * <p>Пресеты лежат в {@code classpath:rulesets/*.json} и читаются один раз при старте: битый или
 * неполный пресет роняет приложение, а не отдельную партию.
 */
@Component
public class Rulesets {

  private final Map<String, RulesetSnapshot> byKey;

  public Rulesets() {
    this(new PathMatchingResourcePatternResolver(), new ObjectMapper());
  }

  Rulesets(PathMatchingResourcePatternResolver resolver, ObjectMapper json) {
    Map<String, RulesetSnapshot> loaded = new TreeMap<>();
    for (Resource resource : resources(resolver)) {
      RulesetSnapshot snapshot = read(resource, json);
      RulesetSnapshot clash = loaded.put(snapshot.key(), snapshot);
      if (clash != null) {
        throw new IllegalStateException("дублирующийся ключ пресета правил: " + snapshot.key());
      }
    }
    if (loaded.isEmpty()) {
      throw new IllegalStateException("не найдено ни одного пресета правил в classpath:rulesets/");
    }
    this.byKey = Map.copyOf(loaded);
  }

  /** Снимок правил по ключу пресета. */
  public RulesetSnapshot require(String key) {
    RulesetSnapshot snapshot = byKey.get(key);
    if (snapshot == null) {
      throw new IllegalArgumentException("неизвестный пресет правил: " + key);
    }
    return snapshot;
  }

  /** Ключи доступных пресетов. */
  public List<String> keys() {
    return List.copyOf(byKey.keySet());
  }

  private static Resource[] resources(PathMatchingResourcePatternResolver resolver) {
    try {
      return resolver.getResources("classpath*:rulesets/*.json");
    } catch (IOException e) {
      throw new UncheckedIOException("не удалось прочитать пресеты правил", e);
    }
  }

  private static RulesetSnapshot read(Resource resource, ObjectMapper json) {
    byte[] bytes;
    try {
      bytes = resource.getContentAsByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException("не удалось прочитать пресет " + resource.getFilename(), e);
    }

    JsonNode node = json.readTree(bytes);
    List<Integer> uma = new java.util.ArrayList<>();
    for (JsonNode value : required(node, "uma", resource)) {
      uma.add(value.asInt());
    }

    return new RulesetSnapshot(
        required(node, "key", resource).asString(),
        required(node, "version", resource).asString(),
        required(node, "displayName", resource).asString(),
        required(node, "startingPoints", resource).asInt(),
        required(node, "returnPoints", resource).asInt(),
        uma,
        required(node, "oka", resource).asInt(),
        required(node, "openTanyao", resource).asBoolean(),
        required(node, "kiriageMangan", resource).asBoolean(),
        required(node, "kazoeYakuman", resource).asBoolean(),
        required(node, "stackYakuman", resource).asBoolean(),
        required(node, "complexYakumanCountsDouble", resource).asBoolean(),
        required(node, "doubleWindPairFu", resource).asInt(),
        required(node, "aotenjou", resource).asBoolean(),
        required(node, "atamahane", resource).asBoolean(),
        required(node, "abortiveDraws", resource).asBoolean(),
        required(node, "tripleRonAbort", resource).asBoolean(),
        required(node, "nagashiMangan", resource).asBoolean(),
        required(node, "chomboPenalty", resource).asInt(),
        sha256(bytes));
  }

  private static JsonNode required(JsonNode node, String field, Resource resource) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalStateException(
          "в пресете " + resource.getFilename() + " отсутствует обязательное поле " + field);
    }
    return value;
  }

  // ponytail: пресет — статичный файл, поэтому его байты и есть каноническое представление.
  // Когда пресеты станут редактируемыми через API, считать контрольную сумму придётся от
  // канонической сериализации, а не от исходного текста.
  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 недоступен", e);
    }
  }
}
