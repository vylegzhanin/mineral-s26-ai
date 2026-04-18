# ADR-0001: Замена файлового кэша на PostgreSQL + Redis и добавление undo/redo

## Статус
Proposed (2026-04-18)

## Контекст
Сейчас подготовка объектов датасета опирается на файловый кэш:
- кэш хранится в локальном каталоге (`cacheRoot`) с `manifest.json`;
- при наличии `manifest.json` и файлов превью данные читаются из кэша;
- иначе данные перерасчитываются и снова пишутся на диск.

Подход простой, но имеет ограничения:
1. Нет транзакционности и согласованности между разными экземплярами приложения.
2. Нет нормальной модели undo/redo на уровне пользователя/сессии.
3. Файловая модель усложняет горизонтальное масштабирование (stateful storage, блокировки, shared volume).
4. Кэш не является основной задачей системы, поэтому нужна простая операционная модель без тяжелой инфраструктуры.

## Драйверы решения
- Кэширование вторично и не должно усложнять поддержку.
- Желательно избежать переусложненной инфраструктуры.
- Предпочтительная БД: PostgreSQL.
- Нужен undo/redo на уровне сессии или пользователя.

## Решение
Использовать **PostgreSQL как основное хранилище состояния и истории команд**, а для ускорения горячих чтений добавить **опциональный Redis** (как L2-кэш, без критичной зависимости).

### 1) PostgreSQL как источник истины
В PostgreSQL хранятся:
- метаданные объектов и результатов обработки;
- ссылки на артефакты (путь/ключ к preview/crop/mask);
- журнал пользовательских изменений (event/command log);
- стеки undo/redo по `user_id` или `session_id`.

Важно: бинарные файлы изображений хранить не в БД, а в файловом/object storage (текущий путь можно оставить на переходный период).

### 2) Undo/redo через command/event log
Модель:
- `user_action_log` — append-only таблица с атомарными действиями (`action_type`, `payload`, `inverse_payload`, `created_at`);
- `session_state` — указатель на текущую позицию в журнале (`cursor`), отдельно для `scope_type in ('USER','SESSION')`;
- `redo` очищается при новой операции после undo (классическая семантика).

Плюсы:
- предсказуемое поведение;
- простая отладка и аудит;
- возможность последующей аналитики действий.

### 3) Redis как необязательный ускоритель
Redis используется только для:
- короткоживущих подготовленных представлений (например, результаты фильтрации);
- быстрых lookup-ключей для UI.

Если Redis недоступен, приложение работает только на PostgreSQL (degrade gracefully).

## Минимальная схема (черновик)

```sql
create table dataset_object (
    id uuid primary key,
    dataset_id text not null,
    name text not null,
    category text,
    preview_ref text not null,
    properties jsonb not null default '{}',
    updated_at timestamptz not null default now()
);

create table action_log (
    id bigserial primary key,
    scope_type text not null check (scope_type in ('USER', 'SESSION')),
    scope_id text not null,
    action_type text not null,
    payload jsonb not null,
    inverse_payload jsonb not null,
    created_at timestamptz not null default now()
);

create table history_cursor (
    scope_type text not null check (scope_type in ('USER', 'SESSION')),
    scope_id text not null,
    cursor_action_id bigint,
    updated_at timestamptz not null default now(),
    primary key (scope_type, scope_id)
);

create index idx_action_log_scope on action_log(scope_type, scope_id, id);
```

## Почему это не «переусложнение»
- PostgreSQL в любом случае нужен для undo/redo и консистентного состояния.
- Redis не обязателен и может быть включен позже.
- Отказ от file-only state упрощает multi-instance сценарии.
- Функциональность концентрируется в одной понятной модели: `state + action history`.

## План миграции (итеративно)
1. **Шаг 1 (без риска):** ввести слой `ObjectRepository`/`HistoryRepository` и писать в Postgres параллельно с файловым кэшем (dual-write).
2. **Шаг 2:** читать объектные метаданные из Postgres, файл-кэш оставить только как source для бинарных preview.
3. **Шаг 3:** включить undo/redo через `action_log + history_cursor`.
4. **Шаг 4:** отключить запись `manifest.json`, оставить файловое хранилище только для бинарных артефактов.
5. **Шаг 5 (опционально):** добавить Redis для горячих чтений и коротких TTL.

## Риски и меры
- **Dual-write рассинхронизация:** временный reconciliation job + метрики расхождений.
- **Рост action_log:** партиционирование по дате и retention policy.
- **Сложность inverse_payload:** ограничить set поддерживаемых action-типов на старте.

## Критерии готовности
- Undo/redo проходит e2e для scope USER и SESSION.
- UI продолжает работать при отключенном Redis.
- Новый инстанс приложения поднимается без shared FS-кэша и корректно отображает состояние.

## Альтернативы
1. **Оставить file-cache и добавить отдельный журнал изменений в файлах** — сложно поддерживать консистентность и блокировки.
2. **Только Redis** — слабая долговечность как source of truth для undo/redo.
3. **Kafka + event sourcing** — избыточно для текущих требований.

## Последствия
- Появляется единый центр правды (PostgreSQL).
- Значительно упрощается реализация undo/redo.
- Локальный файловый кэш перестает быть критичной зависимостью.
