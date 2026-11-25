package com.example.langchain4j.knowledge.parser;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * 知识导入文件解析器（Apache Tika 实现）：扩展名白名单 + 魔数自动检测，统一提取纯文本。
 *
 * - 第一道防线：扩展名白名单（txt/md/markdown/pdf/docx），其余类型直接拒绝，防止垃圾文件污染知识库；
 * - 第二道防线：Tika 按魔数（MIME）自动选择解析器——不信任扩展名，伪造扩展名的文件按真实类型解析；
 * - 第三道防线：BodyContentHandler writeLimit 限制抽取文本量，防恶意/超大文档撑爆内存（压缩炸弹等）。
 *
 * 设计边界：只做"二进制 → 纯文本"，切块 / 嵌入 / 落库仍复用
 */
@Component
public class KnowledgeFileParser {

    private static final Set<String> SUPPORTED_EXT = Set.of("txt", "md", "markdown", "pdf", "docx");

    /** 抽取文本上限（字符）：知识库单篇仅允许 agent.knowledge.max-document-chars（默认 5 万）字符，此处 100 万仅为防御恶意文档撑爆内存 */
    private static final int MAX_EXTRACTED_CHARS = 1_000_000;

    /**
     * 提取文件文本。
     *
     * @param fileName 原始文件名（扩展名白名单检查用，大小写不敏感；实际解析按魔数走 Tika）
     * @param bytes    文件字节（空数组视为空文件）
     * @return 提取出的纯文本（保留换行，交给上游 sanitize/切块）
     * @throws BizException 类型不支持 / 文件为空 / 解析失败 / 内容超限（BAD_REQUEST 语义）
     */
    public String extract(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件内容为空");
        }
        String ext = extensionOf(fileName);
        if (!SUPPORTED_EXT.contains(ext)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "不支持的文件类型: " + ext + "（支持 txt / md / pdf / docx）");
        }
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            Metadata metadata = new Metadata();
            // 文件名提示 Tika 在纯文本等歧义场景选择解析器；真实类型仍以魔数为准
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
            BodyContentHandler handler = new BodyContentHandler(MAX_EXTRACTED_CHARS);
            new AutoDetectParser().parse(in, handler, metadata, new ParseContext());
            // 尾随空白归一化：Tika 文本解析器会在结尾多输出换行，且尾随空白对切块无信息量
            return handler.toString().stripTrailing();
        } catch (SAXException e) {
            if (e.getCause() instanceof WriteLimitReachedException) {
                throw new BizException(ErrorCode.BAD_REQUEST, "文档内容过大，超过抽取上限");
            }
            throw new BizException(ErrorCode.BAD_REQUEST, "文档解析失败: " + e.getMessage());
        } catch (TikaException | IOException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文档解析失败: " + e.getMessage());
        }
    }

    /** 取小写扩展名（无扩展名返回空串，走不支持分支） */
    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
