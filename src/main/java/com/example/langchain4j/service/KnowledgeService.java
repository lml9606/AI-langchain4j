package com.example.langchain4j.service;

import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.KnowledgeBatchImportRequest;
import com.example.langchain4j.dto.KnowledgeBatchImportResponse;
import com.example.langchain4j.dto.KnowledgeHealthResponse;
import com.example.langchain4j.dto.KnowledgeSearchResponse;
import com.example.langchain4j.dto.KnowledgeUpdateRequest;
import com.example.langchain4j.knowledge.EmbeddingService;
import com.example.langchain4j.knowledge.VectorSearchService;
import com.example.langchain4j.repository.entity.KnowledgeChunk;
import com.example.langchain4j.repository.mapper.KnowledgeChunkMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识服务：父子分块导入（父块整章不嵌入 + 子块嵌入落库）、检索与知识治理
 * （批量导入/更新删除/分块可配/健康度统计）。
 */
@Slf4j
@Service
public class KnowledgeService {

    private static final int DEFAULT_TOP_K = 3;
    private static final int MAX_TOP_K = 10;
    /** 分块大小防御边界：过小碎片化、过大单块语义稀释（入口另有 maxDocumentChars 总量拦截） */
    private static final int MIN_CHUNK_SIZE = 200;
    private static final int MAX_CHUNK_SIZE = 1200;
    /** 父块大小防御边界：过小失去整章语义、过大装配时超出上下文预算 */
    private static final int MIN_PARENT_SIZE = 500;
    private static final int MAX_PARENT_SIZE = 8000;
    /** 段落边界：空行切分优先级最高，保证段落语义块完整 */
    private static final String PARAGRAPH_BOUNDARY = "\n\n";
    /** 句末边界字符：段落/行边界都找不到时退而求其次，避免把句子拦腰截断 */
    private static final String BOUNDARY_CHARS = "。！？；";
    /** 块类型：父块（整章上下文，不嵌入）/ 子块（嵌入供检索） */
    private static final String TYPE_PARENT = "PARENT";
    private static final String TYPE_CHILD = "CHILD";
    /** 子块 content 标题前缀分隔符（方案 A：父标题拼进子块，解决父标题检索不到问题） */
    private static final String TITLE_SEPARATOR = "｜";
    /** 标题行识别：Markdown 标题（#~######）或数字编号（1. / 1.1）或中文章节（第X章/节/部分） */
    private static final Pattern TITLE_LINE = Pattern.compile(
            "^\\s*(?:#{1,6}\\s+\\S|\\d+(?:\\.\\d+)*[、.．]\\s*\\S|第[一二三四五六七八九十百千\\d]+[章节篇部]).*",
            Pattern.MULTILINE);

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final EmbeddingService embeddingService;
    private final VectorSearchService vectorSearchService;
    private final AgentProperties properties;
    private final ThreadPoolTaskExecutor importExecutor;

    public KnowledgeService(KnowledgeChunkMapper knowledgeChunkMapper,
                            EmbeddingService embeddingService,
                            VectorSearchService vectorSearchService,
                            AgentProperties properties,
                            @Qualifier("knowledgeImportExecutor") ThreadPoolTaskExecutor importExecutor) {
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.embeddingService = embeddingService;
        this.vectorSearchService = vectorSearchService;
        this.properties = properties;
        this.importExecutor = importExecutor;
    }

    /** 语义检索（topK 有界防御），检索失败降级为空列表由上游处理 */
    public KnowledgeSearchResponse search(String query, int topK) {
        int bounded = Math.min(Math.max(topK, 1), MAX_TOP_K);
        List<KnowledgeChunk> chunks = vectorSearchService.search(query, bounded);
        List<KnowledgeSearchResponse.Chunk> result = chunks.stream()
                .map(c -> new KnowledgeSearchResponse.Chunk(c.getContent(), c.getMetadata()))
                .toList();
        return KnowledgeSearchResponse.of(result);
    }

    public KnowledgeSearchResponse search(String query) {
        return search(query, DEFAULT_TOP_K);
    }

    /**
     * 导入长文档（父子分块）：
     * 步骤1 按标题/章节切父块（整章上下文，约 parentChunkSize，不嵌入）；
     * 步骤2 父块内按段落切子块（约 chunkSize，标题前缀拼入 content 后嵌入）；
     * 步骤3 父块先落库拿自增 ID，子块回填 parent_id 后批量落库。
     * 子块大小/重叠可配：请求级 chunkSize/chunkOverlap 优先，缺省用 agent.knowledge 配置值；
     * 父块大小仅走配置（章节边界优先，大小是上限参考）。
     *
     * @return 落库总块数（父块 + 子块）
     */
    public int importDocument(String content, String metadata, Integer chunkSize, Integer chunkOverlap) {
        String cleaned = sanitize(content);
        if (cleaned.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "content 清洗后为空");
        }
        checkDocumentSize(cleaned);
        int childSize = boundedChunkSize(chunkSize);
        int overlap = boundedChunkOverlap(chunkOverlap, childSize);
        int parentSize = boundedParentChunkSize();
        String userId = UserContext.require();
        // 步骤1：按标题/章节切父块（同 title 长章节可拆多父块，装配时按 title 组找回）
        List<ParentSection> sections = splitParents(cleaned, parentSize);
        // 步骤2：逐父块切子块并嵌入（易失败的嵌入先行，失败时尚未落库零残留）
        List<KnowledgeChunk> parents = new ArrayList<>(sections.size());
        List<KnowledgeChunk> children = new ArrayList<>();
        List<Integer> childrenPerParent = new ArrayList<>(sections.size());
        for (int p = 0; p < sections.size(); p++) {
            ParentSection section = sections.get(p);
            KnowledgeChunk parent = new KnowledgeChunk();
            parent.setContent(section.body());
            parent.setChunkType(TYPE_PARENT);
            parent.setTitle(section.title());
            parent.setMetadata(withChunkInfo(metadata, p + 1, sections.size()));
            parent.setUserId(userId);
            parents.add(parent);
            List<String> pieces = split(section.body(), childSize, overlap);
            for (int i = 0; i < pieces.size(); i++) {
                KnowledgeChunk child = new KnowledgeChunk();
                child.setContent(section.title() + TITLE_SEPARATOR + pieces.get(i));
                child.setEmbedding(embeddingService.embed(child.getContent()));
                child.setChunkType(TYPE_CHILD);
                child.setTitle(section.title());
                child.setMetadata(withChunkInfo(metadata, i + 1, pieces.size()));
                child.setUserId(userId);
                children.add(child);
            }
            childrenPerParent.add(pieces.size());
        }
        // 步骤3：父块逐个落库拿自增 ID，子块按父块顺序回填 parent_id 后一次批量落库
        int cursor = 0;
        for (int p = 0; p < parents.size(); p++) {
            knowledgeChunkMapper.insert(parents.get(p));
            for (int i = 0; i < childrenPerParent.get(p); i++) {
                children.get(cursor + i).setParentId(parents.get(p).getId());
            }
            cursor += childrenPerParent.get(p);
        }
        if (!children.isEmpty()) {
            knowledgeChunkMapper.insertBatch(children);
        }
        log.info("[knowledge] document imported, parents={}, children={}, parentSize={}, childSize={}",
                parents.size(), children.size(), parentSize, childSize);
        return parents.size() + children.size();
    }

    /**
     * 批量导入：逐篇提交到独立导入线程池并行执行，HTTP 线程只等待结果（不执行嵌入等耗时操作）。
     * 单篇失败不中断整批（任务内 try-catch，返回逐条结果供重试失败项）；
     * 单篇超时（嵌入慢/队列积压）即取消该篇记为失败，不无限挂起请求。
     * 文档数上限按配置校验（防一次请求打爆嵌入服务）。
     */
    public KnowledgeBatchImportResponse batchImport(KnowledgeBatchImportRequest request) {
        int maxDocs = Math.max(1, properties.getKnowledge().getBatchMaxDocuments());
        List<KnowledgeBatchImportRequest.Item> items = request.items();
        if (items.size() > maxDocs) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单次最多导入 " + maxDocs + " 篇文档");
        }
        // 步骤1：逐篇提交导入任务（用户身份经 TaskDecorator 透传入工作线程）
        List<Future<KnowledgeBatchImportResponse.ItemResult>> futures = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            int index = i;
            KnowledgeBatchImportRequest.Item item = items.get(i);
            try {
                futures.add(importExecutor.getThreadPoolExecutor().submit(() -> importItem(index, item)));
            } catch (RejectedExecutionException e) {
                // 队列满：该篇记为失败（整体不抛错，其余篇照常执行），用户可按明细重试
                log.warn("[knowledge] batch import item {} rejected, queue full", index);
                futures.add(null);
            }
        }
        // 步骤2：逐篇取回结果（有界等待，超时取消），保持响应语义：单篇失败不中断整批
        long timeoutSeconds = Math.max(10, properties.getKnowledgeImport().getTimeoutSeconds());
        List<KnowledgeBatchImportResponse.ItemResult> results = new ArrayList<>(items.size());
        int imported = 0;
        int failed = 0;
        for (int i = 0; i < futures.size(); i++) {
            Future<KnowledgeBatchImportResponse.ItemResult> future = futures.get(i);
            if (future == null) {
                results.add(new KnowledgeBatchImportResponse.ItemResult(i, 0, false, "导入队列已满，请稍后重试"));
                failed++;
                continue;
            }
            try {
                KnowledgeBatchImportResponse.ItemResult result = future.get(timeoutSeconds, TimeUnit.SECONDS);
                if (result.ok()) {
                    imported++;
                } else {
                    failed++;
                }
                results.add(result);
            } catch (TimeoutException e) {
                future.cancel(true);
                log.warn("[knowledge] batch import item {} timeout after {}s, cancelled", i, timeoutSeconds);
                results.add(new KnowledgeBatchImportResponse.ItemResult(i, 0, false, "导入超时（" + timeoutSeconds + "s）"));
                failed++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results.add(new KnowledgeBatchImportResponse.ItemResult(i, 0, false, "导入被中断"));
                failed++;
            } catch (ExecutionException e) {
                log.warn("[knowledge] batch import item {} failed: {}", i, e.getMessage());
                results.add(new KnowledgeBatchImportResponse.ItemResult(i, 0, false, rootMessage(e)));
                failed++;
            }
        }
        return new KnowledgeBatchImportResponse(items.size(), imported, failed, results);
    }

    /** 单篇导入任务：失败自吞并转结果对象（单篇失败不中断整批的语义在任务内闭环） */
    private KnowledgeBatchImportResponse.ItemResult importItem(int index, KnowledgeBatchImportRequest.Item item) {
        try {
            int chunks = importDocument(item.content(), item.metadata(), item.chunkSize(), item.chunkOverlap());
            return new KnowledgeBatchImportResponse.ItemResult(index, chunks, true, null);
        } catch (Exception e) {
            log.warn("[knowledge] batch import item {} failed: {}", index, e.getMessage());
            return new KnowledgeBatchImportResponse.ItemResult(index, 0, false, e.getMessage());
        }
    }

    /** 取异常链最深处的 message（业务原因通常在最底层），避免向上只看到包装信息 */
    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return String.valueOf(cur.getMessage());
    }

    /**
     * 更新知识块：归属校验（仅本人私有块可改，公共库块 403）→ 重嵌 → 更新。
     * SQL 层再按 user_id 过滤（双保险，防 TOCTOU 越权）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateChunk(long id, KnowledgeUpdateRequest request) {
        String userId = UserContext.require();
        KnowledgeChunk existing = knowledgeChunkMapper.selectById(id, userId);
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识块不存在或不可见");
        }
        if (existing.getUserId() == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "公共库知识块仅运营侧可维护");
        }
        String content = sanitize(request.getContent());
        if (content.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "content 清洗后为空");
        }
        checkDocumentSize(content);
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setContent(content);
        chunk.setEmbedding(embeddingService.embed(content));
        chunk.setMetadata(request.getMetadata());
        chunk.setUserId(userId);
        if (knowledgeChunkMapper.updateContent(chunk) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识块不存在或已被删除");
        }
        log.info("[knowledge] chunk updated, id={}", id);
    }

    /** 删除知识块：仅本人私有块可删（公共库块 403），SQL 层 user_id 过滤双保险 */
    public void deleteChunk(long id) {
        String userId = UserContext.require();
        KnowledgeChunk existing = knowledgeChunkMapper.selectById(id, userId);
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识块不存在或不可见");
        }
        if (existing.getUserId() == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "公共库知识块仅运营侧可维护");
        }
        knowledgeChunkMapper.deleteById(id, userId);
        log.info("[knowledge] chunk deleted, id={}", id);
    }

    /** 知识库健康度：当前用户可见范围（公共库 + 本人私有库）聚合统计 */
    public KnowledgeHealthResponse health() {
        return KnowledgeHealthResponse.from(knowledgeChunkMapper.healthStats(UserContext.require()));
    }

    /**
     * 长文档切分：回退窗口内按“段落 > 行尾 > 句末标点”优先级找边界（保证语义块完整），
     * 无边界则硬切；chunkOverlap 保留跨块边界的语义连续。
     * 父子分块中作为子块切分复用（父块内按段落切最小检索单元）。
     */
    List<String> split(String content, int chunkSize, int chunkOverlap) {
        if (content.length() <= chunkSize) {
            return List.of(content);
        }
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + chunkSize, content.length());
            if (end < content.length()) {
                // 回退窗口 [start + chunkSize/2, end)：按优先级找切分终点，避免切断段落/句子
                int boundaryEnd = lastBoundaryEnd(content, start + chunkSize / 2, end);
                if (boundaryEnd > 0) {
                    end = boundaryEnd;
                }
            }
            String piece = content.substring(start, end).trim();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            if (end >= content.length()) {
                break;
            }
            // 下一块起点回退 overlap；至少前进 1，防边界计算异常导致死循环
            start = Math.max(end - chunkOverlap, start + 1);
        }
        return pieces;
    }

    /**
     * 在 [from, to) 范围内按优先级找切分终点（exclusive）：
     * 段落边界（空行）> 行尾（换行）> 句末标点；均无返回 -1（调用方硬切）。
     */
    private int lastBoundaryEnd(String content, int from, int to) {
        int paragraph = content.lastIndexOf(PARAGRAPH_BOUNDARY, to - 1);
        if (paragraph >= from) {
            return paragraph + PARAGRAPH_BOUNDARY.length();
        }
        int line = content.lastIndexOf('\n', to - 1);
        if (line >= from) {
            return line + 1;
        }
        int punct = lastBoundary(content, from, to);
        return punct >= 0 ? punct + 1 : -1;
    }

    /** 在 [from, to) 范围内找最后一个句末标点位置；无则返回 -1 */
    private int lastBoundary(String content, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            if (BOUNDARY_CHARS.indexOf(content.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 入口 token 拦截：单篇内容超过上限直接拒绝导入/更新。
     * 拒绝而非截断：静默截断会丢内容且用户无感知，显式报错让用户自行拆分后重导。
     */
    private void checkDocumentSize(String content) {
        int maxChars = Math.max(1, properties.getKnowledge().getMaxDocumentChars());
        if (content.length() > maxChars) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "内容过长：" + content.length() + " 字符，单篇上限 " + maxChars + " 字符，请拆分后导入");
        }
    }

    /** 分块大小防御边界：200~1200（过小碎片化、过大单块语义稀释） */
    private int boundedChunkSize(Integer requestChunkSize) {
        int configured = properties.getKnowledge().getChunkSize();
        int size = requestChunkSize != null ? requestChunkSize : configured;
        return Math.min(Math.max(size, MIN_CHUNK_SIZE), MAX_CHUNK_SIZE);
    }

    /** 父块大小防御边界：500~8000（仅走配置，章节边界优先于大小硬切） */
    private int boundedParentChunkSize() {
        int configured = properties.getKnowledge().getParentChunkSize();
        return Math.min(Math.max(configured, MIN_PARENT_SIZE), MAX_PARENT_SIZE);
    }

    /** 分块重叠防御边界：0 ~ chunkSize/2（负值归零，过大反而稀释块语义） */
    private int boundedChunkOverlap(Integer requestOverlap, int chunkSize) {
        int configured = properties.getKnowledge().getChunkOverlap();
        int overlap = requestOverlap != null ? requestOverlap : configured;
        return Math.min(Math.max(overlap, 0), chunkSize / 2);
    }

    /** 元数据追加分块信息（chunkIndex/chunkCount）：原 JSON 对象合并，非 JSON 原文保留在 raw 字段 */
    private String withChunkInfo(String metadata, int index, int total) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (metadata != null && !metadata.isBlank()) {
            try {
                Map<String, Object> parsed = JsonUtil.fromJson(metadata, new TypeReference<>() {
                });
                if (parsed != null) {
                    map.putAll(parsed);
                }
            } catch (Exception e) {
                map.put("raw", metadata);
            }
        }
        map.put("chunkIndex", index);
        map.put("chunkCount", total);
        return JsonUtil.toJson(map);
    }

    /**
     * 父子分块第一层：按标题/章节行切父块（整章上下文，不嵌入）。
     * <ul>
     *   <li>有标题行 → 标题行开新节；首个标题前的正文（前言）用兜底标题单独成节；</li>
     *   <li>无标题行 → 单一节，title 取首行（≤40 字）否则前 100 字；</li>
     *   <li>节超父块大小 → 复用 {@link #split} 续切成多个同 title 父块
     *       （同 title 组装配补长章节被拆散的漏）。</li>
     * </ul>
     */
    List<ParentSection> splitParents(String content, int parentSize) {
        Matcher matcher = TITLE_LINE.matcher(content);
        List<Integer> titleStarts = new ArrayList<>();
        while (matcher.find()) {
            titleStarts.add(matcher.start());
        }
        if (titleStarts.isEmpty()) {
            String title = deriveTitle(content);
            return split(content, parentSize, 0).stream()
                    .map(piece -> new ParentSection(title, piece))
                    .toList();
        }
        List<ParentSection> sections = new ArrayList<>();
        // 标题前的正文（前言）单独成节，title 用兜底标题
        if (titleStarts.get(0) > 0) {
            String preamble = content.substring(0, titleStarts.get(0)).trim();
            if (!preamble.isEmpty()) {
                String title = deriveTitle(preamble);
                for (String piece : split(preamble, parentSize, 0)) {
                    sections.add(new ParentSection(title, piece));
                }
            }
        }
        // 相邻标题行之间为一个节；节首行即标题（超长节续切为同 title 多父块）
        for (int i = 0; i < titleStarts.size(); i++) {
            int begin = titleStarts.get(i);
            int end = i + 1 < titleStarts.size() ? titleStarts.get(i + 1) : content.length();
            String section = content.substring(begin, end).trim();
            if (section.isEmpty()) {
                continue;
            }
            String title = extractTitle(section);
            for (String piece : split(section, parentSize, 0)) {
                sections.add(new ParentSection(title, piece));
            }
        }
        return sections;
    }

    /** 父块切分单元：title（紧凑标题，装配分组键）+ body（整章上下文） */
    record ParentSection(String title, String body) {
    }

    /** 节首行即标题（标题行 ≤100 字防御截断）；首行过长时退化为前 100 字 */
    private String extractTitle(String section) {
        int end = section.indexOf('\n');
        String first = end < 0 ? section : section.substring(0, end);
        String trimmed = first.trim();
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    /** 无标题兜底标题：首行（非空且 ≤40 字）优先，否则取前 100 字截断 */
    private String deriveTitle(String content) {
        int end = content.indexOf('\n');
        String first = end < 0 ? content : content.substring(0, end);
        String trimmed = first.trim();
        if (!trimmed.isEmpty() && trimmed.length() <= 40) {
            return trimmed;
        }
        String body = content.trim();
        return body.length() > 100 ? body.substring(0, 100) : body;
    }

    /** 控制字符清洗 + 边界空白清理（嵌入前的防御性处理） */
    private String sanitize(String content) {
        return content.replaceAll("[\\p{Cntrl}]", " ").trim();
    }
}
