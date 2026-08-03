import {
  createBrowserRouter,
  redirect,
  type LoaderFunctionArgs,
} from "react-router";
import { loadSession } from "@/auth/session";
import { AdminLayout } from "@/routes/AdminLayout";
import { AdminMetrics } from "@/screens/AdminMetrics";
import { AppLayout } from "@/routes/AppLayout";
import { PublicLayout } from "@/routes/PublicLayout";
import { RouteError } from "@/routes/RouteError";
import { TableLayout } from "@/routes/TableLayout";
import { Home } from "@/screens/Home";
import { Leaderboard } from "@/screens/Leaderboard";
import { Login } from "@/screens/Login";
import { NotFound } from "@/screens/NotFound";
import { Placeholder } from "@/screens/Placeholder";
import { Profile } from "@/screens/Profile";
import { Table } from "@/screens/table/Table";
import { Tables } from "@/screens/Tables";

// Guards run in layout loaders, before the child screen renders — a denied
// route redirects or 403s without ever flashing protected content. Проверка идёт
// у сервера: cookie сессии переживает и перезагрузку, и обновление приложения, а
// отметка в браузере — нет.
async function requireAuth({ request }: LoaderFunctionArgs) {
  if (!(await loadSession())) {
    const url = new URL(request.url);
    throw redirect(
      `/login?next=${encodeURIComponent(url.pathname + url.search)}`,
    );
  }
  return null;
}

async function requireStaff(args: LoaderFunctionArgs) {
  await requireAuth(args); // redirects when signed out
  // Метрики и судейские экраны — право модератора; админ его покрывает.
  const role = (await loadSession())?.role;
  if (role !== "admin" && role !== "moderator") {
    throw new Response("Forbidden", { status: 403 });
  }
  return null;
}

export const router = createBrowserRouter([
  {
    element: <PublicLayout />,
    errorElement: <RouteError />,
    handle: { access: "public" },
    children: [
      { index: true, element: <Home /> },
      {
        path: "rules",
        element: (
          <Placeholder
            eyebrow="Публичное"
            title="Правила и яку"
            note="Экран — FE-30."
          />
        ),
      },
      {
        path: "clubs",
        element: (
          <Placeholder
            eyebrow="Публичное"
            title="Клубы"
            note="Каталог клубов — FE-10."
          />
        ),
      },
      { path: "leaderboard", element: <Leaderboard /> },
      { path: "login", element: <Login /> },
      { path: "styleguide", lazy: () => import("@/screens/Styleguide") },
      { path: "*", element: <NotFound /> },
    ],
  },
  {
    path: "app",
    element: <AppLayout />,
    errorElement: <RouteError />,
    loader: requireAuth,
    handle: { access: "auth" },
    children: [
      {
        index: true,
        element: (
          <Placeholder
            eyebrow="Кабинет"
            title="Обзор"
            note="История и рейтинг — FE-25/26."
          />
        ),
      },
      { path: "tables", element: <Tables /> },
      { path: "profile", element: <Profile /> },
    ],
  },
  {
    path: "table/:id",
    element: <TableLayout />,
    errorElement: <RouteError />,
    loader: requireAuth,
    handle: { access: "auth" },
    children: [
      { index: true, element: <Table /> },
    ],
  },
  {
    path: "admin",
    element: <AdminLayout />,
    errorElement: <RouteError />,
    loader: requireStaff,
    handle: { access: "admin" },
    children: [
      { index: true, element: <AdminMetrics /> },
      {
        path: "moderation",
        element: (
          <Placeholder
            eyebrow="Admin"
            title="Модерация"
            note="Очередь жалоб — FE-29."
          />
        ),
      },
    ],
  },
]);
