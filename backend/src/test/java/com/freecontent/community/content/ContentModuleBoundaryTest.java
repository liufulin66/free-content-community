package com.freecontent.community.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 防止审核用例及共享能力重新耦合创作工作区的 DTO、事务、Mapper 或限流实现。 */
class ContentModuleBoundaryTest {
    @Test
    void readUseCasesAndSharedComponentsRespectGroupBoundaries() throws IOException {
        Path content = Path.of("src/main/java/com/freecontent/community/content");
        assertTrue(Files.isDirectory(content.resolve("review")), "未找到内容模块源码，无法检查依赖边界");
        List<String> violations = new ArrayList<>();
        for (String scope : List.of("browse", "review", "service/read", "validation", "mapper", "ratelimit", "model", "dto")) {
            Path directory = content.resolve(scope);
            if (!Files.exists(directory)) continue;
            try (var files = Files.walk(directory)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                    List<String> lines = Files.readAllLines(file);
                    for (int index = 0; index < lines.size(); index++) {
                        if (lines.get(index).contains("com.freecontent.community.content.workspace.")
                                || scope.equals("browse") && lines.get(index).contains("com.freecontent.community.content.review.")) {
                            violations.add(content.relativize(file) + ":" + (index + 1) + " " + lines.get(index).trim());
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "内容浏览、审核或共享层仍存在跨用例组依赖：\n" + String.join("\n", violations));
    }
}
