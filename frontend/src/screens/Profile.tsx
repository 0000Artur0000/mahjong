import { useEffect, useState } from "react";
import type { UiError } from "@/api/errors";
import { getMyProfile, type PrivateProfile } from "@/api/profile";
import { getMyRatingSummary, type FormatSummary } from "@/api/ratings";
import { listMyHands, type WonHand } from "@/api/tables";
import { Badge, EmptyState, Skeleton } from "@/ui";
import { Inbox } from "lucide-react";

const FORMAT_NAMES: Record<string, string> = {
  hanchan: "Ханчан",
  tonpuusen: "Тонпусен",
};

const PLACE_NAMES = ["1-е", "2-е", "3-е", "4-е"];

/**
 * Кабинет игрока: кто ты, как играешь и чем закончились твои раздачи.
 *
 * Всё считается из уже накопленного — лестницы и журнала столов, — поэтому
 * отдельного хранилища статистики нет и расходиться нечему.
 */
export function Profile() {
  const [profile, setProfile] = useState<PrivateProfile | null>(null);
  const [summary, setSummary] = useState<FormatSummary[] | null>(null);
  const [hands, setHands] = useState<WonHand[] | null>(null);
  const [error, setError] = useState<UiError | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      const [me, formats, won] = await Promise.all([
        getMyProfile(controller.signal),
        getMyRatingSummary(controller.signal),
        listMyHands(controller.signal),
      ]);
      if (controller.signal.aborted) return;
      if ("error" in me) {
        setError(me.error);
        return;
      }
      setProfile(me.data);
      if ("data" in formats) setSummary(formats.data);
      if ("data" in won) setHands(won.data);
    })();
    return () => controller.abort();
  }, []);

  if (error) {
    return (
      <EmptyState
        icon={Inbox}
        title="Профиль недоступен"
        hint={error.detail ?? error.title}
      />
    );
  }

  if (!profile) return <Skeleton width="100%" height="12rem" />;

  // Лучшая рука — по хан, при равенстве по фу: так же её сравнивают за столом.
  const best = hands?.reduce<WonHand | null>(
    (top, hand) =>
      !top || hand.han > top.han || (hand.han === top.han && hand.fu > top.fu)
        ? hand
        : top,
    null,
  );

  return (
    <div className="screen stack">
      <div className="cluster">
        <h1 className="screen__title">{profile.nickname}</h1>
        {profile.role !== "player" && (
          <Badge tone="accent">
            {profile.role === "admin" ? "Администратор" : "Модератор"}
          </Badge>
        )}
      </div>

      {summary?.length === 0 && (
        <EmptyState
          icon={Inbox}
          title="Доигранных партий нет"
          hint="В зачёт идут партии, сыгранные до конца формата."
        />
      )}

      {summary?.map((format) => (
        <section key={format.format} className="stack">
          <p className="section-label">
            {FORMAT_NAMES[format.format] ?? format.format}
          </p>
          <div className="stat-row">
            <span className="stat stat--accent">
              <span className="stat__value">{format.rating}</span>
              <span className="stat__label">рейтинг</span>
            </span>
            <span className="stat">
              <span className="stat__value">{format.games}</span>
              <span className="stat__label">партий</span>
            </span>
            <span className="stat">
              <span className="stat__value">{format.averagePlace}</span>
              <span className="stat__label">среднее место</span>
            </span>
          </div>
          <div className="stat-row">
            {format.places.map((count, place) => (
              <span key={place} className={place === 0 ? "stat" : "stat stat--muted"}>
                <span className="stat__value">{count}</span>
                <span className="stat__label">{PLACE_NAMES[place]} место</span>
              </span>
            ))}
          </div>
        </section>
      ))}

      <section className="stack">
        <p className="section-label">Выигранные раздачи</p>
        {hands?.length === 0 && (
          <EmptyState
            icon={Inbox}
            title="Побед пока нет"
            hint="Подтверждённые победы появятся здесь. Откатанные — нет."
          />
        )}
        {hands && hands.length > 0 && (
          <ol className="hand-list">
            {hands.map((hand) => (
              <li
                key={`${hand.tableId}-${hand.sequence}`}
                className={
                  hand === best ? "hand-card hand-card--best" : "hand-card"
                }
              >
                <span className="hand-card__value">
                  {hand.han} <span>хан</span> {hand.fu} <span>фу</span>
                </span>
                <span className="hand-card__yaku">
                  {hand.yaku.join(" · ") || "без яку"}
                </span>
                {hand === best ? (
                  <Badge tone="accent">лучшая</Badge>
                ) : (
                  <span className="hand-card__date">
                    {new Date(hand.at).toLocaleDateString("ru-RU", {
                      day: "numeric",
                      month: "short",
                    })}
                  </span>
                )}
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}
