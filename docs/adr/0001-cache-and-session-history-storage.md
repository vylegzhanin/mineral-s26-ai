# ADR-0001: Замена файлового кэша на PostgreSQL + Redis и добавление undo/redo

## Статус
Proposed (2026-04-18, revised)

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
- Предпочтительные data-access инструменты: jOOQ или JetBrains Exposed.
- Нужны управляемые миграции схемы БД.
- Доступ к данным должен быть доступен из Ktor server.
- Нужно учитывать готовность к асинхронному доступу к БД.
- По возможности избегаем Spring-зависимостей в data слое.

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

### 4) Data access: jOOQ / Exposed (без Spring-first подхода)
Базовый вариант:
- **jOOQ** для SQL-first сценариев (сложные выборки, CTE, контроль SQL на уровне схемы);
- **JetBrains Exposed** допустим как более легкий DSL/DAO вариант.

Оба варианта должны использоваться через общий `Repository` API из `app-kernel`, чтобы:
- UI и Ktor не знали о деталях ORM/DSL;
- можно было заменить реализацию без переписывания use-case слоя;
- data слой не зависел от Spring Data/JPA.

### 5) Миграции БД (обязательно)
Миграции ведутся versioned-скриптами (например, Flyway/Liquibase):
- `V1__init_dataset_and_history.sql` — базовые таблицы (`dataset_object`, `action_log`, `history_cursor`);
- последующие миграции только forward-only;
- rollback через отдельные corrective-миграции и бэкапы.

Требования:
- миграции должны запускаться как в `app-edge-ktor`, так и в окружениях без Spring Boot;
- схема должна быть воспроизводима с нуля из набора миграций;
- проверка миграций должна входить в CI.

### 6) Доступ из Ktor + асинхронная готовность
Сервис `app-edge-ktor` должен читать/писать те же данные, что и UI, через общий контракт репозиториев.

Для async-ready подхода:
- использовать JDBC-пул (`HikariCP`) и явный `Dispatchers.IO`/корутинные boundary на старте;
- не блокировать event-loop Ktor долгими DB-операциями;
- при росте нагрузки рассмотреть R2DBC/vertx-pg-client как эволюционный шаг, не меняя доменные контракты.

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
- jOOQ/Exposed + миграции дают прозрачный контроль SQL без тяжелого Spring-стека.

## План миграции (итеративно)
1. **Шаг 1 (без риска):** ввести слой `ObjectRepository`/`HistoryRepository` в `app-kernel` и реализации в `app-edge-ktor` (jOOQ/Exposed), писать в Postgres параллельно с файловым кэшем (dual-write).
2. **Шаг 2:** добавить и применить миграции (`V1...Vn`), включить автоматическую проверку миграций в CI.
3. **Шаг 3:** читать объектные метаданные из Postgres и в UI, и в Ktor; файл-кэш оставить только как source для бинарных preview.
4. **Шаг 4:** включить undo/redo через `action_log + history_cursor` (scope USER/SESSION).
5. **Шаг 5:** отключить запись `manifest.json`, оставить файловое хранилище только для бинарных артефактов.
6. **Шаг 6 (опционально):** добавить Redis для горячих чтений и коротких TTL.

## Риски и меры
- **Dual-write рассинхронизация:** временный reconciliation job + метрики расхождений.
- **Рост action_log:** партиционирование по дате и retention policy.
- **Сложность inverse_payload:** ограничить set поддерживаемых action-типов на старте.
- **Блокирующий JDBC в Ktor:** выделенные IO-dispatcher границы + профилирование latencies.
- **Дрейф схемы между средами:** только миграции, запрет ручных изменений схемы.

## Критерии готовности
- Undo/redo проходит e2e для scope USER и SESSION.
- UI продолжает работать при отключенном Redis.
- Новый инстанс приложения поднимается без shared FS-кэша и корректно отображает состояние.
- Ktor server выполняет тот же набор read/write use-cases через общий repository-контракт.
- Миграции воспроизводят схему с нуля и проходят в CI.

## Альтернативы
1. **Оставить file-cache и добавить отдельный журнал изменений в файлах** — сложно поддерживать консистентность и блокировки.
2. **Только Redis** — слабая долговечность как source of truth для undo/redo.
3. **Kafka + event sourcing** — избыточно для текущих требований.
4. **Spring Data / JPA как основной путь** — против ограничения «избегать Spring по возможности».

## Последствия
- Появляется единый центр правды (PostgreSQL).
- Значительно упрощается реализация undo/redo.
- Локальный файловый кэш перестает быть критичной зависимостью.
- Data layer становится переиспользуемым между Vaadin UI и Ktor.
- Путь к async-эволюции формализован без немедленного усложнения.
