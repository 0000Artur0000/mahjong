package ru.dorahub.scoring.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Тонкая обёртка над io.github.ssttkkl:mahjong-utils.
 *
 * <p>Библиотека считает разложения, яку, хан, фу и стоимость по хан/фу. Всё, что относится к столу
 * — хонба, ставки риичи, ума/ока, множественный рон, санма, нотен и чомбо — библиотека не покрывает
 * и остаётся зоной Dorahub. Подробности и границы: docs/adr/0003-score-engine-library.md.
 */
public final class MahjongUtils {

  // ponytail: JVM-точка входа библиотеки лежит в безымянном пакете (MainKt.call), импортировать её
  // из именованного пакета Java нельзя. MethodHandle резолвится один раз при загрузке класса.
  // Если понадобится типизированный доступ — подключать Kotlin-плагин и писать тонкий Kotlin-шим.
  private static final MethodHandle CALL = resolveCall();

  private static final ObjectMapper JSON = new ObjectMapper();

  private MahjongUtils() {}

  /** Разбор выигрышной руки: яку, хан, фу. */
  public static Hora hora(Map<String, Object> args) {
    JsonNode data = call("hora", args);
    List<String> yaku = new ArrayList<>();
    for (JsonNode node : data.path("yaku")) {
      yaku.add(node.asString());
    }
    return new Hora(data.path("han").asInt(), data.path("hu").asInt(), List.copyOf(yaku));
  }

  /** Стоимость руки не-дилера по хан/фу. */
  public static NonDealerPoints nonDealerPoints(int han, int hu, Map<String, Object> options) {
    JsonNode data = call("getChildPointByHanHu", hanHu(han, hu, options));
    return new NonDealerPoints(
        data.path("ron").asInt(),
        data.path("tsumoParent").asInt(),
        data.path("tsumoChild").asInt());
  }

  /** Стоимость руки дилера по хан/фу. */
  public static DealerPoints dealerPoints(int han, int hu, Map<String, Object> options) {
    JsonNode data = call("getParentPointByHanHu", hanHu(han, hu, options));
    return new DealerPoints(data.path("ron").asInt(), data.path("tsumo").asInt());
  }

  private static Map<String, Object> hanHu(int han, int hu, Map<String, Object> options) {
    return options == null || options.isEmpty()
        ? Map.of("han", han, "hu", hu)
        : Map.of("han", han, "hu", hu, "options", options);
  }

  private static JsonNode call(String method, Map<String, Object> params) {
    String response;
    try {
      response = (String) CALL.invokeExact(method, JSON.writeValueAsString(params));
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException("mahjong-utils call failed: " + method, e);
    }

    JsonNode root = JSON.readTree(response);
    int code = root.path("code").asInt();
    if (code != 200) {
      // Невалидный состав руки — ошибка ввода, а не сбой сервиса.
      throw new IllegalArgumentException(
          "mahjong-utils "
              + method
              + " rejected input ("
              + code
              + "): "
              + root.path("msg").asString());
    }
    return root.path("data");
  }

  private static MethodHandle resolveCall() {
    try {
      Class<?> main = Class.forName("MainKt");
      return MethodHandles.publicLookup()
          .findStatic(
              main, "call", MethodType.methodType(String.class, String.class, String.class));
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** Разбор руки: сработавшие яку, суммарный хан и фу. */
  public record Hora(int han, int hu, List<String> yaku) {}

  /** Выплаты не-дилеру: рон и цумо отдельно с дилера и с каждого не-дилера. */
  public record NonDealerPoints(int ron, int tsumoFromDealer, int tsumoFromNonDealer) {}

  /** Выплаты дилеру: рон и цумо с каждого. */
  public record DealerPoints(int ron, int tsumoFromEach) {}
}
