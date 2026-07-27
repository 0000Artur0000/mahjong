import {
  createBrowserRouter,
  redirect,
  type LoaderFunctionArgs,
} from "react-router";
import { getSession } from "@/auth/session";
import { AdminLayout } from "@/routes/AdminLayout";
import { AppLayout } from "@/routes/AppLayout";
import { PublicLayout } from "@/routes/PublicLayout";
import { RouteError } from "@/routes/RouteError";
import { TableLayout } from "@/routes/TableLayout";
import { Home } from "@/screens/Home";
import { Login } from "@/screens/Login";
import { NotFound } from "@/screens/NotFound";
import { Placeholder } from "@/screens/Placeholder";

// Guards run in layout loaders, before the child screen renders — a denied
// route redirects or 403s without ever flashing protected content.
function requireAuth({ request }: LoaderFunctionArgs) {
  if (!getSession()) {
    const url = new URL(request.url);
    throw redirect(
      `/login?next=${encodeURIComponent(url.pathname + url.search)}`,
    );
  }
  return null;
}

function requireAdmin(args: LoaderFunctionArgs) {
  requireAuth(args); // redirects when signed out
  if (getSession()?.role !== "admin") {
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
      {
        path: "leaderboard",
        element: (
          <Placeholder
            eyebrow="Публичное"
            title="Рейтинг"
            note="Рейтинги — FE-26."
          />
        ),
      },
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
      {
        path: "tables",
        element: (
          <Placeholder
            eyebrow="Кабинет"
            title="Столы"
            note="Создание и лобби — FE-11/12."
          />
        ),
      },
      {
        path: "profile",
        element: (
          <Placeholder
            eyebrow="Кабинет"
            title="Профиль"
            note="Профиль и приватность — FE-09."
          />
        ),
      },
    ],
  },
  {
    path: "table/:id",
    element: <TableLayout />,
    errorElement: <RouteError />,
    loader: requireAuth,
    handle: { access: "auth" },
    children: [
      {
        index: true,
        element: (
          <Placeholder
            eyebrow="Активный стол"
            title="Стол"
            note="Компас, исходы и подсчёт — FE-13+."
          />
        ),
      },
    ],
  },
  {
    path: "admin",
    element: <AdminLayout />,
    errorElement: <RouteError />,
    loader: requireAdmin,
    handle: { access: "admin" },
    children: [
      {
        index: true,
        element: (
          <Placeholder
            eyebrow="Admin"
            title="Судейская"
            note="Судейские экраны — FE-29."
          />
        ),
      },
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
