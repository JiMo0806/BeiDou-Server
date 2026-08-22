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
        Path langPath = getLanguageFile();
        Path wzPath = getBaseFile();

        // 语言版目录可能存在但为空（v1.12 镜像只填充了 String/Quest 等文本类 wz，
        // Map/Item/Mob 等留空壳）。此时必须回落到原版 wz，否则 MapFactory.loadMapFromWz
        // 会拿到 null mapData，触发 NPE 导致玩家进不去角色。
        if (Files.isDirectory(langPath) && isNonEmptyDirectory(langPath)) {
            return langPath;
        }
        return wzPath;
    }

    public Path getBaseFile() {
        return Path.of(DIRECTORY, fileName);
    }

    public Path getLanguageFile() {
        ServiceProperty serviceProperty = ServerManager.getApplicationContext().getBean(ServiceProperty.class);
        return Path.of(DIRECTORY + "-" + serviceProperty.getLanguage(), fileName);
    }

    private static boolean isNonEmptyDirectory(Path dir) {
        // 递归查找 .xml：v1.12 镜像的语言版目录可能是"只含空壳子目录"的空目录
        // （如 wz-zh-CN/Map.wz/Map/ 内没有任何 xml），只查一层会误判为有数据。
        // walk 是惰性的，找到第一个 xml 即短路返回。
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            return stream.anyMatch(p -> p.getFileName().toString().endsWith(".xml"));
        } catch (IOException e) {
            return false;
        }
    }

    public String getFilePath() {
        return getFile().toString();
    }
}
