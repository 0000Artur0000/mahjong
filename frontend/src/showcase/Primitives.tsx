import { useState, type ReactNode } from "react";
import {
  Button,
  Checkbox,
  Dialog,
  Field,
  Input,
  Link,
  Menu,
  Popover,
  Radio,
  Select,
  Skeleton,
  Textarea,
  useToast,
} from "@/ui";

function Group({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="stack">
      <p className="demo-label">{label}</p>
      {children}
    </div>
  );
}

export function Primitives() {
  const [dialogOpen, setDialogOpen] = useState(false);
  const [nickname, setNickname] = useState("");
  const toast = useToast();
  const nicknameError =
    nickname.trim().length > 0 && nickname.trim().length < 3
      ? "Никнейм — минимум 3 символа."
      : undefined;

  return (
    <section className="plate">
      <header className="plate__head">
        <p className="eyebrow">Primitives</p>
        <h2 className="plate__title">Доступные компоненты</h2>
      </header>

      <div className="stack stack--wide">
        <Group label="Buttons">
          <div className="cluster">
            <Button variant="primary">Начать стол</Button>
            <Button variant="secondary">Пригласить</Button>
            <Button variant="ghost">Отмена</Button>
            <Button variant="danger">Чомбо</Button>
            <Button variant="secondary" disabled>
              Недоступно
            </Button>
            <Button variant="secondary" icon aria-label="Настройки">
              <span aria-hidden="true">⚙</span>
            </Button>
            <Link href="#primitives">Правила рейтинга</Link>
          </div>
        </Group>

        <Group label="Form">
          <div className="form-grid">
            <Field
              label="Никнейм"
              hint="Видно другим игрокам"
              error={nicknameError}
            >
              {(p) => (
                <Input
                  {...p}
                  value={nickname}
                  onChange={(e) => setNickname(e.target.value)}
                  placeholder="Например, Тэнхо"
                  autoComplete="off"
                />
              )}
            </Field>

            <Field label="Город">
              {(p) => (
                <Select {...p} defaultValue="">
                  <option value="" disabled>
                    Выберите город
                  </option>
                  <option>Москва</option>
                  <option>Санкт-Петербург</option>
                  <option>Новосибирск</option>
                </Select>
              )}
            </Field>

            <Field label="О себе" hint="Необязательно">
              {(p) => <Textarea {...p} rows={3} />}
            </Field>

            <fieldset className="fieldset">
              <legend className="field__label">Формат</legend>
              <div className="cluster">
                <Radio name="format" label="Ханчан" defaultChecked />
                <Radio name="format" label="Тонпусэн" />
              </div>
            </fieldset>

            <Checkbox label="Согласен на фотофиксацию раздач" defaultChecked />
          </div>
        </Group>

        <Group label="Overlays">
          <div className="cluster">
            <Button variant="secondary" onClick={() => setDialogOpen(true)}>
              Открыть диалог
            </Button>
            <Popover label="Подробнее">
              <p
                className="lede"
                style={{ fontSize: "var(--text-sm)", margin: 0 }}
              >
                Rated-игра требует сертифицированного рулсета и полного состава.
              </p>
            </Popover>
            <Menu
              label="Действия"
              items={[
                { label: "Пересадить игрока", onSelect: () => {} },
                { label: "Передать роль создателя", onSelect: () => {} },
                { label: "Распустить стол", onSelect: () => {}, danger: true },
              ]}
            />
          </div>
        </Group>

        <Group label="Toasts">
          <div className="cluster">
            <Button onClick={() => toast({ title: "Черновик сохранён" })}>
              Info
            </Button>
            <Button
              onClick={() =>
                toast({ title: "Результат подтверждён", tone: "positive" })
              }
            >
              Success
            </Button>
            <Button
              onClick={() =>
                toast({ title: "Не удалось загрузить фото", tone: "danger" })
              }
            >
              Error
            </Button>
          </div>
        </Group>

        <Group label="Skeleton">
          <div className="skeleton-card">
            <Skeleton
              width="2.75rem"
              height="2.75rem"
              radius="var(--radius-pill)"
            />
            <div className="stack" style={{ flex: 1, gap: "var(--space-2)" }}>
              <Skeleton width="60%" height="0.9rem" />
              <Skeleton width="40%" height="0.9rem" />
            </div>
          </div>
        </Group>
      </div>

      <Dialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        title="Распустить стол?"
        footer={
          <>
            <Button variant="ghost" onClick={() => setDialogOpen(false)}>
              Отмена
            </Button>
            <Button variant="danger" onClick={() => setDialogOpen(false)}>
              Распустить
            </Button>
          </>
        }
      >
        Незавершённые раздачи не сохранятся. Это действие необратимо.
      </Dialog>
    </section>
  );
}
