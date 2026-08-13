package ru.taskflow.task.application;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Цвет и иконка для группы, создаваемой автоматически (например, ассистентом
 * по названию из реплики). Словарь, а не хэш и не запрос к модели: хэш даёт
 * стабильный, но бессмысленный цвет без иконки; спрашивать цвет с иконкой у
 * модели — новый параметр в контракте инструментов ради того, что решается
 * один раз за жизнь группы (см. recurrence и update_task(group) — тот же урок).
 * Ключи — из уже существующих реестров фронтенда: miniapp/src/lib/group-icons.ts
 * (иконки) и GROUP_CARD_STYLE/getColorHex в GroupsPage.tsx (цвета); новых не изобретать,
 * иначе фронтенд отрисует заглушку вместо назначенного стиля.
 */
@Component
public class GroupStyleResolver {

    public record GroupStyle(String color, String icon) {}

    private static final List<String> COLORS =
            List.of("blue", "purple", "green", "orange", "red", "yellow", "pink", "cyan");

    private static final Map<String, GroupStyle> KNOWN = Map.ofEntries(
            Map.entry("покупки", new GroupStyle("orange", "shopping")),
            Map.entry("работа", new GroupStyle("blue", "briefcase")),
            Map.entry("здоровье", new GroupStyle("red", "pill")),
            Map.entry("дом", new GroupStyle("yellow", "home")),
            Map.entry("учёба", new GroupStyle("purple", "school")),
            Map.entry("личное", new GroupStyle("pink", "palette")),
            Map.entry("финансы", new GroupStyle("green", "cash")),
            Map.entry("спорт", new GroupStyle("cyan", "run")),
            Map.entry("семья", new GroupStyle("orange", "users"))
    );

    public GroupStyle resolve(String name) {
        String normalized = normalize(name);
        GroupStyle known = KNOWN.get(normalized);
        if (known != null) {
            return known;
        }
        int index = Math.floorMod(normalized.hashCode(), COLORS.size());
        return new GroupStyle(COLORS.get(index), "folder");
    }

    private String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
