// Route-skeleton stub. Real screens arrive in their own plan items; this keeps
// every route reachable and reloadable in the meantime.
export function Placeholder({
  eyebrow,
  title,
  note,
}: {
  eyebrow: string;
  title: string;
  note: string;
}) {
  return (
    <div className="screen">
      <p className="eyebrow">{eyebrow}</p>
      <h1 className="screen__title">{title}</h1>
      <p className="lede">Заглушка маршрутного каркаса. {note}</p>
    </div>
  );
}
