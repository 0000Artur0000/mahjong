import { useEffect, useState } from "react";
import type { UiError } from "@/api/errors";
import { getTableStats, type TableStats } from "@/api/tables";
import { EmptyState, Skeleton } from "@/ui";
import { Inbox } from "lucide-react";

/**
 * Идёт ли пилот.
 *
 * Считается из уже накопленного — состояний столов и причин их завершения, — поэтому
 * отдельного сбора событий нет и включать его на телефонах игроков не пришлось.
 * Персональных данных здесь нет вовсе: вопрос «доводят ли партии до конца» про
 * продукт, а не про людей.
 */
export function AdminMetrics() {
  const [stats, setStats] = useState<TableStats | null>(null);
  const [error, setError] = useState<UiError | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      const result = await getTableStats(controller.signal);
      if (controller.signal.aborted) return;
      if ("error" in result) setError(result.error);
      else setStats(result.data);
    })();
    return () => controller.abort();
  }, []);

  if (error) {
    return (
      <EmptyState
        icon={Inbox}
        title="Метрики недоступны"
        hint={error.detail ?? error.title}
      />
    );
  }
  if (!stats) return <Skeleton width="100%" height="12rem" />;

  return (
    <div className="screen stack">
      <h1 className="screen__title">Как идёт пилот</h1>

      <section className="stack">
        <p className="section-label">Доводят ли партии до конца</p>
        <div className="stat-row">
          <span className="stat stat--accent">
            <span className="stat__value">
              {stats.completionRate}
              <span className="stat__unit">%</span>
            </span>
            <span className="stat__label">доиграно до конца формата</span>
          </span>
          <span className="stat">
            <span className="stat__value">{stats.completed}</span>
            <span className="stat__label">партий в зачёте</span>
          </span>
          <span className="stat stat--muted">
            <span className="stat__value">{stats.abandonedEarly}</span>
            <span className="stat__label">брошено за столом</span>
          </span>
          <span className="stat stat--muted">
            <span className="stat__value">{stats.abandonedLobby}</span>
            <span className="stat__label">лобби без старта</span>
          </span>
        </div>
      </section>

      <section className="stack">
        <p className="section-label">Прямо сейчас</p>
        <div className="stat-row">
          <span className="stat">
            <span className="stat__value">{stats.active}</span>
            <span className="stat__label">партий идёт</span>
          </span>
          <span className="stat stat--muted">
            <span className="stat__value">{stats.lobbies}</span>
            <span className="stat__label">столов в лобби</span>
          </span>
          <span className={stats.stale > 0 ? "stat" : "stat stat--muted"}>
            <span className="stat__value">{stats.stale}</span>
            <span className="stat__label">остыли без раздач</span>
          </span>
        </div>
      </section>

      <section className="stack">
        <p className="section-label">Сколько занимает партия</p>
        <div className="stat-row">
          <span className="stat">
            <span className="stat__value">
              {stats.medianMinutes}
              <span className="stat__unit"> мин</span>
            </span>
            <span className="stat__label">медиана</span>
          </span>
          <span className="stat stat--muted">
            <span className="stat__value">
              {stats.p90Minutes}
              <span className="stat__unit"> мин</span>
            </span>
            <span className="stat__label">p90</span>
          </span>
          <span className="stat stat--muted">
            <span className="stat__value">{stats.handsPerCompletedGame}</span>
            <span className="stat__label">раздач в партии</span>
          </span>
        </div>
      </section>
    </div>
  );
}
