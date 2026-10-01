# Бот пекарни на Дубравной

Telegram-бот и канал для одной пекарни: пекарь одним нажатием сообщает о партии из печи,
подписчики узнают об этом в канале `@tandoor_on_dubrava` и в личных сообщениях.
Полный план работ: [docs/bakery-bot-plan.md](docs/bakery-bot-plan.md).

## Стек

Kotlin 2.3 · Spring Boot 4.1 · Gradle 9.7 · JDK 21 · Flyway · Spring Data JDBC · H2 (PostgreSQL-режим) или PostgreSQL.
Бот работает через long polling — домен, вебхук и SSL не нужны.
Bot API вызывается напрямую через `RestClient` (`telegram/api`), без сторонних Telegram-библиотек.

## Запуск локально

1. Скопировать `.env.example` в `.env` и вписать `BOT_TOKEN`. Файл `.env` не попадает в git.
2. Запустить:

```bash
./gradlew bootRun
```

Spring сам подхватывает `.env` (`spring.config.import`), настоящие переменные окружения имеют приоритет.
База — файл `data/tandoor.mv.db`, создаётся при первом старте, миграции применяет Flyway.

## Тесты

```bash
./gradlew build
```

Контекст поднимается на H2 в памяти, polling в тестах выключен (`telegram.polling-enabled: false`).

## Docker

```bash
docker compose up --build
```

Контейнер ограничен 512 МБ, база лежит в `./data`, токен берётся из `.env`.

## Деплой на сервер

Серверу с 1 ГБ памяти сборка Gradle не по силам, поэтому образ собирается на рабочей машине
и передаётся готовым. На сервере нужны только Docker, этот репозиторий (ради `docker-compose.yml`)
и файл `.env` с `BOT_TOKEN` и `BOOTSTRAP_ADMINS`.

На рабочей машине (Git Bash):

```bash
docker compose build
docker save tandoor-bot:latest | gzip > build/tandoor-bot.tar.gz
scp build/tandoor-bot.tar.gz root@<сервер>:/root/
```

На сервере, в папке репозитория:

```bash
git pull
docker load < /root/tandoor-bot.tar.gz
docker compose up -d
docker compose logs --tail 20 bot
```

Если на сервере нет плагина Compose (`docker compose` отвечает «unknown shorthand flag»), то же самое
делается обычным `docker run`; значения в `.env` в этом случае пишутся без кавычек:

```bash
docker rm -f tandoor-bot 2>/dev/null
docker run -d --name tandoor-bot --restart unless-stopped --memory 512m \
  --env-file .env -e CHANNEL_ID=@tandoor_on_dubrava \
  -v "$PWD/data:/data" tandoor-bot:latest
docker logs --tail 20 tandoor-bot
```

Одновременно может работать только один экземпляр бота с одним токеном: перед запуском на сервере
локальный нужно остановить (`docker compose stop`).

## Переменные окружения

| Переменная | Назначение | По умолчанию |
| --- | --- | --- |
| `BOT_TOKEN` | Токен бота из BotFather | — (обязательна) |
| `CHANNEL_ID` | `@username` канала или числовой ID | пусто |
| `BOOTSTRAP_ADMINS` | Telegram ID стартовых админов через запятую | пусто |
| `BAKERY_TZ` | Часовой пояс пекарни для времени в сообщениях | `Europe/Moscow` |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | JDBC-подключение; для PostgreSQL достаточно сменить URL | H2-файл |

## Структура

```
src/main/kotlin/dubrava/tandoor/
  config/      свойства из окружения
  core/        ядро без Telegram: партии, подписки, каталог, позиции, роли, настройки, статистика
  telegram/    адаптер Telegram: клиент API, long polling, роутер, обработчики команд
src/main/resources/db/migration/   миграции Flyway (схема из раздела 8 плана, стартовые позиции)
```

Токен бота входит в URL запросов к Bot API, поэтому `TelegramApi` не пропускает наружу исключения Spring с этим URL: в логи попадает только имя метода и причина сбоя.

## Статус

Этапы 0–5 (каркас; кнопка пекаря, пост в канал, статусы с автопереходами, время готовности у партии; личные подписки, «Иду!», метки источника, блокировка; каталог со свежестью и редактор позиций; приглашения по ссылке, отзыв ролей, настройки таймеров и автопостов; суточный снимок и `/stats`) — готовы, 1 октября 2026. Доступ к функциям персонала проверяет роутер по роли. Следующий — этап 6: пилот; до него остаются незакрытые пункты этапа 0 (хостинг, тексты и карточки от пекарни).
