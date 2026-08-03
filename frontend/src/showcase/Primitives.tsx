import { Inbox, Settings } from "lucide-react";
import { useState, type ReactNode } from "react";
import {
  Avatar,
  Badge,
  Button,
  Card,
  Checkbox,
  CodeInput,
  Dialog,
  EmptyState,
  Field,
  GlowCard,
  Icon,
  Input,
  Link,
  Menu,
  Popover,
  Radio,
  ScoreChip,
  Select,
  Skeleton,
  Tabs,
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
  const [code, setCode] = useState("");
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
              <Icon icon={Settings} />
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

      <div className="stack stack--wide">
        <Group label="Badge">
          <div className="cluster">
            <Badge tone="accent">Лига A</Badge>
            <Badge tone="positive">Подтверждён</Badge>
            <Badge tone="warning">Provisional</Badge>
            <Badge tone="danger">Оспорено</Badge>
            <Badge>Ханчан</Badge>
          </div>
        </Group>

        <Group label="Avatar">
          <div className="cluster">
            <Avatar name="Артур Сахиуллин" size="sm" />
            <Avatar name="Артур Сахиуллин" size="md" />
            <Avatar name="Мия Кобаяси" size="lg" />
          </div>
        </Group>

        <Group label="ScoreChip — очки за столом">
          <div className="cluster">
            <ScoreChip tile="1z" label="Восток" score={32600} delta={2600} dealer />
            <ScoreChip tile="2z" label="Юг" score={27400} delta={-2600} />
            <ScoreChip tile="3z" label="Запад" score={30000} />
          </div>
        </Group>

        <Group label="Card и GlowCard">
          <div className="cluster">
            <Card>Обычная карточка</Card>
            <Card elevated lift>
              Приподнятая, с hover-подъёмом
            </Card>
            <GlowCard>Золотая рамка, свечение на hover</GlowCard>
          </div>
        </Group>

        <Group label="Tabs">
          <Tabs
            tabs={[
              { id: "hands", label: "Раздачи", panel: "История раздач стола." },
              { id: "scores", label: "Очки", panel: "Динамика очков по раздачам." },
              { id: "rules", label: "Правила", panel: "Снимок пресета RRC-RU 1.0." },
            ]}
          />
        </Group>

        <Group label="EmptyState">
          <EmptyState
            icon={Inbox}
            title="Столов пока нет"
            hint="Создайте стол и позовите тиммейтов по коду — партия начнётся, когда сядут четверо."
            action={<Button variant="secondary">Создать стол</Button>}
          />
        </Group>

        <Group label="Размеры и загрузка кнопки">
          <div className="cluster">
            <Button size="sm">Малая</Button>
            <Button>Обычная</Button>
            <Button size="lg">Большая</Button>
            <Button loading>Загрузка</Button>
            <Button variant="secondary" loading>
              Загрузка
            </Button>
          </div>
        </Group>

        <Group label="CodeInput — вход по одноразовому коду">
          <CodeInput value={code} onChange={setCode} />
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
