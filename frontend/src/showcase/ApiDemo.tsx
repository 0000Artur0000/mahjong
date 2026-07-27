import { useCallback, useEffect, useState } from "react";
import type { UiError } from "@/api/errors";
import { getServerTime } from "@/api/system";
import { Button, Skeleton } from "@/ui";

type State =
  | { status: "loading" }
  | { status: "ok"; serverTime: string }
  | { status: "error"; error: UiError };

export function ApiDemo() {
  const [state, setState] = useState<State>({ status: "loading" });

  const load = useCallback((signal?: AbortSignal) => {
    setState({ status: "loading" });
    getServerTime(signal).then((result) => {
      if (signal?.aborted) return;
      setState(
        "data" in result
          ? { status: "ok", serverTime: result.data }
          : { status: "error", error: result.error },
      );
    });
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  return (
    <div className="card api-demo">
      <div className="api-demo__row">
        <code className="demo-label">GET /api/v1/system/time</code>
        <Button onClick={() => load()}>Обновить</Button>
      </div>

      {state.status === "loading" && <Skeleton width="14rem" height="1.6rem" />}

      {state.status === "ok" && (
        <p className="api-demo__value tabular">
          {new Date(state.serverTime).toLocaleString("ru-RU")}
        </p>
      )}

      {state.status === "error" && (
        <div className="api-demo__error" role="alert">
          <strong>{state.error.title}</strong>
          {state.error.detail && <span>{state.error.detail}</span>}
          <code>
            status {state.error.status} · {state.error.kind}
            {state.error.correlationId ? ` · ${state.error.correlationId}` : ""}
          </code>
        </div>
      )}
    </div>
  );
}
