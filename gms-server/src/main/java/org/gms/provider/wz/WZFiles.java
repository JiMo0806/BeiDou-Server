package org.gms.provider.wz;

import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public enum WZFiles {
    QUEST("Quest"),
    ETC("Etc"),
    ITEM("Item"),
    CHARACTER("Character"),
    STRING("String"),
    LIST("List"),
    MOB("Mob"),
    MAP("Map"),
    NPC("Npc"),
    REACTOR("Reactor"),
    SKILL("Skill"),
    SOUND("Sound"),
    UI("UI");

    private final String fileName;
    public static final String DIRECTORY = "wz";

    WZFiles(String name) {
        this.fileName = name + ".wz";
    }

    public Path getFile() {
        // 优先取语言文件夹，没有则取wz
        Path wzPath = Path.of(DIRECTORY, fileName);
        ServiceProperty serviceProperty = ServerManager.getApplicationContext().getBean(ServiceProperty.class);
        Path langPath = Path.of(DIRECTORY + "-" + serviceProperty.getLanguage(), fileName);

        // 语言版目录可能存在但为空（v1.12 镜像只填充了 String/Quest 等文本类 wz，
        // Map/Item/Mob 等留空壳）。此时必须回落到原版 wz，否则 MapFactory.loadMapFromWz
        // 会拿到 null mapData，触发 NPE 导致玩家进不去角色。
        if (Files.isDirectory(langPath) && isNonEmptyDirectory(langPath)) {
            return langPath;
        }
        return wzPath;
    }

    private static boolean isNonEmptyDirectory(Path dir) {
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            return stream.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    public String getFilePath() {
        return getFile().toString();
    }
}
