> **Language:** Русский · [English](README.en.md)

<div align="center">

  <img width="150" alt="Логотип Atlas Map (Extended)" src="images/icon.png">
  <h3>Atlas Map (Extended)</h3>

</div>

<div align="center">

![Java 21+](https://img.shields.io/badge/Java-21+-blue.svg)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21-blue.svg)
![Fabric](https://img.shields.io/badge/Loader-Fabric-blue.svg)
![NeoForge](https://img.shields.io/badge/Loader-NeoForge-blue.svg)
![License](https://img.shields.io/badge/License-MIT-blue.svg)

</div>

Клиентский мод полноэкранная карта мира и миникарта, метки с иконками, описаниями и скриншотами, линейка, рисование на карте, телепорт к меткам и **8 вариантов оформления**.

> Мод сделан и портирован с помощью ИИ по идеям автора и зрителей.

<div align="center">

  [<img width="900" alt="Скачать на CurseForge" src="images/curseforge_banner.png">](https://www.curseforge.com/minecraft/mc-mods/atlas-map-extended)

</div>

## Галерея
| Карта мира | Миникарта |
| :---: | :---: |
| ![Карта](article_screenshots/webp/04_karta_spisok_i_metka.webp) | ![Миникарта](article_screenshots/webp/02_minikarta.webp) |

| Настройки | Темы оформления |
| :---: | :---: |
| ![Настройки](article_screenshots/webp/15_nastrojki.webp) | ![Все 8 тем](article_screenshots/webp/00_vse_8_dizajnov.webp) |

## Возможности

- **Карта мира** (клавиша `M`): рисуется сама, пока вы ходите по миру. Работают масштаб, перетаскивание и переключение измерений.
- **Миникарта** (`N`, `=`/`-`): можно выбрать размер, угол экрана, масштаб и показ координат.
- **Метки**: ставятся правой кнопкой мыши по карте или клавишей `B` (метка на месте игрока). У метки есть название, координаты, описание и скриншоты.
- **Список меток** (`J`): поиск, сортировка, скрытие меток.
- **183 иконки** с поиском. Любую иконку можно **перекрасить**: правая кнопка мыши по иконке открывает палитру из 60 цветов.
- **Скриншоты в метках**: добавляются из папки скриншотов, через окно выбора файла или перетаскиванием. Поверх скриншота можно рисовать.
- **Линейка** (`R`): меряет расстояние по нескольким точкам.
- **Рисование на карте** (`G`): ручка, линия, прямоугольник, круг и ластик. Есть 60 цветов и отмена действия.
- **Телепорт к метке**: включается в настройках. Работает, если разрешены команды (читы в одиночной игре или права оператора на сервере).
- **Настройки** (кнопка-шестерёнка на карте и в главном меню). Оформление на выбор: Папирус, Камень, Сланец, Светлый, Тёмный, Дерево, Незер, Энд.
- **Атлас карт** в главном меню: карты, метки и скриншоты хранятся отдельно для каждого сервера и мира.
- Интерфейс на русском и английском языках.

## Установка

Мод работает на **Fabric** и **NeoForge**. Fabric API и другие библиотеки не требуются.

1. Установите [Fabric Loader](https://fabricmc.net/use/) (0.15.11 или новее) или [NeoForge](https://neoforged.net/) для Minecraft **1.21**.
2. Положите в папку `mods` один JAR под свой загрузчик:
   - `AtlasMapExtended-Fabric-1.21-v1.1.1.jar` — для Fabric;
   - `AtlasMapExtended-NeoForge-1.21-v1.1.1.jar` — для NeoForge.
3. Запустите игру.

Мод нужен только на клиенте, ставить его на сервер не надо.

## Управление

| Клавиша | Действие |
|---|---|
| `M` | Открыть или закрыть карту |
| `J` | Список меток |
| `B` | Метка на месте игрока |
| `N` | Включить или выключить миникарту |
| `=` / `-` | Масштаб миникарты |
| `R` | Линейка (на карте) |
| `G` | Рисование (на карте) |
| `H` | Показать или скрыть рисунки |
| ПКМ по иконке | Перекрасить иконку метки |

Все клавиши можно переназначить в стандартных настройках управления Minecraft.

## Где хранятся данные

`.minecraft/atlasmapextended/worlds/<id>/`: тайлы карты, метки (`markers.json`), рисунки (`drawings.json`) и скриншоты меток. Настройки лежат в `.minecraft/config/atlasmapextended.json`.

## Сборка

Нужен JDK 21 или новее.

```bash
./gradlew build
```

Команда собирает сразу два файла в `build/libs/`:

- `AtlasMapExtended-Fabric-1.21-v1.1.1.jar`
- `AtlasMapExtended-NeoForge-1.21-v1.1.1.jar`

Игровые тесты со скриншотами доступны в ветках `26.1` и `26.3` (в Fabric API для 1.21 нет client-gametest модуля).

## Авторы и лицензии

- **Автор мода:** [erkinpaw](https://github.com/erkinpaw) · **Лицензия:** [MIT](https://github.com/byMr712/AtlasMapExtended-MinecraftMod/blob/26.3/LICENSE).  
- **Автор порта на Fabric/Neoforge 1.21:** [Mr712](https://github.com/byMr712/AtlasMapExtended-MinecraftMod) · **Лицензия:** [Apache License 2.0](LICENSE).
