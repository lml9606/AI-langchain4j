package com.example.langchain4j.knowledge;

import com.example.langchain4j.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 文件解析器测试：txt/md 直读、扩展名大小写不敏感、不支持类型与空文件拒绝。
 * （PDF/Word 解析依赖真实二进制文件，由接口层联调验证，不在单测构造）
 */
class KnowledgeFileParserTest {

    private final KnowledgeFileParser parser = new KnowledgeFileParser();

    @Test
    void extractPlainTextAsIs() {
        String content = "# 标题\n\n正文内容，保留换行。";
        String text = parser.extract("docs.txt", content.getBytes(StandardCharsets.UTF_8));
        assertEquals(content, text);
    }

    @Test
    void extractMarkdownCaseInsensitive() {
        String content = "## 章节\n\n段落文字";
        String text = parser.extract("DOC.MD", content.getBytes(StandardCharsets.UTF_8));
        assertEquals(content, text);
    }

    @Test
    void rejectUnsupportedType() {
        assertThrows(BizException.class, () -> parser.extract("data.xlsx", new byte[]{1, 2, 3}));
    }

    @Test
    void rejectEmptyFile() {
        assertThrows(BizException.class, () -> parser.extract("empty.txt", new byte[0]));
    }
}
